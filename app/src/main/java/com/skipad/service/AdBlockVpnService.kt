package com.skipad.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.skipad.R
import com.skipad.core.blocklist.BlocklistEngine
import com.skipad.core.vpn.DnsTunnel
import com.skipad.core.vpn.UpstreamResolver
import com.skipad.data.log.BlockLogStore
import com.skipad.data.prefs.BlockMode
import com.skipad.data.prefs.SettingsRepository
import com.skipad.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 广告拦截 VPN 服务。
 *
 * 该服务只接管 DNS 查询，其余流量完全交由系统路由处理。
 */
class AdBlockVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 建立隧道的互斥锁。
     *
     * 服务可能被多处同时触发启动（Activity 恢复、onStartCommand、磁贴等），
     * 若不串行化，会并发执行两次 establish()：
     * 后一次会顶掉前一次的接口，前一个 fd 立即变为 EOF，
     * 导致读取线程空转、tun0 变成 DOWN，隧道彻底失效。
     */
    private val startMutex = Mutex()
    private var tunnel: DnsTunnel? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var upstreamResolver: com.skipad.core.vpn.DnsForwarder? = null

    private val isRunning = AtomicBoolean(false)
    /**
     * 待落库的拦截域名 → 拦截发生时的前台应用包名。
     *
     * 空串表示「当时不知道前台应用」（未授权无障碍），落库时按无归属处理。
     */
    private val pendingBlocked = ConcurrentHashMap<String, String>()

    /** 运行期统计数据。 */
    @Volatile
    var sessionStats: SessionStats = SessionStats()
        private set

    // ---- 供 UI 读取的运行状态 ----

    @Volatile
    private var currentBlockMode: BlockMode = BlockMode.BLOCK

    /** 用户是否选择了静默通知（Android 不允许完全隐藏前台服务通知）。 */
    @Volatile
    private var silentNotification = false

    @Volatile
    private var currentEngine: BlocklistEngine? = null

    @Volatile
    private var currentRuleCount: Int = 0

    data class SessionStats(
        val received: Long = 0,
        val blocked: Long = 0,
        val forwarded: Long = 0,
        val errors: Long = 0,
        val ruleCount: Int = 0,
    )

    companion object {
        private const val TAG = "SkipAdVpn"
        /** 状态通知渠道 ID。供保活通知复用，保证用户可在同一处管理。 */
        const val CHANNEL_ID = "skipad_status"

        /** 静默通知渠道 ID。 */
        const val CHANNEL_ID_SILENT = "skipad_status_silent"
        private const val NOTIFICATION_ID = 1001
        private const val FLUSH_INTERVAL_MS = 5000L

        /** 拦截日志保留天数。 */
        private const val LOG_RETENTION_DAYS = 30

        /**
         * TUN 接口自身的地址。系统会把它当作 VPN 侧的网络标识。
         */
        private val TUN_ADDRESS_V4 = com.skipad.core.vpn.VpnConfig.TUN_ADDRESS_V4
        private val TUN_ADDRESS_V6 = com.skipad.core.vpn.VpnConfig.TUN_ADDRESS_V6

        private val PROXY_IPV4 = com.skipad.core.vpn.VpnConfig.PROXY_IPV4
        private val PROXY_IPV6 = com.skipad.core.vpn.VpnConfig.PROXY_IPV6

        /** 用于强制重建隧道的信号。 */
        const val ACTION_RELOAD = "com.skipad.action.RELOAD"

        @Volatile
        var instance: AdBlockVpnService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // 必须在此立即进入前台：系统要求 startForegroundService() 之后 5 秒内
        // 调用 startForeground()，否则会抛 ForegroundServiceDidNotStartInTimeException
        // 并杀死进程。而规则解析可能耗时 20 秒以上，绝不能放在这之前。
        startForegroundCompat("正在加载规则…")

        // 静默通知开关（Android 不允许真正隐藏前台服务通知，只能做到不打扰）
        scope.launch {
            silentNotification = runCatching {
                SettingsRepository(applicationContext).settings.first().quietNotification
            }.getOrDefault(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            scope.launch { restartTunnel() }
            return START_STICKY
        }
        scope.launch { startTunnel() }
        // 启动后台自检：即使本进程之后被厂商 ROM 清理，也要能被重新拉起
        KeepAliveScheduler.scheduleWatchdog(this)
        // START_STICKY 的重投递：进程被杀后系统会重建服务并重投递最后一个 Intent
        return START_STICKY
    }

    /**
     * 用户把应用从最近任务划掉时触发。
     *
     * 默认服务不会因此停止，但部分 ROM 会连带清理后台。
     * 这里安排一次快速重启，尽量缩短防护空窗期。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val enabled = runCatching {
            kotlinx.coroutines.runBlocking {
                SettingsRepository(applicationContext).settings.first().protectionEnabled
            }
        }.getOrDefault(false)

        if (enabled) {
            Log.i(TAG, "任务被移除，安排恢复")
            KeepAliveScheduler.scheduleRestart(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning.set(false)
        // 非用户主动关闭时，安排自检以便尽快恢复
        val stillWanted = runCatching {
            kotlinx.coroutines.runBlocking {
                SettingsRepository(applicationContext).settings.first().protectionEnabled
            }
        }.getOrDefault(false)
        if (stillWanted) {
            KeepAliveScheduler.scheduleWatchdog(this)
        }
        tunnel?.stop()
        tunnel = null
        upstreamResolver?.close()
        upstreamResolver = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        instance = null
        // 显式移除常驻通知：
        // 若不清理，异常终止/反复启停后可能残留一条无法划掉的「幽灵通知」，
        // 系统 UI 还会持续报 NameNotFoundException 找不到该通知的图标。
        removeForegroundNotification()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // 用户在系统设置中关闭了 VPN
        isRunning.set(false)
        removeForegroundNotification()
        tunnel?.stop()
        tunnel = null
        scope.launch {
            SettingsRepository(applicationContext).setProtectionEnabled(false)
        }
        super.onRevoke()
    }

    private suspend fun startTunnel() = startMutex.withLock {
        if (isRunning.get()) return@withLock

        val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.protectionEnabled) {
            stopSelf()
            return@withLock
        }

        // 加载规则。这里耗时直接决定「从点开到真正开始拦截」的等待时长，
        // 因此记录分段耗时，便于版本回归时对比。
        val t0 = android.os.SystemClock.elapsedRealtime()
        val engine = BlocklistEngine()
        val filterManager = com.skipad.data.filter.FilterManager(applicationContext)
        val rules = try {
            filterManager.loadRules(settings.enabledFilterIds, settings.customBlockDomains)
        } catch (e: Exception) {
            Log.e(TAG, "规则加载失败", e)
            emptyList()
        }
        val t1 = android.os.SystemClock.elapsedRealtime()
        engine.replaceRules(rules, settings.allowList)
        val t2 = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "startTunnel: 规则 ${rules.size} 条 | 解析 ${t1 - t0}ms | 建索引 ${t2 - t1}ms")

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(TUN_ADDRESS_V4, 32)
            .addAddress(TUN_ADDRESS_V6, 128)
            .addDnsServer(com.skipad.core.vpn.VpnConfig.PROXY_DNS_V4)
            .addDnsServer(com.skipad.core.vpn.VpnConfig.PROXY_DNS_V6)
            // 仅把发往代理 DNS 的流量导入 TUN，其余流量走系统默认路由。
            // 这保证我们不需要实现完整的 TCP/IP 协议栈。
            .addRoute(com.skipad.core.vpn.VpnConfig.PROXY_DNS_V4, 32)
            .addRoute(com.skipad.core.vpn.VpnConfig.PROXY_DNS_V6, 128)
        // 显式设为阻塞模式：DnsTunnel 用 FileInputStream.read() 阻塞等待报文。
        // 若为非阻塞，read() 会立即返回空值，导致读取线程空转甚至退出。
        builder.setBlocking(true)

        // 排除用户指定的应用
        settings.excludedPackages.forEach { packageName ->
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        Log.i(TAG, "startTunnel: 开始建立隧道")
        val descriptor = builder.establish()
        if (descriptor == null) {
            Log.e(TAG, "VPN 建立失败")
            stopSelf()
            return@withLock
        }
        vpnInterface = descriptor
        Log.i(TAG, "startTunnel: 隧道已建立 fd=${descriptor.fd}")

        // 上游解析器：明文 UDP 需要 protect 防止回环；
        // DoH 走 HTTPS(443)，不经过隧道，无需 protect。
        //
        // DoH 端点的主机名必须在**建立隧道之前**解析好：
        // 隧道一旦建立，端点域名的解析请求会再次进入隧道，形成无限递归。
        val resolver: com.skipad.core.vpn.DnsForwarder =
            if (com.skipad.data.filter.DohForwarder.isDohEndpoint(settings.upstreamDns)) {
                val ips = com.skipad.data.filter.DohForwarder.resolveEndpoint(settings.upstreamDns)
                Log.i(TAG, "上游使用 DoH：${settings.upstreamDns}，预解析 ${ips.size} 个地址")
                com.skipad.data.filter.DohForwarder(settings.upstreamDns, ips)
            } else {
                UpstreamResolver(settings.upstreamDns) { socket: DatagramSocket ->
                    protectSocket(socket)
                }
            }
        upstreamResolver = resolver

        val logStore = BlockLogStore(applicationContext)
        // 启动时清理过期日志，避免数据库无限增长
        runCatching { logStore.pruneOlderThan(LOG_RETENTION_DAYS) }

        val newTunnel = DnsTunnel(
            input = FileInputStream(descriptor.fileDescriptor),
            output = FileOutputStream(descriptor.fileDescriptor),
            proxyIpv4 = PROXY_IPV4,
            proxyIpv6 = PROXY_IPV6,
            upstreamResolver = resolver,
            isBlocked = { domain -> engine.isBlocked(domain) },
            blockModeProvider = { currentBlockMode },
            onBlocked = { domain ->
                pendingBlocked.putIfAbsent(
                    domain,
                    SkipAccessibilityService.foregroundPackage.orEmpty(),
                )
            },
            onError = { message -> Log.e(TAG, message) },
        )
        tunnel = newTunnel
        newTunnel.start()
        isRunning.set(true)
        Log.i(TAG, "startTunnel: 读取线程已启动")

        currentBlockMode = settings.blockMode
        currentEngine = engine
        currentRuleCount = rules.size

        // 持久化规则数，使界面在服务停止后仍能正确显示
        runCatching {
            SettingsRepository(applicationContext).setLoadedRuleCount(rules.size)
        }

        // 隧道已建立，把通知文案从「正在加载」切换为运行状态
        updateNotification(getString(R.string.notification_text))

        // 定期把内存中的拦截记录落库
        scope.launch {
            while (isRunning.get()) {
                kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
                flushPending(logStore)
            }
        }
    }

    /**
     * 查询某域名当前是否会被拦截。
     *
     * @return null 表示隧道尚未建立、引擎不可用（调用方需自行按需构建）
     */
    fun matchDomain(domain: String): Boolean? {
        val engine = currentEngine ?: return null
        return engine.isBlocked(domain)
    }

    /**
     * 热更新规则集，**不重建隧道**。
     *
     * 用户增删白名单或自定义拦截域名后调用。
     * 重建隧道会造成几秒的防护空窗，这里只替换索引（写锁保护，查询无感）。
     */
    fun reloadEngine() {
        scope.launch {
            val engine = currentEngine ?: return@launch
            val settings = runCatching {
                SettingsRepository(applicationContext).settings.first()
            }.getOrNull() ?: return@launch

            runCatching {
                val filterManager = com.skipad.data.filter.FilterManager(applicationContext)
                val rules = filterManager.loadRules(
                    settings.enabledFilterIds,
                    settings.customBlockDomains,
                )
                engine.replaceRules(rules, settings.allowList)
                currentRuleCount = rules.size
                SettingsRepository(applicationContext).setLoadedRuleCount(rules.size)
                Log.i(TAG, "规则已热更新：${rules.size} 条")
            }.onFailure {
                Log.e(TAG, "热更新规则失败", it)
            }
        }
    }

    private suspend fun restartTunnel() {
        startMutex.withLock {
            tunnel?.stop()
            tunnel = null
            runCatching { vpnInterface?.close() }
            vpnInterface = null
            isRunning.set(false)
        }
        // 重新获取锁建立新隧道
        startTunnel()
    }

    private fun flushPending(logStore: BlockLogStore) {
        if (pendingBlocked.isEmpty()) return
        val snapshot = pendingBlocked.toMap()
        pendingBlocked.clear()
        // 按「拦截发生时的前台应用」分组落库，保证归因不被刷新时机带偏
        runCatching {
            snapshot.entries.groupBy({ it.value }, { it.key }).forEach { (pkg, domains) ->
                logStore.recordAll(domains, appPackage = pkg.ifEmpty { null })
            }
        }
    }

    private fun protectSocket(socket: DatagramSocket) {
        runCatching { protect(socket) }
    }

    /**
     * 进入前台并显示状态通知。
     *
     * 无论用户是否允许通知，都必须调用 startForeground ——
     * 这是前台服务的硬性要求，否则服务会在 5 秒后被系统判定为超时。
     * 「关闭通知」的实际效果是通知渠道被系统屏蔽，而非停止前台化。
     */
    private fun startForegroundCompat(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 移除前台通知。
     */
    private fun removeForegroundNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * 更新通知文案（隧道就绪后从「正在加载」变为「运行中」）。
     */
    private fun updateNotification(statusText: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    /**
     * 构建前台通知。
     *
     * @param silent 静默模式：使用最低重要度渠道，不显示时间、不发出声音。
     *               Android 强制要求前台服务必须有一条通知，**无法真正隐藏**，
     *               静默模式只能做到「存在但不打扰」。
     */
    private fun buildNotification(statusText: String, silent: Boolean = silentNotification): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(
            this,
            if (silent) CHANNEL_ID_SILENT else CHANNEL_ID,
        )
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)

        if (silent) {
            builder.setShowWhen(false)
            builder.setSilent(true)
            builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        // 静默渠道：最低重要度，不显示时间、不响铃、不在锁屏露出内容。
        // Android 强制要求前台服务必须有一条通知，无法真正隐藏，
        // 静默模式的价值是「存在但不打扰」。
        val silent = NotificationChannel(
            CHANNEL_ID_SILENT,
            getString(R.string.notification_channel_silent_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(silent)
    }

    fun isActive(): Boolean = isRunning.get()

    fun statsSnapshot(): SessionStats {
        val t = tunnel?.stats
        return SessionStats(
            received = t?.received ?: 0,
            blocked = t?.blocked ?: 0,
            forwarded = t?.forwarded ?: 0,
            errors = t?.errors ?: 0,
            ruleCount = currentRuleCount,
        )
    }
}

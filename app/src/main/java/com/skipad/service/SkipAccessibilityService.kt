package com.skipad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import com.skipad.core.skip.BuiltInSkipRules
import com.skipad.core.skip.ClickGuard
import com.skipad.core.skip.SkipMatcher
import com.skipad.core.skip.SkipNode
import com.skipad.core.skip.SkipRule
import com.skipad.core.skip.SkipRuleIndex
import com.skipad.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍广告跳过服务。
 *
 * 职责：
 * 1. 监听窗口内容变化事件
 * 2. 遍历当前窗口的节点树，构造快照
 * 3. 用规则匹配出可点击的「跳过」按钮
 * 4. 通过 `dispatchGesture` 模拟点击
 *
 * 安全约束：
 * - 同一目标在一次应用会话内只点击一次（去重）
 * - 规则级冷却，避免高频误触
 * - 单次会话最多扫描 N 次，防止无限循环消耗电量
 * - 命中后记录日志，便于用户核查误触情况
 */
class SkipAccessibilityService : AccessibilityService() {

    /**
     * 协程异常兜底。
     *
     * `launch` 里抛出的未捕获异常会走线程默认处理器 → **整个进程崩溃**。
     * 无障碍服务一旦崩溃，系统会把它记成 `Crashed services`，
     * 在「已下载的应用」里显示「无法运行」，并且不会自动重新绑定 ——
     * 用户必须手动开关一次才能恢复。
     *
     * 所以服务内的所有异步工作都必须有兜底，宁可这一次加载失败，
     * 也不能把服务本身搞挂。
     */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "协程异常已拦截", throwable)
        SkipCrashLog.record(applicationContext, "无障碍服务协程异常", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)
    private val clickGuard = ClickGuard()
    private val running = AtomicBoolean(false)

    /** 规则索引。 */
    @Volatile
    private var ruleIndex: SkipRuleIndex = SkipRuleIndex(BuiltInSkipRules.rules)

    /** 命中的跳过次数。 */
    private val skipCount = AtomicLong(0)

    /**
     * 当前应用会话开始的时间戳。
     *
     * 取代原先的「事件计数」预算：开屏期间应用会抛出大量
     * TYPE_WINDOW_CONTENT_CHANGED（列表、动画、广告 SDK 自身都在刷新），
     * 用事件数做预算会在跳过按钮真正出现前就耗尽，导致完全跳不过。
     * 改为按时间窗限制，与事件数量解耦。
     */
    private var sessionStartedAt = 0L

    /** 上次扫描时间，用于节流，避免每帧都遍历整棵节点树。 */
    private var lastScanAt = 0L

    /** 当前应用包名。 */
    private var currentPackage: String? = null

    /** 当前 Activity 类名。 */
    private var currentActivity: String? = null

    /** 无障碍功能是否已启用（用户设置项）。 */
    @Volatile
    private var featureEnabled = false

    /**
     * 是否仅对配置了专属规则的应用生效。
     *
     * 开启后，通配规则（包名为 `*`）不再参与匹配，
     * 只有明确列出包名的规则才会触发点击 —— 这是给敏感用户的收紧模式。
     */
    @Volatile
    private var enabledOnlyForConfiguredApps = false

    companion object {
        private const val TAG = "SkipAccessibility"

        /** 进入应用后的持续扫描时长：开屏广告通常在这段时间内出现。 */
        private const val SCAN_WINDOW_MS = 20_000L

        /** 两次扫描的最小间隔，节流用。 */
        private const val SCAN_THROTTLE_MS = 120L

        /** 节点树遍历上限，防止超深或超大节点树拖慢系统。 */
        private const val MAX_NODES = 2048

        /** 手势执行时长（毫秒）。 */
        private const val GESTURE_DURATION_MS = 100L

        @Volatile
        var instance: SkipAccessibilityService? = null
            private set

        /**
         * 当前处于前台的应用包名。
         *
         * 用于给 DNS 拦截记录做**按应用归因**。归因是近似的：
         * 后台应用自己发起的请求可能被记到当时在前台的应用头上。
         * 未授权无障碍时该值始终为 null，此时只统计域名维度。
         */
        @Volatile
        var foregroundPackage: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running.set(true)
        loadRules()
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        running.set(false)
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 系统解除绑定（被回收、被关闭、崩溃后）时清空实例，
        // 界面据此提示「已开启但未连接」，而不是误报为正常运行。
        running.set(false)
        instance = null
        Log.w(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // 无障碍服务被系统中断
    }

    /**
     * 加载规则与设置。
     */
    private fun loadRules() {
        scope.launch {
            // 这里会读 DataStore、读订阅缓存、解析 GKD 选择器，
            // 任何一步抛异常都不能让服务挂掉，因此整段兜底。
            runCatching {
                val repository = SettingsRepository(applicationContext)
                val settings = repository.settings.first()
                featureEnabled = settings.skipFeatureEnabled
                enabledOnlyForConfiguredApps = settings.skipOnlyConfiguredApps

                // 从仓库加载「全部启用源 + 自定义规则」。
                // 早期版本只读了内置规则，订阅规则根本没进引擎 —— 这是订阅无效的主因。
                val rules = com.skipad.data.skip.SkipRuleRepository(applicationContext)
                    .loadAll(settings.enabledSkipSourceIds, settings.customSkipRules)

                ruleIndex = SkipRuleIndex(rules)
                rules.forEach { clickGuard.setCooldown(it.id, it.cooldownMs) }
                Log.i(TAG, "规则已加载：${rules.size} 条")
            }.onFailure {
                // 规则加载失败时退回内置规则，保证基本可用的同时留下证据
                Log.e(TAG, "规则加载失败，退回内置规则", it)
                SkipCrashLog.record(applicationContext, "无障碍规则加载失败", it)
                ruleIndex = SkipRuleIndex(BuiltInSkipRules.rules)
            }
        }
    }

    /**
     * 重新加载规则（供界面调用）。
     */
    fun reloadRules() {
        loadRules()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 系统会把这里抛出的任何异常当作服务崩溃处理，
        // 因此整个回调必须兜底，不能只在「扫描段落」加 try。
        try {
            handleEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "无障碍事件处理异常", e)
            SkipCrashLog.record(applicationContext, "无障碍事件处理异常", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (!running.get() || !featureEnabled) return

        val eventType = event?.eventType
            ?: AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        // 收集所有可交互窗口的根节点。
        // 开屏广告常由广告 SDK 弹在独立窗口中，只看 rootInActiveWindow 会漏掉。
        val roots = collectRoots()
        if (roots.isEmpty()) return

        // 包名必须取「用户当前看到的应用」：
        // 窗口列表顺序不保证，首个窗口可能是状态栏或输入法，
        // 用它去查规则会得到错误的规则集（甚至只有通配规则）。
        val packageName = rootInActiveWindow?.packageName?.toString()
            ?: event?.packageName?.toString()
            ?: roots.firstNotNullOfOrNull { it.packageName?.toString() }
            ?: return
        val now = System.currentTimeMillis()

        // 切换应用时重置会话状态
        if (currentPackage != packageName) {
            currentPackage = packageName
            sessionStartedAt = now
            lastScanAt = 0L
            clickGuard.onPackageChanged(packageName)
        }

        // 记录 Activity 类名；切到新页面时重置扫描窗（用户可能刚点进另一个带开屏的页面）
        val activity = event?.className?.toString()
        if (!activity.isNullOrBlank() && activity != currentActivity) {
            currentActivity = activity
            sessionStartedAt = now
        }

        // 记录前台应用（供按应用归因使用）。
        // 桌面、系统界面与本应用自身不算「前台应用」，避免把后台流量记到它们头上。
        if (!isSystemSurface(packageName) && packageName != this.packageName) {
            foregroundPackage = packageName
        }

        // 本应用自己的界面、桌面与系统界面都不需要扫描：
        // 它们没有开屏广告，而每次扫描都要重建上千个节点快照，
        // 在自己界面里滑动时会持续产生大量临时对象（实测每 2~3 秒就有一次 20~33MB 的 GC）。
        if (packageName == this.packageName) return
        if (isSystemSurface(packageName)) return

        // 时间窗 + 节流：与事件数量解耦，避免事件风暴耗尽预算
        if (now - sessionStartedAt > SCAN_WINDOW_MS) return
        if (now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now

        try {
            val rules = ruleIndex.rulesFor(packageName)
            if (rules.isEmpty()) return

            // 收紧模式：仅允许明确配置了包名的规则触发，通配规则一律跳过
            val effectiveRules = if (enabledOnlyForConfiguredApps) {
                rules.filter { it.packageName == packageName }
            } else {
                rules
            }
            if (effectiveRules.isEmpty()) return

            // 只扫描属于当前应用的窗口：
            // 否则会用本应用的规则去匹配其他应用/系统窗口里的节点，导致误点。
            val ownRoots = roots.filter { root ->
                val pkg = root.packageName?.toString()
                pkg == null || pkg == packageName
            }
            if (ownRoots.isEmpty()) return

            val nodes = buildSnapshot(ownRoots)
            if (nodes.isEmpty()) return

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val matcher = SkipMatcher(screenWidth, screenHeight)

            val result = matcher.findMatch(nodes, effectiveRules)
            if (result == null) return

            // 冷却与去重判定
            val key = buildKey(packageName, result)
            if (!clickGuard.canClick(key, result.rule.id, System.currentTimeMillis())) return

            // 规则可能有延迟执行（如等待开屏动画）
            if (result.rule.delayMs > 0) {
                scope.launch {
                    kotlinx.coroutines.delay(result.rule.delayMs)
                    runCatching { performTap(result.tapX, result.tapY) }
                        .onFailure { Log.w(TAG, "延迟点击失败", it) }
                }
            } else {
                performTap(result.tapX, result.tapY)
            }

            clickGuard.markClicked(key, result.rule.id, System.currentTimeMillis())
            skipCount.incrementAndGet()
            Log.i(TAG, "已跳过：${result.rule.name} @ $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "处理无障碍事件失败", e)
        }
    }

    /**
     * 判断是否为桌面 / 系统界面等「不该自动点击」的场所。
     *
     * 这些界面没有开屏广告，但可能存在含「跳过」字样的控件或广告小组件，
     * 套用通用规则会造成误触。
     */
    private fun isSystemSurface(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("launcher") ||
            pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg == "com.android.settings" ||
            pkg.contains("permissioncontroller") ||
            pkg.contains("packageinstaller") ||
            pkg.startsWith("com.android.providers.")
    }

    /**
     * 收集需要扫描的窗口根节点。
     *
     * 开屏广告常由广告 SDK 弹在独立窗口中（甚至系统级弹窗），
     * 只看 `rootInActiveWindow` 会漏掉这类节点。
     * 需在无障碍配置中声明 `flagRetrieveInteractiveWindows`。
     */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            windows?.forEach { window ->
                if (window == null) return@forEach
                val type = window.type
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    return@forEach
                }
                window.root?.let { roots += it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取多窗口失败，回退到活动窗口", e)
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots += it }
        }
        return roots
    }

    /**
     * 遍历多个窗口的节点树构造快照。
     */
    private fun buildSnapshot(roots: List<AccessibilityNodeInfo>): List<SkipNode> {
        val nodes = ArrayList<SkipNode>(192)
        for (root in roots) {
            if (nodes.size >= MAX_NODES) break
            try {
                appendNode(root, nodes, depth = 0)
            } catch (e: Exception) {
                Log.e(TAG, "遍历节点树失败", e)
            }
        }
        return nodes
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        out: MutableList<SkipNode>,
        depth: Int,
    ) {
        if (out.size >= MAX_NODES) return

        out += SkipNode(
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            visibleToUser = node.isVisibleToUser,
            clickable = node.isClickable,
            bounds = Rect().also { node.getBoundsInScreen(it) }.let {
                com.skipad.core.skip.SkipRect(it.left, it.top, it.right, it.bottom)
            },
            depth = depth,
            childCount = node.childCount,
        )

        val childCount = node.childCount
        for (index in 0 until childCount) {
            if (out.size >= MAX_NODES) break
            val child = try {
                node.getChild(index)
            } catch (e: Exception) {
                null
            } ?: continue
            appendNode(child, out, depth + 1)
        }
    }

    /**
     * 构造去重键：包名 + 节点标识 + 点击坐标。
     *
     * 刻意**不包含规则 id**：同一个物理按钮无论被哪条规则命中，
     * 都只应点击一次，否则可能出现连续点击同一位置的误触。
     */
    private fun buildKey(packageName: String, result: com.skipad.core.skip.SkipMatchResult): String {
        val node = result.node
        val identity = node.resourceId?.takeIf { it.isNotBlank() }
            ?: node.text?.takeIf { it.isNotBlank() }
            ?: node.className.orEmpty()
        return "$packageName|$identity|${result.tapX},${result.tapY}"
    }

    /**
     * 通过手势执行点击。
     */
    private fun performTap(x: Int, y: Int) {
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS)
                )
                .build()

            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "点击完成 @ $x,$y")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.w(TAG, "手势被取消 @ $x,$y")
                    }
                },
                null,
            )
            // dispatchGesture 返回 false 说明服务没有「执行手势」能力，
            // 通常是无障碍配置缺少 canPerformGestures —— 此时不会发生任何点击
            if (!accepted) {
                Log.e(TAG, "手势被系统拒绝 @ $x,$y：请检查无障碍配置是否声明 canPerformGestures")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行手势失败", e)
        }
    }

    /**
     * 当前累计跳过的次数。
     */
    fun skippedCount(): Long = skipCount.get()

    /**
     * 打开「最近任务」界面。
     *
     * 系统没有公开的 recents Intent，只能借无障碍的全局动作。
     * 用于引导用户长按本应用卡片、选择「锁定」，避免被一键清理杀掉。
     *
     * @return 是否成功发起
     */
    fun openRecents(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    } catch (e: Exception) {
        Log.w(TAG, "打开最近任务失败", e)
        false
    }
}

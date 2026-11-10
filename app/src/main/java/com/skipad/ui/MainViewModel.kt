package com.skipad.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skipad.data.filter.FilterManager
import com.skipad.data.filter.FilterSource
import com.skipad.data.log.BlockLogStore
import com.skipad.data.log.BlockedEntry
import com.skipad.data.prefs.AppSettings
import com.skipad.data.prefs.BlockMode
import com.skipad.data.prefs.SettingsRepository
import com.skipad.data.prefs.UpstreamDns
import com.skipad.service.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面状态。
 */
data class DashboardState(
    val protectionEnabled: Boolean = false,
    val todayBlocked: Long = 0,
    val totalBlocked: Long = 0,
    val uniqueDomains: Long = 0,
    val ruleCount: Int = 0,
    /** 本次运行收到的 DNS 查询数，用于确认隧道确实在工作。 */
    val receivedQueries: Long = 0,
    /**
     * VPN 服务是否**真正在运行**。
     *
     * 与 [protectionEnabled] 不同：设置项只表示用户意图，
     * 服务可能因进程被杀、系统回收而未实际启动。
     * 界面必须如实反映真实状态，否则用户会误以为已在保护中。
     */
    val serviceRunning: Boolean = false,
    val loadedFilterCount: Int = 0,
    val topDomains: List<BlockedEntry> = emptyList(),
    val isUpdating: Boolean = false,
    val updateMessage: String? = null,
)

/**
 * 日志界面状态。
 */
data class LogState(
    val entries: List<BlockedEntry> = emptyList(),
    val isLoading: Boolean = false,
    /** 按应用归因的拦截排行（近似：按拦截时的前台应用统计）。 */
    val appStats: List<com.skipad.data.log.AppBlockStat> = emptyList(),
)

/**
 * 主 ViewModel。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val filterManager = FilterManager(application)
    private val logStore = BlockLogStore(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _settingsLoaded = MutableStateFlow(false)

    /**
     * 设置是否已从磁盘真实读出**至少一次**。
     *
     * [settings] 的初始值是 `AppSettings()` 默认实例，其中 `onboardingDone = false`。
     * 界面若直接据此判断，首帧会误判为「首次安装」而闪一下引导页。
     * 凡是要依据持久化状态做分支的地方（引导页、保护开关）都必须先等这个标记为 true。
     */
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard: StateFlow<DashboardState> = _dashboard.asStateFlow()

    private val _logs = MutableStateFlow(LogState())
    val logs: StateFlow<LogState> = _logs.asStateFlow()

    init {
        viewModelScope.launch {
            // 设置读取失败不应中断协程导致界面无响应，降级为默认设置
            runCatching {
                settingsRepository.settings.collect { current ->
                    _dashboard.value = _dashboard.value.copy(
                        protectionEnabled = current.protectionEnabled,
                    )
                    // 只有直接从仓库收到首帧才算「读到了真实值」
                    _settingsLoaded.value = true
                }
            }.onFailure {
                Log.w("SkipAd", "读取设置失败", it)
                // 读不到设置时也要放行界面，否则会一直停在空白页；
                // 此时用默认值（保护关闭、未完成引导）是安全的降级。
                _settingsLoaded.value = true
            }
        }
        refreshStats()
        ensureRuleCountLoaded()
    }

    /**
     * 确保规则总数可用。
     *
     * 场景：用户此前已下载过规则文件，但升级到新版本后持久化字段尚为空值，
     * 此时界面会错误地显示「尚未加载规则」。这里从本地缓存重算一次并写回。
     *
     * 仅在持久化值为 0 时执行，避免每次启动都解析数十万条规则。
     *
     * 注意：必须从仓库读取设置，不能读 [settings] 的当前值 ——
     * StateFlow 的初始值是 `AppSettings()` 默认实例，其 enabledFilterIds 为空集，
     * 直接使用会导致一条规则都不加载。
     */
    private fun ensureRuleCountLoaded() {
        viewModelScope.launch {
            val current = runCatching { settingsRepository.settings.first() }.getOrNull()
                ?: return@launch
            if (current.loadedRuleCount > 0) return@launch

            val cachedCount = withContext(Dispatchers.IO) {
                runCatching {
                    filterManager.loadRules(current.enabledFilterIds).size
                }.getOrElse { 0 }
            }
            if (cachedCount > 0) {
                settingsRepository.setLoadedRuleCount(cachedCount)
                _dashboard.value = _dashboard.value.copy(ruleCount = cachedCount)
            }
        }
    }

    /**
     * 刷新统计数据。
     *
     * 统计属于辅助信息，任何存储层异常都**不应导致应用崩溃**。
     * 这里对读取过程做兜底：失败时保留原有统计值，仅记录日志。
     */
    fun refreshStats() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val service = AdBlockVpnService.instance
                try {
                    SessionSnapshot(
                        today = logStore.todayBlocked(),
                        total = logStore.totalBlocked(),
                        unique = logStore.uniqueDomains(),
                        top = logStore.topDomains(10),
                        ruleCount = resolveRuleCount(service),
                        receivedQueries = service?.statsSnapshot()?.received ?: 0,
                        serviceRunning = service?.isActive() == true,
                    )
                } catch (e: Exception) {
                    // 数据库不可用不应影响主功能，降级为空统计
                    Log.w("SkipAd", "读取拦截统计失败", e)
                    null
                }
            } ?: return@launch

            _dashboard.value = _dashboard.value.copy(
                todayBlocked = snapshot.today,
                totalBlocked = snapshot.total,
                uniqueDomains = snapshot.unique,
                topDomains = snapshot.top,
                ruleCount = snapshot.ruleCount,
                receivedQueries = snapshot.receivedQueries,
                serviceRunning = snapshot.serviceRunning,
            )
        }
    }

    /**
     * 解析当前规则总数。
     *
     * 规则数量是「已装载规则集」的属性，与应用是否运行无关：
     * - VPN 服务在运行且有规则时，以服务内实际装载量为准
     * - 否则回退到持久化保存的数值
     *
     * 若只用服务值，VPN 未启动时会错误地把规则数显示为 0。
     */
    private suspend fun resolveRuleCount(service: AdBlockVpnService?): Int {
        val fromService = service?.statsSnapshot()?.ruleCount ?: 0
        if (fromService > 0) return fromService
        return settingsRepository.settings.first().loadedRuleCount
    }

    private data class SessionSnapshot(
        val today: Long,
        val total: Long,
        val unique: Long,
        val top: List<BlockedEntry>,
        val ruleCount: Int,
        val receivedQueries: Long,
        val serviceRunning: Boolean,
    )

    /**
     * 加载拦截日志。
     *
     * 读取失败时降级为空列表，不影响其他页面。
     */
    fun loadLogs() {
        viewModelScope.launch {
            _logs.value = _logs.value.copy(isLoading = true)
            val (entries, appStats) = withContext(Dispatchers.IO) {
                try {
                    logStore.recent(300) to logStore.topApps(10)
                } catch (e: Exception) {
                    Log.w("SkipAd", "读取拦截日志失败", e)
                    emptyList<BlockedEntry>() to emptyList<com.skipad.data.log.AppBlockStat>()
                }
            }
            _logs.value = LogState(entries = entries, isLoading = false, appStats = appStats)
        }
    }

    /**
     * 清空日志。
     */
    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { logStore.clear() }
                    .onFailure { Log.w("SkipAd", "清空拦截日志失败", it) }
            }
            loadLogs()
            refreshStats()
        }
    }

    /**
     * 更新全部启用的规则源。
     *
     * 启用的规则源从仓库读取，而非 [settings] 的当前值 ——
     * StateFlow 未收到首帧时其 enabledFilterIds 为空集，会导致不下载任何源。
     */
    fun updateFilters() {
        viewModelScope.launch {
            _dashboard.value = _dashboard.value.copy(isUpdating = true, updateMessage = null)

            val currentSettings = settingsRepository.settings.first()
            val enabledIds = currentSettings.enabledFilterIds
            val sources = enabledIds.mapNotNull { FilterSource.fromId(it) }
            val results = filterManager.fetchAll(sources)

            val successCount = results.count { it is com.skipad.data.filter.FetchResult.Success }
            val failed = results.filterIsInstance<com.skipad.data.filter.FetchResult.Failure>()

            // 重新装载规则
            val rules = filterManager.loadRules(enabledIds)
            val engine = com.skipad.core.blocklist.BlocklistEngine()
            engine.replaceRules(rules, currentSettings.allowList)

            // 持久化规则数，保证 VPN 未启动时界面也能正确显示
            settingsRepository.setLoadedRuleCount(rules.size)

            _dashboard.value = _dashboard.value.copy(
                isUpdating = false,
                ruleCount = rules.size,
                updateMessage = if (failed.isEmpty()) {
                    "已更新 $successCount 个规则源，共 ${rules.size} 条规则"
                } else {
                    "成功 $successCount 个，失败 ${failed.size} 个（${failed.first().source.displayName}）"
                },
            )
            refreshStats()
        }
    }

    /**
     * 切换某个规则源的启用状态。
     */
    fun toggleFilter(source: FilterSource, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.toggleFilter(source.id, enabled)
            val rules = filterManager.loadRules(settingsRepository.settings.first().enabledFilterIds)
            settingsRepository.setLoadedRuleCount(rules.size)
            _dashboard.value = _dashboard.value.copy(ruleCount = rules.size)
        }
    }

    suspend fun firstSettings(): AppSettings = settingsRepository.settings.first()

    /**
     * 读取「是否已开启保护」的持久化值。
     *
     * 必须从仓库读取：`settings` 这个 StateFlow 的初始值是 `AppSettings()` 默认实例，
     * 其 protectionEnabled 为 false，直接读会得到错误的「未开启」。
     */
    suspend fun isProtectionEnabled(): Boolean =
        runCatching { settingsRepository.settings.first().protectionEnabled }.getOrDefault(false)

    /**
     * 添加白名单域名。
     */
    fun addToAllowList(domain: String) {
        viewModelScope.launch {
            settingsRepository.addToAllowList(domain.trim().lowercase())
        }
    }

    /**
     * 移除白名单域名。
     */
    fun removeFromAllowList(domain: String) {
        viewModelScope.launch {
            settingsRepository.removeFromAllowList(domain)
            // 白名单变化要立刻生效，否则要等到下次重启才不拦
            com.skipad.service.AdBlockVpnService.instance?.reloadEngine()
        }
    }

    /**
     * 添加自定义拦截域名。
     *
     * 添加后不是简单提示「已添加」，而是**回读确认**：
     * 等规则索引替换完成后，用运行中的引擎实际查一次这个域名，
     * 告诉用户它到底有没有开始生效。
     */
    fun addCustomBlockDomain(domain: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            val normalized = domain.trim().lowercase()
            if (normalized.isEmpty()) {
                onResult("域名不能为空")
                return@launch
            }
            settingsRepository.addCustomBlockDomain(normalized)
            com.skipad.service.AdBlockVpnService.instance?.reloadEngine()

            // 热替换索引是异步的，稍等一下再回读
            kotlinx.coroutines.delay(1200)
            val effect = com.skipad.service.AdBlockVpnService.instance?.matchDomain(normalized)
            refreshCustomDomainEffect()
            onResult(
                when (effect) {
                    true -> "已添加 $normalized，并已确认生效"
                    false -> "已添加 $normalized，但当前未生效（保护未开启或规则尚未加载）"
                    null -> "已添加 $normalized，开启保护后可确认是否生效"
                }
            )
        }
    }

    /**
     * 回读所有自定义拦截域名的生效状态。
     */
    fun refreshCustomDomainEffect() {
        viewModelScope.launch {
            val domains = settingsRepository.settings.first().customBlockDomains
            if (domains.isEmpty()) {
                _customDomainEffect.value = emptyMap()
                return@launch
            }
            val service = com.skipad.service.AdBlockVpnService.instance
            _customDomainEffect.value = domains.associateWith { domain ->
                service?.matchDomain(domain)
            }
        }
    }

    /**
     * 移除自定义拦截域名。
     */
    fun removeCustomBlockDomain(domain: String) {
        viewModelScope.launch {
            settingsRepository.removeCustomBlockDomain(domain)
            com.skipad.service.AdBlockVpnService.instance?.reloadEngine()
        }
    }

    fun setUpstreamDns(address: String) {
        viewModelScope.launch { settingsRepository.setUpstreamDns(address) }
    }

    fun setBlockMode(mode: BlockMode) {
        viewModelScope.launch { settingsRepository.setBlockMode(mode) }
    }

    fun setBootStart(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBootStart(enabled) }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoUpdate(enabled) }
    }

    /**
     * 切换静默通知。
     *
     * 注意：Android 强制要求前台服务必须有一条通知，无法真正隐藏，
     * 静默只是「不显示时间、不响铃」。
     */
    fun setQuietNotification(quiet: Boolean) {
        viewModelScope.launch { settingsRepository.setQuietNotification(quiet) }
    }

    fun setExcludedPackages(packages: Set<String>) {
        viewModelScope.launch { settingsRepository.setExcludedPackages(packages) }
    }

    /**
     * 尝试恢复无障碍服务。
     *
     * 背景（真机实测）：无障碍服务所在进程一旦被杀，系统会把它记成
     * `Crashed services` 并**不再自动重新绑定** —— 重新打开应用也没用，
     * 只能由用户在系统设置里重新授权一次。这是 Android 的设计，应用绕不过去。
     *
     * 但并非所有「未连接」都是 crashed：进程还活着、只是绑定尚未完成时可以自愈。
     * 因此这里先做一次温和的自愈尝试：触发保活自检 → 拉起进程与服务 → 复查状态。
     *
     * @param onResult true 表示已恢复；false 表示需要用户手动重新授权
     */
    fun recoverAccessibility(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // 触发一次保活自检：确保进程与前台服务都在运行
            runCatching {
                // 用**显式 Intent**直接指向接收器：Android 8 之后的隐式广播限制
                // 会让后台广播收不到，显式广播没有这个问题。
                app.sendBroadcast(
                    Intent(app, com.skipad.service.KeepAliveReceiver::class.java)
                        .setAction(com.skipad.service.KeepAliveScheduler.ACTION_WATCHDOG)
                )
            }.onFailure { Log.w("SkipAd", "触发保活自检失败", it) }

            // 给系统一点时间完成绑定
            var recovered = false
            for (attempt in 0 until 6) {
                kotlinx.coroutines.delay(700)
                if (com.skipad.service.SkipAccessibilityService.instance != null) {
                    recovered = true
                    break
                }
            }
            onResult(recovered)
        }
    }

    /**
     * 自定义拦截域名的生效状态。
     *
     * 值为 null 表示**无法判定**（保护未开启，服务里没有引擎）。
     */
    private val _customDomainEffect = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val customDomainEffect: StateFlow<Map<String, Boolean?>> = _customDomainEffect.asStateFlow()

    /**
     * 域名查询结果。
     *
     * @param blocked 是否会被拦截
     * @param allowed 是否被白名单放行
     * @param sources 命中了哪些规则源（为空表示来自自定义域名或未被任何源收录）
     * @param live 是否用的是运行中隧道的引擎（即时结果）
     */
    data class DomainQuery(
        val domain: String,
        val blocked: Boolean,
        val allowed: Boolean,
        val sources: List<String>,
        val live: Boolean,
    )

    /**
     * 查询某域名是否会被拦截。
     *
     * 优先使用运行中隧道的引擎（毫秒级）；隧道未启动时在后台临时构建一份索引，
     * 大约需要一两秒。来源追查走缓存文本的子串匹配，比重新解析快得多。
     */
    fun queryDomain(input: String, onResult: (DomainQuery?) -> Unit) {
        viewModelScope.launch {
            val domain = com.skipad.core.blocklist.RuleParser.normalizeDomain(input)
            if (domain == null) {
                onResult(null)
                return@launch
            }
            val settings = settingsRepository.settings.first()

            // 白名单优先：命中父域也算放行
            val allowed = settings.allowList.any { entry ->
                domain == entry || domain.endsWith(".$entry")
            }

            val live = com.skipad.service.AdBlockVpnService.instance?.matchDomain(domain)
            val blocked = live ?: withContext(Dispatchers.IO) {
                runCatching {
                    val rules = filterManager.loadRules(
                        settings.enabledFilterIds,
                        settings.customBlockDomains,
                    )
                    val engine = com.skipad.core.blocklist.BlocklistEngine()
                    engine.replaceRules(rules, settings.allowList)
                    engine.isBlocked(domain)
                }.getOrDefault(false)
            }

            val sources = if (blocked) {
                filterManager.findSourcesContaining(domain, settings.enabledFilterIds)
            } else {
                emptyList()
            }

            onResult(DomainQuery(domain, blocked, allowed, sources, live != null))
        }
    }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setProtectionEnabled(enabled) }
    }

    // ---- 应用级跳过（无障碍引擎）----

    /**
     * 更新应用级规则订阅。
     *
     * @param onResult 更新结果的回调，在主线程执行
     */
    fun updateSkipSubscription(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            val repository = com.skipad.data.skip.SkipRuleRepository(getApplication())

            val sources = com.skipad.data.skip.SkipSource.downloadable
                .filter { it.id in current.enabledSkipSourceIds }

            if (sources.isEmpty()) {
                onResult("请先启用至少一个规则源")
                return@launch
            }

            val failures = ArrayList<String>()
            var success = 0

            sources.forEach { source ->
                val result = repository.update(source)
                if (result.isSuccess) {
                    success++
                } else {
                    failures += source.displayName
                    Log.w("SkipAdSkip", "规则源 ${source.id} 更新失败", result.exceptionOrNull())
                }
            }

            val total = repository
                .loadAll(current.enabledSkipSourceIds, current.customSkipRules)
                .size

            val message = if (failures.isEmpty()) {
                "已更新 $success 个规则源，共 $total 条规则"
            } else {
                "成功 $success 个，失败 ${failures.size} 个（${failures.first()}）"
            }
            onResult(message)
            refreshSkipSources()

            // 规则变化后让无障碍服务重新装载
            com.skipad.service.SkipAccessibilityService.instance?.reloadRules()
        }
    }

    /**
     * 规则源列表与本地缓存状态，供界面展示。
     */
    private val _skipSources = MutableStateFlow<List<SkipSourceUi>>(emptyList())
    val skipSources: StateFlow<List<SkipSourceUi>> = _skipSources.asStateFlow()

    data class SkipSourceUi(
        val source: com.skipad.data.skip.SkipSource,
        val enabled: Boolean,
        val ruleCount: Int,
        val skipped: Int,
        val updatedAt: Long,
        val cached: Boolean,
    )

    /**
     * 刷新规则源状态。
     */
    fun refreshSkipSources() {
        viewModelScope.launch {
            val current = runCatching { settingsRepository.settings.first() }.getOrNull()
                ?: return@launch
            val repository = com.skipad.data.skip.SkipRuleRepository(getApplication())

            val items = withContext(Dispatchers.IO) {
                com.skipad.data.skip.SkipSource.entries.map { source ->
                    // 统计失败按「无缓存」渲染，不能让一次解析异常崩掉界面
                    val stats = runCatching { repository.stats(source) }
                        .getOrElse {
                            com.skipad.data.skip.SkipRuleRepository.SourceStats(0, 0, 0L, false)
                        }
                    SkipSourceUi(
                        source = source,
                        enabled = source.id in current.enabledSkipSourceIds,
                        ruleCount = stats.ruleCount,
                        skipped = stats.skipped,
                        updatedAt = stats.updatedAt,
                        cached = stats.cached,
                    )
                }
            }
            _skipSources.value = items
        }
    }

    /**
     * 开启/关闭某个规则源。
     */
    fun toggleSkipSource(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.toggleSkipSource(sourceId, enabled)
            refreshSkipSources()
            com.skipad.service.SkipAccessibilityService.instance?.reloadRules()
        }
    }

    /**
     * 已适配的应用名称（来自内置规则中的显式包名规则）。
     */
    fun adaptedAppNames(): List<String> = com.skipad.core.skip.BuiltInSkipRules.rules
        .filter { it.packageName != "*" && it.id.startsWith("splash_") }
        .map { it.name.substringBefore(" 开屏") }
        .distinct()

    /**
     * 结束首次引导。
     */
    fun setOnboardingDone() {
        viewModelScope.launch { settingsRepository.setOnboardingDone(true) }
    }

    /**
     * 重新打开首次引导（供设置页「查看使用引导」使用）。
     */
    fun restartOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingDone(false) }
    }

    fun setSkipFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSkipFeatureEnabled(enabled)
            com.skipad.service.SkipAccessibilityService.instance?.reloadRules()
        }
    }

    fun setSkipOnlyConfiguredApps(onlyConfigured: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSkipOnlyConfiguredApps(onlyConfigured)
            com.skipad.service.SkipAccessibilityService.instance?.reloadRules()
        }
    }

    fun setSkipSubscriptionUrl(url: String) {
        viewModelScope.launch { settingsRepository.setSkipSubscriptionUrl(url) }
    }
}

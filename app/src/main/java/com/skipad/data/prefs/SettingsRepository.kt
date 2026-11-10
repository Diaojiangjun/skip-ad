package com.skipad.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.skipad.data.skip.SkipSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用设置。
 */
data class AppSettings(
    val protectionEnabled: Boolean = false,
    val bootStartEnabled: Boolean = true,
    val autoUpdateEnabled: Boolean = true,
    val enabledFilterIds: Set<String> = emptySet(),
    val allowList: Set<String> = emptySet(),
    val excludedPackages: Set<String> = emptySet(),
    val upstreamDns: String = UpstreamDns.ALIYUN,
    val blockMode: BlockMode = BlockMode.BLOCK,
    /**
     * 是否使用静默通知。
     *
     * Android 强制要求前台服务必须有一条通知，无法真正隐藏；
     * 静默模式使用最低重要度渠道，不显示时间、不响铃。
     */
    val quietNotification: Boolean = false,
    /** 无障碍引擎是否启用。 */
    val skipFeatureEnabled: Boolean = true,
    /** 无障碍引擎是否仅对配置了规则的应用生效。 */
    val skipOnlyConfiguredApps: Boolean = false,
    /** 用户自定义的拦截域名（DNS 引擎）。 */
    val customBlockDomains: Set<String> = emptySet(),
    /** 用户自定义的应用级规则（JSON 字符串）。 */
    val customSkipRules: Set<String> = emptySet(),
    /** 无障碍规则的远程订阅地址。 */
    val skipSubscriptionUrl: String = "",
    /** 是否启用远程订阅。 */
    val skipSubscriptionEnabled: Boolean = false,
    /** 是否已完成首次使用引导。 */
    val onboardingDone: Boolean = false,
    /**
     * 启用的内置规则源 id 集合。
     *
     * 默认启用「内置精选 + GKD 官方 + AIsouler 订阅」。
     */
    val enabledSkipSourceIds: Set<String> = SkipSourceDefaults.ids,
    /**
     * 已加载的规则总数。
     *
     * 规则数量属于「已装载规则集」的属性，与应用是否正在运行无关，
     * 因此持久化保存 —— 否则 VPN 未启动时界面会错误地显示 0 条。
     */
    val loadedRuleCount: Int = 0,
) {
    /**
     * 是否启用了自动更新。
     */
    val shouldAutoUpdate: Boolean get() = autoUpdateEnabled
}

/**
 * 拦截行为模式。
 */
enum class BlockMode(val label: String, val description: String) {
    /** 返回 0.0.0.0 / ::，让连接快速失败。 */
    BLOCK("拦截", "将广告域名解析到空地址，连接立即失败"),

    /** 返回 NXDOMAIN，明确告知域名不存在。 */
    NXDOMAIN("域名不存在", "返回域名不存在，部分应用会更快放弃请求"),

    /** 放行但记录，用于观察。 */
    MONITOR("仅记录", "不实际拦截，仅记录命中的广告域名以便观察效果"),
}

/**
 * 上游 DNS 提供方。
 */
object UpstreamDns {
    const val ALIYUN = "223.5.5.5"
    const val DNSPOD = "119.29.29.29"
    const val CLOUDFLARE = "1.1.1.1"
    const val GOOGLE = "8.8.8.8"
    const val QUAD9 = "9.9.9.9"

    /** 阿里 DNS 的 DoH 端点。 */
    const val ALIYUN_DOH = "https://dns.alidns.com/dns-query"

    /** 腾讯 DNSPod 的 DoH 端点。 */
    const val DNSPOD_DOH = "https://doh.pub/dns-query"

    /** Cloudflare 的 DoH 端点。 */
    const val CLOUDFLARE_DOH = "https://cloudflare-dns.com/dns-query"

    val presets: List<Pair<String, String>> = listOf(
        ALIYUN to "阿里 DNS（国内推荐）",
        DNSPOD to "腾讯 DNSPod（国内推荐）",
        CLOUDFLARE to "Cloudflare 1.1.1.1",
        GOOGLE to "Google 8.8.8.8",
        QUAD9 to "Quad9 9.9.9.9（安全过滤）",
        ALIYUN_DOH to "阿里 DNS（加密 DoH）",
        DNSPOD_DOH to "腾讯 DNSPod（加密 DoH）",
        CLOUDFLARE_DOH to "Cloudflare（加密 DoH）",
    )
}

/**
 * 设置存储。
 *
 * 必须带 [ReplaceFileCorruptionHandler]：配置文件一旦写坏（进程在写入途中被杀、
 * 磁盘异常），DataStore 会对**每一次读取**都抛 `CorruptionException`。
 * 没有兜底时表现为「应用一打开就崩」「无障碍服务反复被系统标记为无法运行」，
 * 而且用户无论重启多少次都好不了 —— 只能清除数据。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "skipad_settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        emptyPreferences()
    },
)

/**
 * 设置的持久化仓库。
 */
class SettingsRepository(private val context: Context) {

    internal object Keys {
        val protectionEnabled = booleanPreferencesKey("protection_enabled")
        val bootStart = booleanPreferencesKey("boot_start")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val enabledFilters = stringSetPreferencesKey("enabled_filters")
        val allowList = stringSetPreferencesKey("allow_list")
        val excludedPackages = stringSetPreferencesKey("excluded_packages")
        val upstreamDns = stringPreferencesKey("upstream_dns")
        val blockMode = stringPreferencesKey("block_mode")
        val quietNotification = booleanPreferencesKey("quiet_notification")
        val skipFeatureEnabled = booleanPreferencesKey("skip_feature_enabled")
        val skipOnlyConfiguredApps = booleanPreferencesKey("skip_only_configured_apps")
        val customSkipRules = stringSetPreferencesKey("custom_skip_rules")
        val customBlockDomains = stringSetPreferencesKey("custom_block_domains")
        val skipSubscriptionUrl = stringPreferencesKey("skip_subscription_url")
        val enabledSkipSourceIds = stringSetPreferencesKey("enabled_skip_sources")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val skipSubscriptionEnabled = booleanPreferencesKey("skip_subscription_enabled")
        val loadedRuleCount = intPreferencesKey("loaded_rule_count")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { readSettings(it) }

    suspend fun setProtectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.protectionEnabled] = enabled }
    }

    suspend fun setBootStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.bootStart] = enabled }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoUpdate] = enabled }
    }

    suspend fun setEnabledFilters(ids: Set<String>) {
        context.dataStore.edit { it[Keys.enabledFilters] = ids }
    }

    suspend fun toggleFilter(id: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.enabledFilters] ?: FilterSourceDefaults.ids
            prefs[Keys.enabledFilters] = if (enabled) current + id else current - id
        }
    }

    suspend fun setAllowList(domains: Set<String>) {
        context.dataStore.edit { it[Keys.allowList] = domains }
    }

    suspend fun addToAllowList(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.allowList] ?: emptySet()
            prefs[Keys.allowList] = current + domain
        }
    }

    suspend fun removeFromAllowList(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.allowList] ?: emptySet()
            prefs[Keys.allowList] = current - domain
        }
    }

    suspend fun setExcludedPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.excludedPackages] = packages }
    }

    suspend fun setUpstreamDns(address: String) {
        context.dataStore.edit { it[Keys.upstreamDns] = address }
    }

    suspend fun setBlockMode(mode: BlockMode) {
        context.dataStore.edit { it[Keys.blockMode] = mode.name }
    }

    suspend fun setQuietNotification(quiet: Boolean) {
        context.dataStore.edit { it[Keys.quietNotification] = quiet }
    }

    suspend fun setSkipFeatureEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.skipFeatureEnabled] = enabled }
    }

    suspend fun setSkipOnlyConfiguredApps(onlyConfigured: Boolean) {
        context.dataStore.edit { it[Keys.skipOnlyConfiguredApps] = onlyConfigured }
    }

    /** 添加一个自定义拦截域名。 */
    suspend fun addCustomBlockDomain(domain: String) {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.customBlockDomains] ?: emptySet()
            prefs[Keys.customBlockDomains] = current + normalized
        }
    }

    /** 移除一个自定义拦截域名。 */
    suspend fun removeCustomBlockDomain(domain: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.customBlockDomains] ?: emptySet()
            prefs[Keys.customBlockDomains] = current - domain
        }
    }

    suspend fun addCustomSkipRule(json: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.customSkipRules] ?: emptySet()
            prefs[Keys.customSkipRules] = current + json
        }
    }

    suspend fun removeCustomSkipRule(json: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.customSkipRules] ?: emptySet()
            prefs[Keys.customSkipRules] = current - json
        }
    }

    suspend fun setSkipSubscriptionUrl(url: String) {
        context.dataStore.edit { it[Keys.skipSubscriptionUrl] = url }
    }

    /**
     * 标记首次引导已完成。
     */
    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.onboardingDone] = done }
    }

    /**
     * 切换某个规则源的启用状态。
     */
    suspend fun toggleSkipSource(sourceId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.enabledSkipSourceIds] ?: SkipSourceDefaults.ids
            prefs[Keys.enabledSkipSourceIds] =
                if (enabled) current + sourceId else current - sourceId
        }
    }

    suspend fun setSkipSubscriptionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.skipSubscriptionEnabled] = enabled }
    }

    /**
     * 记录已加载的规则总数。
     */
    suspend fun setLoadedRuleCount(count: Int) {
        context.dataStore.edit { it[Keys.loadedRuleCount] = count }
    }
}

/**
 * 默认启用的规则源 ID 集合。
 */
/**
 * 默认启用的规则源。
 */
object SkipSourceDefaults {
    val ids: Set<String> = SkipSource.entries.filter { it.enabledByDefault }.map { it.id }.toSet()
}

object FilterSourceDefaults {
    val ids: Set<String> = setOf("adaway", "steven_black", "android_ad")

}

/**
 * 把持久化的偏好读成 [AppSettings]。
 *
 * 抽成独立函数是为了能在不启动 Android 的前提下单测默认值与容错：
 * DataStore 的 *模型*（[Preferences]）是纯 JVM 实现，测试里可以直接构造。
 *
 * 关键约定：**任何读取失败都必须退回默认值而不是抛异常**。
 * 历史上正因为这里没有兜底，配置文件写坏后每次读取都抛异常，
 * 应用反复崩溃、无障碍服务被系统判为「无法运行」。
 */
internal fun readSettings(prefs: androidx.datastore.preferences.core.Preferences): AppSettings =
    AppSettings(
        protectionEnabled = prefs[SettingsRepository.Keys.protectionEnabled] ?: false,
        bootStartEnabled = prefs[SettingsRepository.Keys.bootStart] ?: true,
        autoUpdateEnabled = prefs[SettingsRepository.Keys.autoUpdate] ?: true,
        enabledFilterIds = prefs[SettingsRepository.Keys.enabledFilters] ?: FilterSourceDefaults.ids,
        allowList = prefs[SettingsRepository.Keys.allowList] ?: emptySet(),
        excludedPackages = prefs[SettingsRepository.Keys.excludedPackages] ?: emptySet(),
        upstreamDns = prefs[SettingsRepository.Keys.upstreamDns] ?: UpstreamDns.ALIYUN,
        blockMode = prefs[SettingsRepository.Keys.blockMode]?.let { name ->
            BlockMode.entries.firstOrNull { it.name == name }
        } ?: BlockMode.BLOCK,
        quietNotification = prefs[SettingsRepository.Keys.quietNotification] ?: false,
        skipFeatureEnabled = prefs[SettingsRepository.Keys.skipFeatureEnabled] ?: true,
        skipOnlyConfiguredApps = prefs[SettingsRepository.Keys.skipOnlyConfiguredApps] ?: false,
        customSkipRules = prefs[SettingsRepository.Keys.customSkipRules] ?: emptySet(),
        skipSubscriptionUrl = prefs[SettingsRepository.Keys.skipSubscriptionUrl] ?: "",
        enabledSkipSourceIds = prefs[SettingsRepository.Keys.enabledSkipSourceIds] ?: SkipSourceDefaults.ids,
        onboardingDone = prefs[SettingsRepository.Keys.onboardingDone] ?: false,
        skipSubscriptionEnabled = prefs[SettingsRepository.Keys.skipSubscriptionEnabled] ?: false,
        loadedRuleCount = prefs[SettingsRepository.Keys.loadedRuleCount] ?: 0,
    )

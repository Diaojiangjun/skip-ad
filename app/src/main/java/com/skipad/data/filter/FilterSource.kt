package com.skipad.data.filter

/**
 * 规则源定义。
 *
 * 内置源均选自维护活跃、格式规范的公开项目，并优先选择国内可直连的镜像以提升可用性。
 *
 * 每个源可配置多个 [urls] 备选地址：按顺序尝试，第一个成功的即被采用。
 * 这能显著提升在受限网络下的可用性 —— 某个镜像不可达时自动回退到下一个，
 * 而不会让整个更新流程卡在超时上。
 */
enum class FilterSource(
    val id: String,
    val displayName: String,
    val urls: List<String>,
    val description: String,
    val enabledByDefault: Boolean,
) {
    ADAWAY(
        id = "adaway",
        displayName = "AdAway 默认列表",
        urls = listOf(
            "https://adaway.org/hosts.txt",
        ),
        description = "AdAway 官方维护的默认广告与追踪域名列表，稳定性最好",
        enabledByDefault = true,
    ),

    STEVEN_BLACK(
        id = "steven_black",
        displayName = "StevenBlack 统一版",
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
            "https://fastly.jsdelivr.net/gh/StevenBlack/hosts@master/hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
        description = "合并多个来源的扩展列表，覆盖面广，条目约 12 万条",
        enabledByDefault = true,
    ),

    ADBLOCK_PROTECTIVE(
        id = "adblock_protective",
        displayName = "AdGuard 保护性规则",
        urls = listOf(
            "https://filters.adtidy.org/extension/safari/filters/2.txt",
        ),
        description = "AdGuard 团队维护的基础过滤规则，兼顾广告与追踪",
        enabledByDefault = false,
    ),

    /**
     * 面向 Android 应用的广告域名列表。
     *
     * 该列表专门收录国内 App 的广告与追踪域名，与通用 hosts 列表互补。
     */
    ANDROID_AD_HOSTS(
        id = "android_ad",
        displayName = "Android 应用广告域名",
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/DataMaster-2501/DataMaster-Android-AdBlock-Hosts@master/hosts",
            "https://fastly.jsdelivr.net/gh/DataMaster-2501/DataMaster-Android-AdBlock-Hosts@master/hosts",
            "https://raw.githubusercontent.com/DataMaster-2501/DataMaster-Android-AdBlock-Hosts/master/hosts",
        ),
        description = "专门收录国内 Android 应用的广告与追踪域名，与通用列表互补",
        enabledByDefault = true,
    ),

    DANGLING_DNS(
        id = "dangling_dns",
        displayName = "DNS 悬空域名拦截",
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/ineffective/dangling-dns@master/dangling-domains-ag.txt",
            "https://raw.githubusercontent.com/ineffective/dangling-dns/master/dangling-domains-ag.txt",
        ),
        description = "拦截已失效的域名请求，可显著减少无效连接与追踪泄漏",
        enabledByDefault = false,
    ),
    ;

    /**
     * 首选地址，用于界面展示。
     */
    val url: String get() = urls.first()

    companion object {
        fun fromId(id: String): FilterSource? = entries.firstOrNull { it.id == id }

        /**
         * 默认启用的规则源。
         */
        val defaults: List<FilterSource> get() = entries.filter { it.enabledByDefault }
    }
}

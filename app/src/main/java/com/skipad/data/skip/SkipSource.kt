package com.skipad.data.skip

/**
 * 规则来源格式。
 */
enum class SkipSourceFormat {
    /** 本应用自有格式（内置规则/自定义）。 */
    NATIVE,

    /** GKD 订阅（JSON5 + CSS 风格选择器）。 */
    GKD,

    /** SKIP 的 skip_config 格式（skipIds / skipBounds）。 */
    SKIP,
}

/**
 * 内置的规则订阅源。
 *
 * 全部为社区公开项目，界面上会显示名称、作者与主页，
 * 让用户清楚每条规则来自哪里，而不是一个来路不明的「订阅地址」。
 *
 * 每个源都配置多个镜像：jsDelivr 在国内通常可直连，
 * 官方 npm 源作为备选，避免单点不可达导致整源失效。
 */
enum class SkipSource(
    val id: String,
    val displayName: String,
    val author: String,
    val homepage: String,
    val description: String,
    val format: SkipSourceFormat,
    val urls: List<String>,
    val enabledByDefault: Boolean,
) {
    /** 应用内自带的精选规则（覆盖常见国内 App 的开屏按钮）。 */
    BUILTIN(
        id = "builtin",
        displayName = "内置精选规则",
        author = "SkipAd",
        homepage = "",
        description = "针对常见国内应用手工整理，随应用更新，无需联网",
        format = SkipSourceFormat.NATIVE,
        urls = emptyList(),
        enabledByDefault = true,
    ),

    /**
     * GKD 官方默认订阅。
     *
     * 注意：该订阅已被作者标记为「已停止维护」，仅保留其经典的全局开屏规则。
     */
    GKD_OFFICIAL(
        id = "gkd_official",
        displayName = "GKD 官方默认订阅",
        author = "gkd-kit",
        homepage = "https://github.com/gkd-kit/subscription",
        description = "GKD 官方订阅，含全局开屏广告规则；作者已标注停止维护，建议同时启用下方镜像订阅",
        format = SkipSourceFormat.GKD,
        urls = listOf(
            "https://cdn.jsdelivr.net/npm/@gkd-kit/subscription/dist/gkd.json5",
            "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
        ),
        enabledByDefault = true,
    ),

    /**
     * AIsouler 维护的 GKD 订阅。
     *
     * 目前更新最勤、覆盖最广的第三方 GKD 订阅之一，
     * 微博 / 应用宝 / 百度 / 中国电信等应用都有专门规则。
     */
    GKD_AISOULER(
        id = "gkd_aisouler",
        displayName = "GKD 订阅（AIsouler）",
        author = "AIsouler",
        homepage = "https://github.com/AIsouler/GKD_subscription",
        description = "社区维护最活跃的 GKD 订阅，覆盖应用最广，含开屏/弹窗/更新提示等",
        format = SkipSourceFormat.GKD,
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/AIsouler/GKD_subscription@main/dist/AIsouler_gkd.json5",
            "https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5",
        ),
        enabledByDefault = true,
    ),

    /**
     * SKIP 的官方配置。
     *
     * SKIP 采用「记录控件 id + 记录点击坐标」的方式，
     * 坐标规则依赖具体分辨率，这里只取其中的控件 id 规则。
     */
    SKIP_OFFICIAL(
        id = "skip_official",
        displayName = "SKIP 官方配置",
        author = "GuoXiCheng",
        homepage = "https://github.com/GuoXiCheng/SKIP",
        description = "SKIP 项目内置的控件 id 规则，条目较少但命中精确",
        format = SkipSourceFormat.SKIP,
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/GuoXiCheng/SKIP@main/app/src/main/assets/skip_config_v3.json",
        ),
        enabledByDefault = false,
    ),
    ;

    /** 是否需要联网下载。 */
    val requiresDownload: Boolean get() = urls.isNotEmpty()

    companion object {
        fun fromId(id: String): SkipSource? = entries.firstOrNull { it.id == id }

        /** 默认启用的源。 */
        val defaults: List<SkipSource> get() = entries.filter { it.enabledByDefault }

        /** 需要联网的源。 */
        val downloadable: List<SkipSource> get() = entries.filter { it.requiresDownload }
    }
}

package com.skipad.core.skip

/**
 * 内置的应用级跳过规则。
 *
 * 规则来源于社区公开的 SKIP 项目配置与 GKD 订阅生态，
 * 这里选取了覆盖率高、误判风险低的通用规则。
 *
 * 匹配以 resource-id 为主（最稳定），辅以文案与区域约束降低误触。
 */
object BuiltInSkipRules {

    /**
     * 全部内置规则。
     */
    /**
     * 「开屏跳过按钮」的通用特征选择器。
     *
     * 提炼自 GKD 全局组的开屏规则，覆盖绝大多数广告 SDK 的跳过按钮形态：
     * - 文案含「跳过 / 跳過 / skip」且长度很短（排除正文里出现「跳过」的长句）
     * - view id 含 skip
     * - 倒计时控件（含 count 与 down，排除 download）
     * - contentDescription 含「跳过 / skip」
     *
     * 刻意**不限制屏幕区域**：实测微博、应用宝、百度、中国电信等应用
     * 把跳过按钮放在左上、底部或居中，强行限定右上角会直接漏掉。
     */
    private const val SKIP_BUTTON_SELECTOR =
        "[visibleToUser=true][childCount=0]" +
            "[(text.length>0&&text.length<10&&(text*=\"跳过\"||text*=\"跳過\"||" +
            "text*=\"skip\"||text*=\"Skip\"||text*=\"关闭广告\"))" +
            "||vid*=\"skip\"||vid*=\"Skip\"" +
            "||vid*=\"splash_close\"||vid*=\"ad_close\"||vid*=\"close_ad\"||vid*=\"skip_ad\"" +
            "||(vid*=\"count\"&&vid*=\"down\"&&vid!*=\"download\")" +
            "||desc*=\"跳过\"||desc*=\"skip\"||desc*=\"关闭广告\"" +
            "||id$=\"tt_splash_skip_btn\"]"

    /**
     * 用户反馈无法跳过的应用。
     *
     * 为它们单独生成规则，作用有二：
     * 1. 在「应用」页能直观看到「已适配」了哪些 App
     * 2. 开启「仅对已配置应用生效」的收紧模式时，这些应用仍然有效
     */
    private val REPORTED_APPS = listOf(
        "com.sina.weibo" to "微博",
        "com.tencent.android.qqdownloader" to "应用宝",
        "com.baidu.searchbox" to "百度",
        "com.ct.client" to "中国电信",
        "com.baidu.homework" to "百度",
    )

    val rules: List<SkipRule> = buildList {
        // ---- 全局开屏跳过（不依赖任何订阅）----
        add(
            SkipRule(
                id = "universal_splash_selector",
                name = "开屏广告 · 通用跳过按钮",
                packageName = "*",
                priority = 96,
                cooldownMs = 2000L,
                match = SkipMatch(selector = SKIP_BUTTON_SELECTOR),
            )
        )

        // ---- 用户反馈的应用：单独成条，便于识别与收紧 ----
        REPORTED_APPS.forEach { (pkg, label) ->
            add(
                SkipRule(
                    id = "splash_$pkg",
                    name = "$label 开屏广告",
                    packageName = pkg,
                    priority = 97,
                    cooldownMs = 2000L,
                    match = SkipMatch(selector = SKIP_BUTTON_SELECTOR),
                )
            )
        }

        // ---- 哔哩哔哩 ----
        add(
            SkipRule(
                id = "bilibili_splash",
                name = "哔哩哔哩 开屏广告",
                packageName = "tv.danmaku.bili",
                activity = "tv.danmaku.bili.MainActivityV2",
                priority = 100,
                match = SkipMatch(
                    resourceId = listOf(
                        "tv.danmaku.bili:id/count_down",
                        "tv.danmaku.bili:id/skip",
                    ),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 什么值得买 ----
        add(
            SkipRule(
                id = "smzdm_splash",
                name = "什么值得买 开屏广告",
                packageName = "com.smzdm.client.android",
                activity = "com.smzdm.client.android.app.WelComeActivity",
                priority = 100,
                match = SkipMatch(
                    resourceId = listOf("com.smzdm.client.android:id/tv_skip"),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 京东读书 ----
        add(
            SkipRule(
                id = "jdreader_splash",
                name = "京东读书 开屏广告",
                packageName = "com.jd.app.reader",
                activity = "com.jingdong.app.reader.logo.JdLogoActivity",
                priority = 100,
                match = SkipMatch(
                    resourceId = listOf("com.jd.app.reader:id/mJumpBtn"),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 菜鸟 ----
        add(
            SkipRule(
                id = "cainiao_splash",
                name = "菜鸟 开屏广告",
                packageName = "com.cainiao.wireless",
                priority = 90,
                match = SkipMatch(
                    resourceId = listOf(
                        "com.cainiao.wireless:id/homesplash_close_fullscreen",
                    ),
                    text = listOf("跳过", "关闭"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 淘宝 / 天猫（穿山甲 SDK）----
        add(
            SkipRule(
                id = "taobao_splash",
                name = "淘宝 / 天猫 开屏广告",
                packageName = "com.taobao.taobao",
                priority = 80,
                match = SkipMatch(
                    resourceId = listOf(
                        "com.taobao.taobao:id/tt_splash_skip_btn",
                        "com.byted.pangle.m:id/tt_splash_skip_btn",
                    ),
                    text = listOf("跳过", "跳过广告"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 京东（通用）----
        add(
            SkipRule(
                id = "jd_splash",
                name = "京东 开屏广告",
                packageName = "com.jingdong.app.mall",
                priority = 80,
                match = SkipMatch(
                    resourceId = listOf(
                        "com.jingdong.app.mall:id/skip_btn",
                        "com.byted.pangle.m:id/tt_splash_skip_btn",
                    ),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 抖音 ----
        add(
            SkipRule(
                id = "douyin_splash",
                name = "抖音 开屏广告",
                packageName = "com.ss.android.ugc.aweme",
                priority = 85,
                match = SkipMatch(
                    resourceId = listOf(
                        "com.ss.android.ugc.aweme:id/skip_btn",
                        "com.byted.pangle.m:id/tt_splash_skip_btn",
                    ),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 微信读书 ----
        add(
            SkipRule(
                id = "weread_splash",
                name = "微信读书 开屏广告",
                packageName = "com.tencent.weread",
                priority = 85,
                match = SkipMatch(
                    resourceId = listOf("com.tencent.weread:id/skip_view"),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 网易云音乐 ----
        add(
            SkipRule(
                id = "netease_music_splash",
                name = "网易云音乐 开屏广告",
                packageName = "com.netease.cloudmusic",
                priority = 85,
                match = SkipMatch(
                    resourceId = listOf("com.netease.cloudmusic:id/splash_skip_btn"),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 拼多多 ----
        add(
            SkipRule(
                id = "pinduoduo_splash",
                name = "拼多多 开屏广告",
                packageName = "com.xunmeng.pinduoduo",
                priority = 80,
                match = SkipMatch(
                    resourceId = listOf("com.xunmeng.pinduoduo:id/skip_btn"),
                    text = listOf("跳过"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 通用穿山甲 / 优量汇开屏跳过按钮 ----
        // 这类按钮由广告 SDK 统一生成，resource-id 高度稳定，
        // 覆盖大量接入该 SDK 的应用。
        add(
            SkipRule(
                id = "pangle_universal_splash",
                name = "穿山甲 SDK 通用跳过按钮",
                packageName = "*",
                priority = 50,
                cooldownMs = 2000L,
                match = SkipMatch(
                    resourceId = listOf(
                        "com.byted.pangle.m:id/tt_splash_skip_btn",
                        "com.byted.pangle.m:id/pangle_splash_skip_btn",
                    ),
                    text = listOf("跳过", "跳过广告", "Skip", "SKIP"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )

        // ---- 通用「跳过」文案兜底规则 ----
        // 优先级最低，仅在右上角区域匹配「跳过」文案，
        // 且要求节点可点击，最大限度降低误触风险。
        add(
            SkipRule(
                id = "generic_skip_text",
                name = "通用跳过按钮（右上角）",
                packageName = "*",
                priority = 10,
                cooldownMs = 3000L,
                match = SkipMatch(
                    text = listOf("跳过", "跳过广告", "Skip", "SKIP", "跳过 >"),
                    region = ScreenRegion.TOP_RIGHT,
                ),
            )
        )
    }
}

package com.skipad.core.skip

import org.json.JSONArray
import org.json.JSONObject

/**
 * GKD 订阅解析。
 *
 * 订阅文件是 JSON5，结构大致为：
 * ```
 * {
 *   name: '订阅名', version: 186,
 *   globalGroups: [ { key, name, rules: [ { key, matches, action } ] } ],
 *   apps: [ { id: 'com.example', name: '某应用', enable: true,
 *             groups: [ { key, name, rules: [ { key, activityIds, matches } ] } ] } ]
 * }
 * ```
 *
 * 解析策略：
 * - `globalGroups` 转为包名 `*` 的通用规则（例如「开屏广告」全局组）
 * - `apps[].groups[].rules` 转为应用级规则
 * - `matches` 为字符串或字符串数组，每个元素编译为一条规则
 * - 含结构组合符的选择器（`>`、`@` 等）**直接跳过**，计入 [Result.skipped]
 *
 * 跳过而不是近似模拟，是因为结构型选择器依赖节点树关系，猜错会导致误点。
 */
object GkdSubscription {

    /**
     * 解析结果。
     *
     * @param rules 可用的规则
     * @param total 订阅中出现的规则总数
     * @param skipped 因语法不支持而跳过的规则数
     */
    data class Result(
        val rules: List<SkipRule>,
        val total: Int,
        val skipped: Int,
    )

    /**
     * 解析订阅文本。解析失败返回空结果而不抛异常。
     */
    fun parse(text: String, sourceId: String): Result {
        val root = try {
            Json5.parseObject(text)
        } catch (e: Exception) {
            return Result(emptyList(), 0, 0)
        }

        val rules = ArrayList<SkipRule>(512)
        var total = 0
        var skipped = 0

        // ---- 全局组：对所有应用生效 ----
        val globalGroups = root.optJSONArray("globalGroups")
        if (globalGroups != null) {
            for (g in 0 until globalGroups.length()) {
                val group = globalGroups.optJSONObject(g) ?: continue
                val groupName = group.optString("name").ifBlank { "全局规则" }
                val groupKey = group.optInt("key", g)
                val isSplash = groupName.contains("开屏")

                val groupRules = group.optJSONArray("rules") ?: continue
                for (r in 0 until groupRules.length()) {
                    val rule = groupRules.optJSONObject(r) ?: continue
                    val selectors = readMatches(rule)
                    total += selectors.size
                    selectors.forEachIndexed { index, selector ->
                        val skipRule = buildRule(
                            packageName = "*",
                            appName = null,
                            groupName = groupName,
                            groupKey = groupKey,
                            ruleKey = rule.optInt("key", r),
                            index = index,
                            selector = selector,
                            ruleObj = rule,
                            isSplash = isSplash,
                            sourceId = sourceId,
                        )
                        if (skipRule != null) rules += skipRule else skipped++
                    }
                }
            }
        }

        // ---- 应用级规则 ----
        val apps = root.optJSONArray("apps")
        if (apps != null) {
            for (a in 0 until apps.length()) {
                val app = apps.optJSONObject(a) ?: continue
                val appId = app.optString("id")
                if (appId.isBlank()) continue
                val packageName = appId
                // 订阅里显式 disable 的应用不处理
                if (app.has("enable") && !app.optBoolean("enable", true)) continue
                val appName = app.optString("name").ifBlank { packageName }

                val groups = app.optJSONArray("groups") ?: continue
                for (g in 0 until groups.length()) {
                    val group = groups.optJSONObject(g) ?: continue
                    val groupName = group.optString("name").ifBlank { "规则" }
                    val groupKey = group.optInt("key", g)
                    val isSplash = groupName.contains("开屏")

                    val groupRules = group.optJSONArray("rules") ?: continue
                    for (r in 0 until groupRules.length()) {
                        val rule = groupRules.optJSONObject(r) ?: continue
                        val selectors = readMatches(rule)
                        total += selectors.size
                        selectors.forEachIndexed { index, selector ->
                            val skipRule = buildRule(
                                packageName = packageName,
                                appName = appName,
                                groupName = groupName,
                                groupKey = groupKey,
                                ruleKey = rule.optInt("key", r),
                                index = index,
                                selector = selector,
                                ruleObj = rule,
                                isSplash = isSplash,
                                sourceId = sourceId,
                            )
                            if (skipRule != null) rules += skipRule else skipped++
                        }
                    }
                }
            }
        }

        return Result(rules, total, skipped)
    }

    /**
     * 读取规则的选择器列表。
     */
    private fun readMatches(rule: JSONObject): List<String> {
        // 必须先判数组：org.json 的 optString 对 JSONArray 会返回其字符串形式，
        // 若先读 optString 会把整个数组当成一个畸形选择器，导致整条规则失效。
        rule.optJSONArray("matches")?.let { array ->
            val list = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) list += value
            }
            return list
        }
        val single = rule.optString("matches")
        return if (single.isNotBlank()) listOf(single) else emptyList()
    }

    /**
     * 构造单条规则。选择器不受支持时返回 null。
     */
    private fun buildRule(
        packageName: String,
        appName: String?,
        groupName: String,
        groupKey: Int,
        ruleKey: Int,
        index: Int,
        selector: String,
        ruleObj: JSONObject,
        isSplash: Boolean,
        sourceId: String,
    ): SkipRule? {
        // 纯属性选择器可直接求值；结构型选择器尝试提取 @ 目标节点的属性条件
        val usableSelector = when {
            GkdSelector.isSupported(selector) -> selector
            else -> GkdSelector.normalizeTarget(selector) ?: return null
        }
        if (!GkdSelector.isSupported(usableSelector)) return null

        val activity = readActivityIds(ruleObj)

        return SkipRule(
            id = "gkd:$sourceId:$packageName:$groupKey:$ruleKey:$index",
            name = appName?.let { "$it · $groupName" } ?: groupName,
            packageName = packageName,
            activity = activity,
            // 开屏组优先级更高，避免被通用弹窗规则抢占
            priority = if (isSplash) SPLASH_PRIORITY else DEFAULT_PRIORITY,
            cooldownMs = COOLDOWN_MS,
            match = SkipMatch(selector = usableSelector, region = ScreenRegion.ANY),
            action = SkipAction(tapStrategy = parseAction(ruleObj.optString("action"))),
            source = sourceId,
        )
    }

    /**
     * 读取 activityIds，兼容字符串与数组两种写法。
     */
    private fun readActivityIds(rule: JSONObject): String {
        rule.optJSONArray("activityIds")?.let { array ->
            val values = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) values += value
            }
            return values.joinToString(",").ifBlank { "*" }
        }
        val single = rule.optString("activityIds")
        return single.ifBlank { "*" }
    }

    /**
     * GKD 的动作名映射到本引擎的点击策略。
     */
    private fun parseAction(value: String?): TapStrategy = when (value?.lowercase()) {
        "clickcenter", null, "" -> TapStrategy.CENTER
        "clicktopright", "clicktoprightcorner" -> TapStrategy.TOP_RIGHT
        "clickbottomright", "clickbottomrightcorner" -> TapStrategy.BOTTOM_RIGHT
        else -> TapStrategy.CENTER
    }

    private const val SPLASH_PRIORITY = 85
    private const val DEFAULT_PRIORITY = 60
    private const val COOLDOWN_MS = 2_000L
}

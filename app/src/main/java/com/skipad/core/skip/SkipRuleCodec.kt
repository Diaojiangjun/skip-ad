package com.skipad.core.skip

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则的 JSON 序列化。
 *
 * 采用与 GKD / Fuck-AD 订阅生态兼容的字段命名，
 * 便于直接复用社区现有的规则订阅源。
 */
object SkipRuleCodec {

    /**
     * 解析规则数组。
     *
     * 兼容三种输入：
     * 1. 顶层数组 `[{...}, {...}]`
     * 2. GKD 订阅格式 `{"apps": [{...}]}`
     * 3. SKIP 风格的 `[{appName, packageName, skipIds, skipBounds}]`
     */
    fun parseList(text: String): List<SkipRule> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            when {
                trimmed.startsWith("[") -> parseArray(JSONArray(trimmed))
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    val apps = root.optJSONArray("apps")
                    if (apps != null) {
                        parseArray(apps)
                    } else {
                        // 单条规则对象
                        listOfNotNull(parseRule(root, "builtin"))
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析规则数组（同时兼容 SKIP 风格）。
     */
    private fun parseArray(array: JSONArray): List<SkipRule> {
        val rules = ArrayList<SkipRule>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val skipRule = parseRule(obj, "builtin")
            if (skipRule != null) {
                rules += skipRule
            } else {
                // 尝试按 SKIP 风格解析
                parseSkipStyle(obj)?.let { rules += it }
            }
        }
        return rules
    }

    /**
     * 解析单条规则（标准格式）。
     */
    fun parseRule(obj: JSONObject, source: String): SkipRule? {
        val id = obj.optString("id").ifBlank {
            obj.optString("key").ifBlank { return null }
        }
        val name = obj.optString("name").ifBlank { id }

        val matchObj = obj.optJSONObject("match")
        val match = if (matchObj != null) {
            SkipMatch(
                text = matchObj.optStringList("text"),
                desc = matchObj.optStringList("desc"),
                resourceId = matchObj.optStringList("resourceId") +
                    matchObj.optStringList("resourceIds"),
                className = matchObj.optStringList("className") +
                    matchObj.optStringList("classNames"),
                excludeText = matchObj.optStringList("excludeText"),
                excludeDesc = matchObj.optStringList("excludeDesc"),
                excludeResourceId = matchObj.optStringList("excludeResourceId"),
                visible = matchObj.optBoolean("visible", true),
                region = parseRegion(matchObj.optString("region")),
            )
        } else {
            // 扁平结构
            SkipMatch(
                text = obj.optStringList("text"),
                desc = obj.optStringList("desc"),
                resourceId = obj.optStringList("resourceId") + obj.optStringList("resourceIds"),
                className = obj.optStringList("className"),
                excludeText = obj.optStringList("excludeText"),
                excludeDesc = obj.optStringList("excludeDesc"),
                excludeResourceId = obj.optStringList("excludeResourceId"),
                visible = obj.optBoolean("visible", true),
                region = parseRegion(obj.optString("region")),
            )
        }

        if (!match.hasPositiveCondition) return null

        val actionObj = obj.optJSONObject("action")
        val action = if (actionObj != null) {
            SkipAction(
                tapStrategy = TapStrategy.fromName(actionObj.optString("tapStrategy")),
                customXRatio = actionObj.optDouble("customXRatio", 0.5).toFloat().coerceIn(0f, 1f),
                customYRatio = actionObj.optDouble("customYRatio", 0.5).toFloat().coerceIn(0f, 1f),
            )
        } else {
            SkipAction(tapStrategy = TapStrategy.fromName(obj.optString("tapStrategy")))
        }

        return SkipRule(
            id = id,
            name = name,
            enabled = obj.optBoolean("enabled", true),
            packageName = obj.optString("packageName", "*").ifBlank { "*" },
            activity = obj.optString("activity", "*").ifBlank { "*" },
            priority = obj.optInt("priority", 0),
            cooldownMs = obj.optLong("cooldownMs", 3000L).coerceAtLeast(0L),
            delayMs = obj.optLong("delayMs", 0L).coerceIn(0L, 10_000L),
            match = match,
            action = action,
            source = source,
        )
    }

    /**
     * 解析 SKIP 风格规则。
     *
     * SKIP 的配置形如：
     * ```json
     * {
     *   "appName": "什么值得买",
     *   "packageName": "com.smzdm.client.android",
     *   "skipIds": [{"id": "com.smzdm.client.android:id/tv_skip"}],
     *   "skipBounds": [{"bound": "1243,176,1383,316"}]
     * }
     * ```
     */
    private fun parseSkipStyle(obj: JSONObject): SkipRule? {
        val packageName = obj.optString("packageName").ifBlank { return null }
        val appName = obj.optString("appName").ifBlank { packageName }

        val resourceIds = ArrayList<String>()
        val activityNames = ArrayList<String>()

        obj.optJSONArray("skipIds")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.optString("id").takeIf { it.isNotBlank() }?.let { resourceIds += it }
                item.optString("activityName").takeIf { it.isNotBlank() }
                    ?.let { activityNames += it }
            }
        }

        obj.optJSONArray("skipBounds")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.optString("activityName").takeIf { it.isNotBlank() }
                    ?.let { activityNames += it }
            }
        }

        if (resourceIds.isEmpty()) return null

        return SkipRule(
            id = "skip:$packageName:${resourceIds.first()}",
            name = appName,
            packageName = packageName,
            activity = activityNames.distinct().joinToString(",").ifBlank { "*" },
            match = SkipMatch(resourceId = resourceIds),
            source = "skip-style",
        )
    }

    /**
     * 序列化为标准 JSON。
     */
    fun toJson(rule: SkipRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("enabled", rule.enabled)
        put("packageName", rule.packageName)
        put("activity", rule.activity)
        put("priority", rule.priority)
        put("cooldownMs", rule.cooldownMs)
        put("delayMs", rule.delayMs)
        put("source", rule.source)
        put(
            "match",
            JSONObject().apply {
                put("text", JSONArray(rule.match.text))
                put("desc", JSONArray(rule.match.desc))
                put("resourceId", JSONArray(rule.match.resourceId))
                put("className", JSONArray(rule.match.className))
                put("excludeText", JSONArray(rule.match.excludeText))
                put("excludeDesc", JSONArray(rule.match.excludeDesc))
                put("excludeResourceId", JSONArray(rule.match.excludeResourceId))
                put("visible", rule.match.visible)
                put("region", rule.match.region.name.lowercase())
            }
        )
        put(
            "action",
            JSONObject().apply {
                put("type", "tap")
                put("tapStrategy", rule.action.tapStrategy.name.lowercase())
                put("customXRatio", rule.action.customXRatio.toDouble())
                put("customYRatio", rule.action.customYRatio.toDouble())
            }
        )
    }

    private fun parseRegion(value: String?): ScreenRegion = when (value?.lowercase()) {
        "topright", "top_right" -> ScreenRegion.TOP_RIGHT
        "bottomright", "bottom_right" -> ScreenRegion.BOTTOM_RIGHT
        "top" -> ScreenRegion.TOP
        "bottom" -> ScreenRegion.BOTTOM
        else -> ScreenRegion.ANY
    }
}

/**
 * JSONObject 的字符串列表读取扩展。
 */
private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    val result = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val value = array.optString(index)
        if (value.isNotBlank()) result += value
    }
    return result
}

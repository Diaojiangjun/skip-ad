package com.skipad.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则序列化与兼容性测试。
 */
class SkipRuleCodecTest {

    @Test
    fun `解析标准格式规则`() {
        val json = """
            [
              {
                "id": "test_rule",
                "name": "测试规则",
                "packageName": "com.example",
                "activity": "SplashActivity",
                "priority": 50,
                "match": {
                  "text": ["跳过"],
                  "resourceId": ["com.example:id/skip"],
                  "excludeText": ["跳过广告会员"]
                },
                "action": { "tapStrategy": "top_right" }
              }
            ]
        """.trimIndent()

        val rules = SkipRuleCodec.parseList(json)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("test_rule", rule.id)
        assertEquals("测试规则", rule.name)
        assertEquals("com.example", rule.packageName)
        assertEquals("SplashActivity", rule.activity)
        assertEquals(50, rule.priority)
        assertEquals(listOf("跳过"), rule.match.text)
        assertEquals(listOf("com.example:id/skip"), rule.match.resourceId)
        assertEquals(listOf("跳过广告会员"), rule.match.excludeText)
        assertEquals(TapStrategy.TOP_RIGHT, rule.action.tapStrategy)
    }

    @Test
    fun `解析扁平结构规则`() {
        val json = """
            [{
              "id": "flat",
              "name": "扁平规则",
              "packageName": "com.flat",
              "text": ["跳过"],
              "resourceId": ["com.flat:id/btn"]
            }]
        """.trimIndent()

        val rules = SkipRuleCodec.parseList(json)
        assertEquals(1, rules.size)
        assertEquals(listOf("跳过"), rules[0].match.text)
        assertEquals(listOf("com.flat:id/btn"), rules[0].match.resourceId)
    }

    @Test
    fun `解析 GKD 订阅格式`() {
        val json = """
            {
              "apps": [
                {
                  "id": "gkd_rule",
                  "name": "GKD 规则",
                  "packageName": "com.gkd",
                  "match": { "text": ["跳过广告"] }
                }
              ]
            }
        """.trimIndent()

        val rules = SkipRuleCodec.parseList(json)
        assertEquals(1, rules.size)
        assertEquals("gkd_rule", rules[0].id)
    }

    @Test
    fun `解析 SKIP 风格规则`() {
        val json = """
            [{
              "appName": "什么值得买",
              "packageName": "com.smzdm.client.android",
              "skipIds": [{
                "id": "com.smzdm.client.android:id/tv_skip",
                "activityName": "com.smzdm.client.android.app.WelComeActivity"
              }],
              "skipBounds": [{
                "bound": "1243,176,1383,316",
                "activityName": "com.smzdm.client.android.app.WelComeActivity"
              }]
            }]
        """.trimIndent()

        val rules = SkipRuleCodec.parseList(json)
        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("com.smzdm.client.android", rule.packageName)
        assertEquals("什么值得买", rule.name)
        assertEquals(listOf("com.smzdm.client.android:id/tv_skip"), rule.match.resourceId)
        assertTrue(rule.activity.contains("WelComeActivity"))
    }

    @Test
    fun `解析单条规则对象`() {
        val json = """
            {
              "id": "single",
              "name": "单条",
              "packageName": "com.single",
              "match": { "text": ["跳过"] }
            }
        """.trimIndent()

        val rules = SkipRuleCodec.parseList(json)
        assertEquals(1, rules.size)
        assertEquals("single", rules[0].id)
    }

    @Test
    fun `无正向条件的规则被丢弃`() {
        val json = """
            [{ "id": "no_match", "name": "无匹配", "packageName": "com.x",
               "match": { "excludeText": ["不要点"] } }]
        """.trimIndent()

        assertEquals(0, SkipRuleCodec.parseList(json).size)
    }

    @Test
    fun `畸形输入返回空列表而不抛异常`() {
        assertEquals(0, SkipRuleCodec.parseList("").size)
        assertEquals(0, SkipRuleCodec.parseList("not json").size)
        assertEquals(0, SkipRuleCodec.parseList("[{broken").size)
        assertEquals(0, SkipRuleCodec.parseList("{}").size)
    }

    @Test
    fun `序列化后可再次解析且保持一致`() {
        val original = SkipRule(
            id = "roundtrip",
            name = "往返测试",
            packageName = "com.rt",
            activity = "SplashActivity",
            priority = 42,
            cooldownMs = 2500L,
            match = SkipMatch(
                text = listOf("跳过"),
                desc = listOf("关闭广告"),
                resourceId = listOf("com.rt:id/skip"),
                className = listOf("android.widget.TextView"),
                excludeText = listOf("会员"),
                region = ScreenRegion.TOP_RIGHT,
            ),
            action = SkipAction(tapStrategy = TapStrategy.BOTTOM_RIGHT, customXRatio = 0.25f),
        )

        val json = SkipRuleCodec.toJson(original).toString()
        val parsed = SkipRuleCodec.parseList("[$json]")

        assertEquals(1, parsed.size)
        val result = parsed[0]
        assertEquals(original.id, result.id)
        assertEquals(original.name, result.name)
        assertEquals(original.packageName, result.packageName)
        assertEquals(original.priority, result.priority)
        assertEquals(original.cooldownMs, result.cooldownMs)
        assertEquals(original.match.text, result.match.text)
        assertEquals(original.match.desc, result.match.desc)
        assertEquals(original.match.resourceId, result.match.resourceId)
        assertEquals(original.match.className, result.match.className)
        assertEquals(original.match.excludeText, result.match.excludeText)
        assertEquals(original.match.region, result.match.region)
        assertEquals(original.action.tapStrategy, result.action.tapStrategy)
        assertEquals(original.action.customXRatio, result.action.customXRatio, 0.001f)
    }

    @Test
    fun `内置规则全部有效`() {
        val rules = BuiltInSkipRules.rules
        assertTrue("内置规则不应为空", rules.isNotEmpty())

        rules.forEach { rule ->
            assertTrue("规则 ${rule.id} 缺少 id", rule.id.isNotBlank())
            assertTrue("规则 ${rule.id} 缺少名称", rule.name.isNotBlank())
            assertTrue(
                "规则 ${rule.id} 缺少正向匹配条件",
                rule.match.hasPositiveCondition
            )
        }

        // id 必须唯一
        val ids = rules.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `内置规则必须有精确的匹配条件`() {
        // 误触防线：每条内置规则至少要满足其一
        // - 指定资源 ID（最精确）
        // - 限定右上角区域
        // - 使用 GKD 选择器（属性条件本身足够具体，且必须能编译）
        BuiltInSkipRules.rules.forEach { rule ->
            val hasResourceId = rule.match.resourceId.isNotEmpty()
            val regionConstrained = rule.match.region == ScreenRegion.TOP_RIGHT
            val selector = rule.match.selector
            val hasCompilableSelector = selector != null && GkdSelector.isSupported(selector)

            assertTrue(
                "规则 ${rule.id} 缺少精确匹配条件，误触风险过高",
                hasResourceId || regionConstrained || hasCompilableSelector
            )
        }
    }
}

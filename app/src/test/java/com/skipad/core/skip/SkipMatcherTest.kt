package com.skipad.core.skip

import com.skipad.core.skip.SkipRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用级规则匹配引擎测试。
 *
 * 重点验证**误触防护**：无障碍方案会真实点击屏幕，
 * 任何过宽的匹配条件都可能在用户手机上造成不可逆的误操作。
 */
class SkipMatcherTest {

    private val screenWidth = 1080
    private val screenHeight = 2400

    private fun matcher() = SkipMatcher(screenWidth, screenHeight)

    /** 构造一个位于右上角的跳过按钮节点。 */
    private fun skipButtonNode(
        resourceId: String? = "com.example:id/skip_btn",
        text: String? = "跳过",
    ) = SkipNode(
        text = text,
        desc = null,
        resourceId = resourceId,
        className = "android.widget.TextView",
        visibleToUser = true,
        clickable = true,
        bounds = SkipRect(900, 100, 1000, 160),
    )

    // ---------- 基础匹配 ----------

    @Test
    fun `按 resourceId 匹配命中`() {
        val rule = SkipRule(
            id = "r1",
            name = "测试",
            packageName = "com.example",
            match = SkipMatch(resourceId = listOf("com.example:id/skip_btn")),
        )
        val result = matcher().findMatch(listOf(skipButtonNode()), listOf(rule))
        assertNotNull(result)
        assertEquals("r1", result!!.rule.id)
    }

    @Test
    fun `按文案匹配命中`() {
        val rule = SkipRule(
            id = "r2",
            name = "测试",
            match = SkipMatch(text = listOf("跳过")),
        )
        val result = matcher().findMatch(listOf(skipButtonNode()), listOf(rule))
        assertNotNull(result)
    }

    @Test
    fun `无匹配条件时不命中任何节点`() {
        val rule = SkipRule(id = "r3", name = "空规则", match = SkipMatch())
        assertNull(matcher().findMatch(listOf(skipButtonNode()), listOf(rule)))
    }

    @Test
    fun `不可见节点不被匹配`() {
        val rule = SkipRule(
            id = "r4",
            name = "测试",
            match = SkipMatch(text = listOf("跳过")),
        )
        val hidden = skipButtonNode().copy(visibleToUser = false)
        assertNull(matcher().findMatch(listOf(hidden), listOf(rule)))
    }

    @Test
    fun `不可见节点在 visible 关闭时可匹配`() {
        val rule = SkipRule(
            id = "r5",
            name = "测试",
            match = SkipMatch(text = listOf("跳过"), visible = false),
        )
        val hidden = skipButtonNode().copy(visibleToUser = false)
        assertNotNull(matcher().findMatch(listOf(hidden), listOf(rule)))
    }

    // ---------- 误触防护 ----------

    @Test
    fun `排除文案优先于正向匹配`() {
        val rule = SkipRule(
            id = "r6",
            name = "测试",
            match = SkipMatch(
                text = listOf("跳过"),
                excludeText = listOf("跳过广告会员"),
            ),
        )
        val node = skipButtonNode(text = "跳过广告会员")
        assertNull(matcher().findMatch(listOf(node), listOf(rule)))
    }

    @Test
    fun `排除 resourceId 生效`() {
        val rule = SkipRule(
            id = "r7",
            name = "测试",
            match = SkipMatch(
                text = listOf("跳过"),
                excludeResourceId = listOf("com.example:id/skip_btn"),
            ),
        )
        assertNull(matcher().findMatch(listOf(skipButtonNode()), listOf(rule)))
    }

    @Test
    fun `区域约束生效`() {
        // 按钮在左下角，不应匹配右上角区域规则
        val rule = SkipRule(
            id = "r8",
            name = "测试",
            match = SkipMatch(
                text = listOf("跳过"),
                region = ScreenRegion.TOP_RIGHT,
            ),
        )
        val bottomLeft = SkipNode(
            text = "跳过",
            visibleToUser = true,
            bounds = SkipRect(50, 2000, 150, 2060),
        )
        assertNull(matcher().findMatch(listOf(bottomLeft), listOf(rule)))
    }

    @Test
    fun `右上角区域规则命中右上角按钮`() {
        val rule = SkipRule(
            id = "r9",
            name = "测试",
            match = SkipMatch(
                text = listOf("跳过"),
                region = ScreenRegion.TOP_RIGHT,
            ),
        )
        assertNotNull(matcher().findMatch(listOf(skipButtonNode()), listOf(rule)))
    }

    @Test
    fun `越界节点不被匹配`() {
        val rule = SkipRule(
            id = "r10",
            name = "测试",
            match = SkipMatch(text = listOf("跳过")),
        )
        val offscreen = SkipNode(
            text = "跳过",
            visibleToUser = true,
            bounds = SkipRect(-500, -500, -400, -400),
        )
        assertNull(matcher().findMatch(listOf(offscreen), listOf(rule)))
    }

    @Test
    fun `零尺寸节点不被匹配`() {
        val rule = SkipRule(
            id = "r11",
            name = "测试",
            match = SkipMatch(text = listOf("跳过")),
        )
        val empty = SkipNode(
            text = "跳过",
            visibleToUser = true,
            bounds = SkipRect(100, 100, 100, 100),
        )
        assertNull(matcher().findMatch(listOf(empty), listOf(rule)))
    }

    // ---------- 规则作用域 ----------

    @Test
    fun `包名不匹配时不生效`() {
        val rule = SkipRule(
            id = "r12",
            name = "测试",
            packageName = "com.other",
            match = SkipMatch(text = listOf("跳过")),
        )
        assertFalse(rule.appliesTo("com.example", "MainActivity"))
        assertTrue(rule.appliesTo("com.other", "MainActivity"))
    }

    @Test
    fun `通配包名对所有应用生效`() {
        val rule = SkipRule(
            id = "r13",
            name = "测试",
            packageName = "*",
            match = SkipMatch(text = listOf("跳过")),
        )
        assertTrue(rule.appliesTo("com.any", null))
    }

    @Test
    fun `Activity 精确与包含匹配`() {
        val rule = SkipRule(
            id = "r14",
            name = "测试",
            packageName = "com.example",
            activity = "com.example.SplashActivity",
            match = SkipMatch(text = listOf("跳过")),
        )
        assertTrue(rule.appliesTo("com.example", "com.example.SplashActivity"))
        // 包含匹配
        assertTrue(rule.appliesTo("com.example", "com.example.ui.SplashActivity"))
        assertFalse(rule.appliesTo("com.example", "com.example.MainActivity"))
    }

    @Test
    fun `多值 Activity 列表匹配`() {
        val rule = SkipRule(
            id = "r15",
            name = "测试",
            packageName = "com.example",
            activity = "SplashActivity,WelcomeActivity",
            match = SkipMatch(text = listOf("跳过")),
        )
        assertTrue(rule.appliesTo("com.example", "com.example.SplashActivity"))
        assertTrue(rule.appliesTo("com.example", "com.example.WelcomeActivity"))
        assertFalse(rule.appliesTo("com.example", "com.example.MainActivity"))
    }

    @Test
    fun `禁用的规则不生效`() {
        val rule = SkipRule(
            id = "r16",
            name = "测试",
            enabled = false,
            match = SkipMatch(text = listOf("跳过")),
        )
        assertFalse(rule.appliesTo("com.example", null))
    }

    // ---------- 优先级 ----------

    @Test
    fun `高优先级规则优先生效`() {
        val lowPriority = SkipRule(
            id = "low",
            name = "低优先级",
            priority = 1,
            match = SkipMatch(text = listOf("跳过")),
        )
        val highPriority = SkipRule(
            id = "high",
            name = "高优先级",
            priority = 100,
            match = SkipMatch(resourceId = listOf("com.example:id/skip_btn")),
        )

        val result = matcher().findMatch(
            listOf(skipButtonNode()),
            listOf(lowPriority, highPriority),
        )
        assertNotNull(result)
        assertEquals("high", result!!.rule.id)
    }

    @Test
    fun `规则索引按包名分桶`() {
        val appRule = SkipRule(
            id = "app",
            name = "指定应用",
            packageName = "com.example",
            match = SkipMatch(text = listOf("跳过")),
        )
        val wildcardRule = SkipRule(
            id = "wildcard",
            name = "通配",
            packageName = "*",
            match = SkipMatch(text = listOf("跳过")),
        )
        val index = SkipRuleIndex(listOf(appRule, wildcardRule))

        assertEquals(2, index.size)
        assertEquals(2, index.rulesFor("com.example").size)
        assertEquals(1, index.rulesFor("com.other").size)
    }

    @Test
    fun `规则索引按优先级排序`() {
        val low = SkipRule(id = "low", name = "低", priority = 1, packageName = "com.example",
            match = SkipMatch(text = listOf("跳过")))
        val high = SkipRule(id = "high", name = "高", priority = 100, packageName = "com.example",
            match = SkipMatch(text = listOf("跳过")))
        val index = SkipRuleIndex(listOf(low, high))

        val rules = index.rulesFor("com.example")
        assertEquals("high", rules[0].id)
    }

    @Test
    fun `收紧模式下仅保留明确指定包名的规则`() {
        val specific = SkipRule(
            id = "specific",
            name = "指定应用",
            packageName = "com.example",
            match = SkipMatch(text = listOf("跳过")),
        )
        val wildcard = SkipRule(
            id = "wildcard",
            name = "通配",
            packageName = "*",
            match = SkipMatch(text = listOf("跳过")),
        )
        val index = SkipRuleIndex(listOf(specific, wildcard))

        // 收紧模式等价于剔除通配规则
        val strict = index.rulesFor("com.example").filter { it.packageName == "com.example" }
        assertEquals(1, strict.size)
        assertEquals("specific", strict[0].id)

        // 未配置的应用在收紧模式下没有任何规则
        val strictOther = index.rulesFor("com.other").filter { it.packageName == "com.other" }
        assertTrue(strictOther.isEmpty())
    }

    // ---------- 点击坐标 ----------

    @Test
    fun `中心点击策略取节点中心`() {
        val action = SkipAction(tapStrategy = TapStrategy.CENTER)
        val (x, y) = action.resolveTapPoint(SkipRect(900, 100, 1000, 160))
        assertEquals(950, x)
        assertEquals(130, y)
    }

    @Test
    fun `右上角点击策略取节点右上`() {
        val action = SkipAction(tapStrategy = TapStrategy.TOP_RIGHT)
        val (x, y) = action.resolveTapPoint(SkipRect(900, 100, 1000, 160))
        assertEquals(999, x)
        assertEquals(101, y)
    }

    @Test
    fun `自定义比例点击策略`() {
        val action = SkipAction(
            tapStrategy = TapStrategy.CUSTOM_RATIO,
            customXRatio = 0f,
            customYRatio = 0f,
        )
        val (x, y) = action.resolveTapPoint(SkipRect(900, 100, 1000, 160))
        assertEquals(900, x)
        assertEquals(100, y)
    }
}

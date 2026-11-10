package com.skipad.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GKD 选择器求值测试。
 *
 * 这些用例直接取自真实 GKD 订阅里出现过的选择器写法，
 * 保证「能吃的语法真的能吃、不能吃的语法明确拒绝」，避免误点。
 */
class GkdSelectorTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        resourceId: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        visibleToUser: Boolean = true,
        childCount: Int = 0,
    ) = SkipNode(
        text = text,
        desc = desc,
        resourceId = resourceId,
        className = className,
        clickable = clickable,
        visibleToUser = visibleToUser,
        childCount = childCount,
        bounds = SkipRect(0, 0, 100, 60),
    )

    @Test
    fun `结构型选择器不被支持`() {
        val unsupported = listOf(
            "@Button[clickable=true] - [text=\"欢迎登录应用宝\"]",
            "WebView > View > @TextView[clickable=true]",
            "[text=\"a\"] < View",
            "@ImageView <<n [vid=\"container\"]",
            "View >(1,2) TextView",
            "[vid=\"skip\"] +3 [text=\"x\"]",
        )
        unsupported.forEach {
            assertTrue("应判定为不支持: $it", !GkdSelector.isSupported(it))
            assertNull("不应编译成功: $it", GkdSelector.compile(it))
        }
    }

    @Test
    fun `纯属性选择器被支持`() {
        val supported = listOf(
            "[text=\"跳过\"]",
            "[text*=\"跳过\"][visibleToUser=true]",
            "[childCount=0][visibleToUser=true][(text.length<10&&(text*=\"跳过\"||desc*=\"skip\"))]",
            "[vid=\"skip\"]",
            "[id=\"com.sina.weibo:id/container\"]",
            "[text=\"a\"] || [text=\"b\"]",
            "[text^=\"继续看视频\"]",
        )
        supported.forEach {
            assertTrue("应判定为支持: $it", GkdSelector.isSupported(it))
        }
    }

    @Test
    fun `文本相等与包含`() {
        val exact = GkdSelector.compile("[text=\"跳过\"]")!!
        assertTrue(exact(node(text = "跳过")))
        assertTrue(!exact(node(text = "跳过 3")))

        val contains = GkdSelector.compile("[text*=\"跳过\"]")!!
        assertTrue(contains(node(text = "跳过 3")))
        assertTrue(contains(node(text = "5s 跳过广告")))
        assertTrue(!contains(node(text = "下一集")))
    }

    @Test
    fun `前缀与后缀匹配`() {
        val start = GkdSelector.compile("[text^=\"跳过\"]")!!
        assertTrue(start(node(text = "跳过广告")))
        assertTrue(!start(node(text = "请跳过")))

        val end = GkdSelector.compile("[id$=\"tt_splash_skip_btn\"]")!!
        assertTrue(end(node(resourceId = "com.byted.pangle.m:id/tt_splash_skip_btn")))
        assertTrue(!end(node(resourceId = "com.byted.pangle.m:id/other")))
    }

    @Test
    fun `vid 取视图 id 的末段`() {
        val predicate = GkdSelector.compile("[vid=\"skip\"]")!!
        assertTrue(predicate(node(resourceId = "com.example:id/skip")))
        assertTrue(!predicate(node(resourceId = "com.example:id/skip_container")))
    }

    @Test
    fun `长度条件排除长句`() {
        val predicate = GkdSelector.compile("[text.length<10][text*=\"跳过\"]")!!
        assertTrue(predicate(node(text = "跳过")))
        assertTrue(predicate(node(text = "跳过广告 3")))
        // 正文里出现「跳过」的长句不应命中
        assertTrue(!predicate(node(text = "你可以跳过这一步直接进入下一步设置页面")))
    }

    @Test
    fun `childCount 与 visibleToUser 条件`() {
        val leaf = GkdSelector.compile("[childCount=0]")!!
        assertTrue(leaf(node(childCount = 0)))
        assertTrue(!leaf(node(childCount = 3)))

        val visible = GkdSelector.compile("[visibleToUser=true]")!!
        assertTrue(visible(node(visibleToUser = true)))
        assertTrue(!visible(node(visibleToUser = false)))
    }

    @Test
    fun `或运算与与运算优先级`() {
        // && 优先级高于 ||
        val predicate = GkdSelector.compile("[text=\"a\"] && [vid=\"b\"] || [text=\"c\"]")!!
        assertTrue("a 且 b", predicate(node(text = "a", resourceId = "x:id/b")))
        assertTrue("仅 c", predicate(node(text = "c")))
        assertTrue("a 但不含 b 且非 c", !predicate(node(text = "a", resourceId = "x:id/z")))
    }

    @Test
    fun `取反条件`() {
        val predicate = GkdSelector.compile("[text!=\"关闭\"]")!!
        assertTrue(predicate(node(text = "跳过")))
        assertTrue(!predicate(node(text = "关闭")))
    }

    @Test
    fun `desc 条件可命中图片按钮`() {
        // 很多跳过按钮是 ImageView，没有 text，只有 contentDescription
        val predicate = GkdSelector.compile("[desc*=\"跳过\"]")!!
        assertTrue(predicate(node(desc = "跳过广告")))
        assertTrue(predicate(node(desc = "跳过")))
        assertTrue(!predicate(node(desc = "关闭")))
    }

    @Test
    fun `内置通用选择器可编译且能命中典型跳过按钮`() {
        val rule = BuiltInSkipRules.rules.first { it.id == "universal_splash_selector" }
        val selector = rule.match.selector
        assertTrue("通用规则应使用选择器", selector != null)
        assertTrue("通用选择器必须可编译", GkdSelector.isSupported(selector!!))

        val predicate = GkdSelector.compile(selector)!!

        // 文案型
        assertTrue(predicate(node(text = "跳过")))
        assertTrue(predicate(node(text = "跳过广告")))
        assertTrue(predicate(node(text = "3s 跳过")))
        // 图片按钮 contentDescription 型
        assertTrue(predicate(node(desc = "跳过")))
        // view id 型
        assertTrue(predicate(node(resourceId = "com.sina.weibo:id/skip")))
        // 穿山甲倒计时控件
        assertTrue(predicate(node(resourceId = "com.byted.pangle.m:id/tt_splash_skip_btn")))
        // 非跳过按钮不应命中
        assertTrue(!predicate(node(text = "打开")))
        assertTrue(!predicate(node(text = "关闭广告", childCount = 2)))
    }

    @Test
    fun `结构型选择器可提取目标节点条件`() {
        // 真实 GKD 订阅里百度红包弹窗的写法
        val normalized = GkdSelector.normalizeTarget(
            "@ImageView[vid=\"obfuscated\"][text=null][childCount=0][clickable=true]" +
                "[visibleToUser=true][index=1] <2 LinearLayout[childCount=2] < FrameLayout"
        )
        assertTrue("应能提取出可用条件", normalized != null)
        assertTrue("提取结果必须可编译", GkdSelector.isSupported(normalized!!))
        assertTrue("应丢弃 index 条件", !normalized.contains("index"))

        val predicate = GkdSelector.compile(normalized)!!
        val target = SkipNode(
            resourceId = "com.baidu.searchbox:id/obfuscated",
            text = null,
            childCount = 0,
            clickable = true,
            visibleToUser = true,
            bounds = SkipRect(10, 10, 90, 90),
        )
        assertTrue("应命中关闭按钮", predicate(target))
        assertTrue("非叶节点不应命中", !predicate(target.copy(childCount = 3)))
    }

    @Test
    fun `仅凭泛化属性无法提取目标条件`() {
        // 应用宝的登录弹窗规则：目标只有 Button[clickable=true]，没有可辨识的属性
        assertNull(
            GkdSelector.normalizeTarget("@Button[clickable=true] - [text=\"欢迎登录应用宝\"]")
        )
    }

    @Test
    fun `状态类未知属性导致放弃提取`() {
        // checked 是引擎无法观测的状态，丢弃会改变语义，因此整条放弃
        assertNull(
            GkdSelector.normalizeTarget("@View[text=\"已了解\"][checked=false] > TextView")
        )
    }

}

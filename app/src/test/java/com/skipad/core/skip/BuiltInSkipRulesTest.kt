package com.skipad.core.skip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用开屏跳过规则的识别范围。
 *
 * 这条规则是「不依赖任何订阅」的最后兜底，因此最容易误伤。
 * 这里的用例专门盯住边界：**能认出广告的关闭按钮，但不能把普通关闭按钮当成广告**。
 */
class BuiltInSkipRulesTest {

    private fun universalRule(): SkipRule = BuiltInSkipRules.rules.first {
        it.id == "universal_splash_selector"
    }

    private fun matches(
        text: String? = null,
        desc: String? = null,
        resourceId: String? = null,
        childCount: Int = 0,
        visibleToUser: Boolean = true,
    ): Boolean {
        val rule = universalRule()
        val predicate = GkdSelector.compile(rule.match.selector!!) ?: return false
        return predicate(
            SkipNode(
                text = text,
                desc = desc,
                resourceId = resourceId,
                childCount = childCount,
                visibleToUser = visibleToUser,
                bounds = SkipRect(0, 0, 100, 100),
            )
        )
    }

    @Test
    fun `跳过按钮能被识别`() {
        assertTrue(matches(text = "跳过"))
        assertTrue(matches(text = "跳過"))
        assertTrue(matches(text = "Skip"))
    }

    @Test
    fun `广告字样的关闭按钮能被识别`() {
        assertTrue(matches(text = "关闭广告"))
        assertTrue(matches(text = "跳过广告"))
        assertTrue(matches(desc = "关闭广告"))
    }

    @Test
    fun `广告 SDK 的关闭控件 id 能被识别`() {
        assertTrue(matches(resourceId = "com.x:id/splash_close"))
        assertTrue(matches(resourceId = "com.x:id/ad_close"))
        assertTrue(matches(resourceId = "com.x:id/close_ad"))
        assertTrue(matches(resourceId = "com.x:id/tt_splash_skip_btn"))
    }

    @Test
    fun `普通的关闭按钮不应被当成广告`() {
        // 这是最危险的误伤：绝大多数应用的「关闭」是正常功能按钮
        assertFalse(matches(text = "关闭"))
        assertFalse(matches(desc = "关闭"))
        assertFalse(matches(resourceId = "com.x:id/close"))
        assertFalse(matches(resourceId = "com.x:id/close_btn"))
        assertFalse(matches(resourceId = "com.x:id/iv_close"))
    }

    @Test
    fun `正文里出现跳过二字的普通文本不应命中`() {
        // 长度限制用来排除正文里的「跳过」长句
        assertFalse(matches(text = "这里可以跳过这段很长的说明文字"))
    }

    @Test
    fun `带子节点的容器不应命中`() {
        // 跳过按钮通常是叶子节点；带子节点更可能是容器
        assertFalse(matches(text = "跳过", childCount = 2))
    }

    @Test
    fun `不可见的节点不应命中`() {
        assertFalse(matches(text = "跳过", visibleToUser = false))
    }

    @Test
    fun `下载按钮不应被误判为跳过`() {
        assertFalse(matches(resourceId = "com.x:id/count_down_download"))
    }

    @Test
    fun `通用规则与专用规则都带有可编译的选择器`() {
        BuiltInSkipRules.rules.forEach { rule ->
            val selector = rule.match.selector
            if (selector != null) {
                assertNotNull("规则 ${rule.id} 的选择器必须可编译", GkdSelector.compile(selector))
            }
        }
    }
}

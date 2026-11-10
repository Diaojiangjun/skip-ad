package com.skipad.core.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GKD 订阅解析测试。
 *
 * 用一段结构与真实订阅一致（但规模很小）的 JSON5 作为输入，
 * 校验：全局组、应用组、禁用应用、结构型规则跳过、activityIds 多值等行为。
 */
class GkdSubscriptionTest {

    private val sample = """
        {
          id: 1,
          name: '测试订阅',
          version: 3,
          globalGroups: [
            {
              key: 0,
              name: '开屏广告',
              rules: [
                { key: 0, matches: '[text*="跳过"][text.length<10]' },
                { key: 1, matches: 'View > @TextView[text="跳过"]' }
              ]
            }
          ],
          apps: [
            {
              id: 'com.sina.weibo',
              name: '微博',
              groups: [
                {
                  key: 1,
                  name: '开屏广告',
                  rules: [
                    {
                      key: 0,
                      activityIds: '.SplashActivity',
                      matches: '[vid="skip"]'
                    },
                    {
                      key: 1,
                      activityIds: ['.A', '.B'],
                      matches: ['[text="跳过"]', '[desc="跳过"]']
                    }
                  ]
                }
              ]
            },
            {
              id: 'com.disabled.app',
              name: '被禁用的应用',
              enable: false,
              groups: [
                { key: 0, name: '开屏广告', rules: [ { matches: '[text="跳过"]' } ] }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `全局组转为通配规则`() {
        val result = GkdSubscription.parse(sample, "test")
        val wildcard = result.rules.filter { it.packageName == "*" }
        // 两条都能用：一条是纯属性选择器，另一条是结构型选择器，
        // 后者通过提取 @ 目标节点条件转成了可求值的形式
        assertEquals("两条全局规则都可用", 2, wildcard.size)
        assertTrue(
            "归一化后不应残留结构组合符",
            wildcard.all { it.match.selector?.contains(">") == false }
        )
        assertTrue(wildcard.all { it.name.contains("开屏广告") })
    }

    @Test
    fun `应用规则被正确解析`() {
        val result = GkdSubscription.parse(sample, "test")
        val weibo = result.rules.filter { it.packageName == "com.sina.weibo" }
        assertEquals("1 条单值选择器 + 2 条数组选择器 = 3 条", 3, weibo.size)
        assertTrue(weibo.all { it.activity.isNotBlank() })
        assertTrue("activityIds 数组应合并", weibo.any { it.activity.contains(",") })
    }

    @Test
    fun `结构型选择器被转换为目标节点条件`() {
        val result = GkdSubscription.parse(sample, "test")
        // 全局 2 条 + 微博 3 条（单值 1 + 数组 2），被 enable:false 的应用整体不计入
        assertEquals("总规则数", 5, result.total)
        assertEquals("样本中所有选择器都能安全转换", 0, result.skipped)
        assertTrue(
            "结果中不应残留结构组合符",
            result.rules.none { it.match.selector?.contains(">") == true }
        )
        // 原本是 `View > @TextView[text="跳过"]`，应被改成 `[text="跳过"]`
        assertTrue(
            "应保留目标节点的文案条件",
            result.rules.any { it.match.selector == "[text=\"跳过\"]" }
        )
    }

    @Test
    fun `显式禁用的应用不产生规则`() {
        val result = GkdSubscription.parse(sample, "test")
        assertTrue(result.rules.none { it.packageName == "com.disabled.app" })
    }

    @Test
    fun `规则 id 唯一`() {
        val result = GkdSubscription.parse(sample, "test")
        val ids = result.rules.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `开屏组优先级高于普通组`() {
        val result = GkdSubscription.parse(sample, "test")
        val splash = result.rules.first { it.name.contains("开屏") }
        assertTrue("开屏组应有较高优先级", splash.priority >= 85)
    }

    @Test
    fun `非法输入返回空结果而不抛异常`() {
        val result = GkdSubscription.parse("not a json", "test")
        assertTrue(result.rules.isEmpty())
        assertEquals(0, result.total)
    }

    @Test
    fun `解析出的规则可被匹配引擎使用`() {
        val result = GkdSubscription.parse(sample, "test")
        val rules = result.rules.filter { it.packageName == "com.sina.weibo" }
        val matcher = SkipMatcher(screenWidth = 1080, screenHeight = 1920)

        val node = SkipNode(
            text = "跳过",
            resourceId = "com.sina.weibo:id/skip",
            visibleToUser = true,
            bounds = SkipRect(900, 100, 1020, 180),
        )
        val match = matcher.findMatch(listOf(node), rules)
        assertTrue("订阅规则应能命中跳过按钮", match != null)
    }
}

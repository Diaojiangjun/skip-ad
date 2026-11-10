package com.skipad.data.skip

import com.skipad.core.skip.BuiltInSkipRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则集合并的约定。
 *
 * 这里验证的是「最终交给引擎的那份规则集」是怎么拼出来的，
 * 包含一条硬性约定：**内置规则永远生效**，哪怕用户把对应源关掉。
 */
class BuildRuleSetTest {

    @Test
    fun `未启用内置源时仍会补上内置规则`() {
        val result = buildRuleSet(
            enabledSourceIds = setOf("gkd_official"),
            sourceRules = emptyList(),
            customJson = emptyList(),
        )
        assertEquals(
            "内置规则必须始终存在",
            BuiltInSkipRules.rules.size,
            result.count { it.source == "builtin" || it.id.startsWith("splash_") },
        )
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `已启用内置源时不重复追加`() {
        val withBuiltin = buildRuleSet(
            enabledSourceIds = setOf(SkipSource.BUILTIN.id),
            sourceRules = BuiltInSkipRules.rules,
            customJson = emptyList(),
        )
        val withoutBuiltin = buildRuleSet(
            enabledSourceIds = emptySet(),
            sourceRules = emptyList(),
            customJson = emptyList(),
        )
        assertEquals(
            "两种路径得到的内置规则数量应一致",
            withoutBuiltin.size,
            withBuiltin.size,
        )
    }

    @Test
    fun `各源规则按顺序保留在前`() {
        val fake = com.skipad.core.skip.SkipRule(
            id = "src_rule",
            name = "来源规则",
            packageName = "*",
        )
        val result = buildRuleSet(
            enabledSourceIds = setOf(SkipSource.BUILTIN.id),
            sourceRules = listOf(fake),
            customJson = emptyList(),
        )
        assertTrue(result.any { it.id == "src_rule" })
    }

    @Test
    fun `自定义规则能被解析并加入`() {
        val json = """
            {"id":"custom_1","name":"我的规则","packageName":"*",
             "match":{"text":["跳过"]}}
        """.trimIndent()
        val result = buildRuleSet(
            enabledSourceIds = setOf(SkipSource.BUILTIN.id),
            sourceRules = emptyList(),
            customJson = listOf(json),
        )
        assertTrue("自定义规则应进入最终集合", result.any { it.id == "custom_1" })
    }

    @Test
    fun `损坏的自定义规则被跳过且不影响其他规则`() {
        val good = """
            {"id":"custom_ok","name":"好的","packageName":"*","match":{"text":["跳过"]}}
        """.trimIndent()
        val result = buildRuleSet(
            enabledSourceIds = setOf(SkipSource.BUILTIN.id),
            sourceRules = emptyList(),
            customJson = listOf("这不是 JSON", good, "{\"缺少字段\":1}"),
        )
        assertTrue("合法规则应保留", result.any { it.id == "custom_ok" })
    }

    @Test
    fun `空输入返回空集合`() {
        assertTrue(
            buildRuleSet(
                enabledSourceIds = setOf(SkipSource.BUILTIN.id),
                sourceRules = emptyList(),
                customJson = emptyList(),
            ).isEmpty()
        )
    }
}

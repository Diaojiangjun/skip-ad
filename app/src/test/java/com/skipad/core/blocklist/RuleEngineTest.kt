package com.skipad.core.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则解析与匹配测试。
 */
class RuleEngineTest {

    // ---------- 解析 ----------

    @Test
    fun `解析 hosts 格式`() {
        val rules = RuleParser.parse(
            """
            # 注释行
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.example.com
            0.0.0.0   spaced.example.com
            """.trimIndent(),
            source = "test",
        )

        assertEquals(3, rules.size)
        assertTrue(rules.all { it.action == RuleAction.BLOCK })
        assertEquals("ads.example.com", rules[0].domain)
        assertEquals("tracker.example.com", rules[1].domain)
        // hosts 语义：同时作用于子域名
        assertTrue(rules[0].subdomains)
    }

    @Test
    fun `忽略非屏蔽地址的 hosts 行`() {
        val rules = RuleParser.parse("192.168.1.1 router.local\n0.0.0.0 ads.example.com")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules[0].domain)
    }

    @Test
    fun `解析 adblock 语法`() {
        val rules = RuleParser.parse(
            """
            ||ads.example.com^
            |http://exact.example.com^
            @@||allowed.com^
            /banner\d+/
            """.trimIndent()
        )

        assertEquals(4, rules.size)

        // ||domain^ 匹配子域名
        assertEquals("ads.example.com", rules[0].domain)
        assertTrue(rules[0].subdomains)

        // |http://...^ 精确匹配
        assertEquals("exact.example.com", rules[1].domain)
        assertFalse(rules[1].subdomains)

        // @@ 为例外规则
        assertEquals(RuleAction.ALLOW, rules[2].action)
        assertEquals("allowed.com", rules[2].domain)

        // 正则规则
        assertTrue(rules[3].isRegex)
        assertTrue(rules[3].regex!!.containsMatchIn("promo_banner1"))
    }

    @Test
    fun `adblock 选项部分被忽略`() {
        val rules = RuleParser.parse("||ads.example.com^\$third-party,script")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules[0].domain)
    }

    @Test
    fun `空行与注释被忽略`() {
        val rules = RuleParser.parse("\n\n# 只有注释\n! 也算注释\n0.0.0.0 real.example.com")
        assertEquals(1, rules.size)
    }

    @Test
    fun `非法域名被拒绝`() {
        assertEquals(null, RuleParser.normalizeDomain(""))
        assertEquals(null, RuleParser.normalizeDomain("localhost"))
        assertEquals(null, RuleParser.normalizeDomain("nodot"))
        assertEquals(null, RuleParser.normalizeDomain("bad domain.com"))
        assertEquals("valid.example.com", RuleParser.normalizeDomain("Valid.Example.com"))
    }

    // ---------- 匹配 ----------

    @Test
    fun `hosts 规则拦截域名及其子域名`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        assertTrue(engine.isBlocked("ads.example.com"))
        assertTrue(engine.isBlocked("cdn.ads.example.com"))
        assertTrue(engine.isBlocked("a.b.ads.example.com"))
        assertFalse(engine.isBlocked("example.com"))
        assertFalse(engine.isBlocked("notads.example.com"))
        assertFalse(engine.isBlocked("ads.example.com.evil.com"))
    }

    @Test
    fun `精确规则不拦截子域名`() {
        val engine = BlocklistEngine()
        engine.replaceRules(
            listOf(Rule(RuleAction.BLOCK, "exact.example.com", subdomains = false))
        )

        assertTrue(engine.isBlocked("exact.example.com"))
        assertFalse(engine.isBlocked("sub.exact.example.com"))
    }

    @Test
    fun `例外规则优先于拦截规则`() {
        val engine = BlocklistEngine()
        engine.replaceRules(
            RuleParser.parse(
                """
                0.0.0.0 ads.example.com
                @@||ads.example.com^
                """.trimIndent()
            )
        )

        // 例外规则覆盖了整个域名树
        assertFalse(engine.isBlocked("ads.example.com"))
    }

    @Test
    fun `用户白名单放行指定域名`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))
        engine.setAllowList(listOf("ads.example.com"))

        assertFalse(engine.isBlocked("ads.example.com"))
        assertFalse(engine.isBlocked("sub.ads.example.com"))
    }

    @Test
    fun `白名单不影响其他规则`() {
        val engine = BlocklistEngine()
        engine.replaceRules(
            RuleParser.parse(
                """
                0.0.0.0 ads.example.com
                0.0.0.0 other.example.com
                """.trimIndent()
            )
        )
        engine.setAllowList(listOf("ads.example.com"))

        assertFalse(engine.isBlocked("ads.example.com"))
        assertTrue(engine.isBlocked("other.example.com"))
    }

    @Test
    fun `正则规则生效`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("/^ads[0-9]+\\.example\\.com$/"))

        assertTrue(engine.isBlocked("ads1.example.com"))
        assertTrue(engine.isBlocked("ads42.example.com"))
        assertFalse(engine.isBlocked("adsx.example.com"))
    }

    @Test
    fun `匹配结果区分拦截与放行`() {
        val engine = BlocklistEngine()
        engine.replaceRules(
            RuleParser.parse(
                """
                0.0.0.0 ads.example.com
                @@||ads.example.com^
                """.trimIndent()
            )
        )

        val result = engine.match("ads.example.com")
        assertTrue(result is MatchResult.Allowed)
    }

    @Test
    fun `未命中返回 NoMatch`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val result = engine.match("example.com")
        assertTrue(result is MatchResult.NoMatch)
    }

    @Test
    fun `拦截计数正确累加`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        engine.isBlocked("ads.example.com")
        engine.isBlocked("ads.example.com")
        engine.isBlocked("example.com")

        assertEquals(2L, engine.blockedTotal())
    }

    @Test
    fun `大列表性能可接受`() {
        val rules = buildString {
            for (i in 0 until 50_000) {
                appendLine("0.0.0.0 host$i.example.com")
            }
        }
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse(rules))

        assertEquals(50_000, engine.ruleCount)

        val start = System.currentTimeMillis()
        repeat(10_000) {
            engine.isBlocked("host${it % 50_000}.example.com")
        }
        val elapsed = System.currentTimeMillis() - start

        // 1 万次匹配应在数百毫秒内完成
        assertTrue("匹配耗时过长: ${elapsed}ms", elapsed < 2000)
    }
}

package com.skipad.core.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleParser] 的行为契约。
 *
 * 这些用例在优化解析性能后补齐：原实现每行都执行一次
 * `line.split(Regex("[ \t]+"))`（逐行编译正则）以及 `stripComment(...).trim()`，
 * 27 万行规则下耗时约 5 秒。改成手写下标扫描后必须保证语义完全一致，
 * 否则会出现「启动变快但漏规则」这种更糟的问题。
 */
class RuleParserTest {

    private fun parse(text: String): List<Rule> = RuleParser.parse(text, "test")

    @Test
    fun `hosts 单域名解析为后缀规则`() {
        val rules = parse("0.0.0.0 ads.example.com")
        assertEquals(1, rules.size)
        with(rules.first()) {
            assertEquals(RuleAction.BLOCK, action)
            assertEquals("ads.example.com", domain)
            assertTrue("hosts 语义应覆盖子域", subdomains)
            assertEquals("test", source)
        }
    }

    @Test
    fun `hosts 多域名只取第一个合法域名`() {
        // 历史行为：一行里出现多个域名时只取首个，不做多条规则
        val rules = parse("0.0.0.0 a.example.com b.example.com")
        assertEquals(1, rules.size)
        assertEquals("a.example.com", rules.first().domain)
    }

    @Test
    fun `hosts 首个域名非法时取下一个合法域名`() {
        // `bad` 不含点，被 normalizeDomain 拒绝，应继续取下一个 token
        val rules = parse("0.0.0.0 bad good.example.com")
        assertEquals(1, rules.size)
        assertEquals("good.example.com", rules.first().domain)
    }

    @Test
    fun `多种空白分隔都能正确切分`() {
        listOf(
            "0.0.0.0\ta.example.com",
            "0.0.0.0   a.example.com",
            "0.0.0.0 \t \t a.example.com",
            "   0.0.0.0 a.example.com   ",
        ).forEach { line ->
            val rules = parse(line)
            assertEquals("解析失败：$line", 1, rules.size)
            assertEquals("解析失败：$line", "a.example.com", rules.first().domain)
        }
    }

    @Test
    fun `非屏蔽地址被忽略`() {
        listOf("8.8.8.8 a.example.com", "192.168.1.1 a.example.com").forEach { line ->
            assertTrue("不应从「$line」得到规则", parse(line).isEmpty())
        }
    }

    @Test
    fun `支持的回环与未指定地址`() {
        listOf("0.0.0.0", "127.0.0.1", "::", "::1", "0::0").forEach { address ->
            val rules = parse("$address a.example.com")
            assertEquals("地址 $address 应被接受", 1, rules.size)
        }
    }

    @Test
    fun `行尾注释被剥离`() {
        assertEquals(1, parse("0.0.0.0 a.example.com # 广告").size)
        assertEquals(1, parse("0.0.0.0 a.example.com ! adblock 注释").size)
        assertEquals("a.example.com", parse("0.0.0.0 a.example.com#紧贴注释").first().domain)
    }

    @Test
    fun `纯注释与空行不产生规则`() {
        val rules = parse(
            """
            # 这是注释
            ! adblock 注释

               
            0.0.0.0 a.example.com
            """.trimIndent()
        )
        assertEquals(1, rules.size)
        assertEquals("a.example.com", rules.first().domain)
    }

    @Test
    fun `域名统一小写并去掉首尾点`() {
        assertEquals("a.example.com", parse("0.0.0.0 A.Example.COM").first().domain)
        assertEquals("a.example.com", parse("0.0.0.0 .a.example.com.").first().domain)
    }

    @Test
    fun `不含点的域名被拒绝`() {
        // 避免把 TLD 级别写成规则导致误伤
        assertTrue(parse("0.0.0.0 localhost").isEmpty())
        assertTrue(parse("0.0.0.0 com").isEmpty())
    }

    @Test
    fun `localhost 被拒绝`() {
        assertTrue(parse("0.0.0.0 localhost.localdomain").isEmpty())
    }

    @Test
    fun `非法字符导致整条域名被拒绝`() {
        assertTrue(parse("0.0.0.0 bad\$domain.com").isEmpty())
        assertTrue(parse("0.0.0.0 广告.example.com").isEmpty())
    }

    @Test
    fun `adblock 双竖线表示域名及其子域`() {
        val rules = parse("||ads.example.com^")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules.first().domain)
        assertTrue(rules.first().subdomains)
    }

    @Test
    fun `adblock 单竖线表示精确匹配`() {
        val rules = parse("|http://exact.example.com^")
        assertEquals(1, rules.size)
        assertEquals("exact.example.com", rules.first().domain)
        assertEquals("单竖线不应覆盖子域", false, rules.first().subdomains)
    }

    @Test
    fun `adblock 例外规则解析为放行`() {
        val rules = parse("@@||allowed.example.com^")
        assertEquals(1, rules.size)
        assertEquals(RuleAction.ALLOW, rules.first().action)
        assertEquals("allowed.example.com", rules.first().domain)
    }

    @Test
    fun `adblock 选项部分被剥离`() {
        val rules = parse("||ads.example.com^\$third-party,script")
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules.first().domain)
    }

    @Test
    fun `正则规则被编译`() {
        val rules = parse("/^ads[0-9]+\\.example\\.com$/")
        assertEquals(1, rules.size)
        assertTrue(rules.first().isRegex)
        assertTrue(rules.first().matches("ads12.example.com"))
    }

    @Test
    fun `正则规则数量受预算限制`() {
        RuleParser.resetBudget()
        val text = buildString {
            repeat(RuleParser.MAX_REGEX_RULES + 100) {
                appendLine("/^ads$it\\.example\\.com$/")
            }
        }
        val rules = parse(text)
        assertEquals(
            "超出预算的正则应被丢弃",
            RuleParser.MAX_REGEX_RULES,
            rules.count { it.isRegex },
        )
        RuleParser.resetBudget()
    }

    @Test
    fun `normalizeDomain 对外行为不变`() {
        assertEquals("a.example.com", RuleParser.normalizeDomain(" A.Example.COM. "))
        assertEquals("a.example.com", RuleParser.normalizeDomain("..a.example.com"))
        assertNull(RuleParser.normalizeDomain(""))
        assertNull(RuleParser.normalizeDomain("   "))
        assertNull(RuleParser.normalizeDomain("com"))
        assertNull(RuleParser.normalizeDomain("bad domain"))
        assertNull(RuleParser.normalizeDomain("a".repeat(250) + ".com"))
    }
}

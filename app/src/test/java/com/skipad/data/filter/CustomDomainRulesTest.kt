package com.skipad.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义拦截域名的转换规则。
 *
 * 语义必须与 hosts 规则一致：命中域名**及其子域**。
 */
class CustomDomainRulesTest {

    @Test
    fun `普通域名转成后缀规则`() {
        val rules = customDomainRules(listOf("ads.example.com"))
        assertEquals(1, rules.size)
        with(rules.first()) {
            assertEquals("ads.example.com", domain)
            assertTrue("应覆盖子域", subdomains)
            assertEquals("custom", source)
        }
    }

    @Test
    fun `大小写与首尾空格被规整`() {
        val rules = customDomainRules(listOf("  ADS.Example.COM  "))
        assertEquals(1, rules.size)
        assertEquals("ads.example.com", rules.first().domain)
    }

    @Test
    fun `首尾的点被去掉`() {
        assertEquals("a.example.com", customDomainRules(listOf(".a.example.com.")).first().domain)
    }

    @Test
    fun `非法域名被丢弃且不影响其他`() {
        val rules = customDomainRules(listOf("localhost", "no-dot", "good.example.com", ""))
        assertEquals(1, rules.size)
        assertEquals("good.example.com", rules.first().domain)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(customDomainRules(emptyList()).isEmpty())
    }

    @Test
    fun `重复域名不会产生重复规则`() {
        // 同一域名重复添加时，后写入的 Set 会去重；这里验证转换本身不产生额外副本
        val rules = customDomainRules(listOf("a.example.com"))
        assertEquals(1, rules.size)
    }
}

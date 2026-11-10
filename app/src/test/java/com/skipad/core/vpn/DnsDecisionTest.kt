package com.skipad.core.vpn

import com.skipad.core.dns.DnsCodec
import com.skipad.core.dns.DnsFlags
import com.skipad.core.dns.DnsMessage
import com.skipad.core.dns.DnsQuestion
import com.skipad.core.dns.DnsType
import com.skipad.data.prefs.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拦截判定的语义。
 *
 * 这段判定此前有两份实现（运行路径一份、测试内联复写一份），
 * 测试全绿却没覆盖到真正跑的代码。现在统一到 [DnsDecision]。
 */
class DnsDecisionTest {

    private fun query(domain: String): DnsMessage = DnsMessage(
        id = 0x1234,
        flags = DnsFlags(isResponse = false, recursionDesired = true),
        questions = listOf(DnsQuestion(domain, DnsType.A, 1)),
    )

    @Test
    fun `未命中规则时放行`() {
        val action = DnsDecision.decide(query("ads.example.com"), blocked = false, BlockMode.BLOCK)
        assertTrue(action is DnsAction.Forward)
        assertEquals("ads.example.com", (action as DnsAction.Forward).domain)
    }

    @Test
    fun `命中规则且为拦截模式时直接应答`() {
        val action = DnsDecision.decide(query("ads.example.com"), blocked = true, BlockMode.BLOCK)
        assertTrue(action is DnsAction.Block)
        assertEquals(BlockMode.BLOCK, (action as DnsAction.Block).mode)
    }

    @Test
    fun `命中规则但为监控模式时只记录`() {
        val action = DnsDecision.decide(query("ads.example.com"), blocked = true, BlockMode.MONITOR)
        assertTrue("监控模式不应直接应答", action is DnsAction.Record)
    }

    @Test
    fun `NXDOMAIN 模式同样走拦截分支`() {
        val action = DnsDecision.decide(query("ads.example.com"), blocked = true, BlockMode.NXDOMAIN)
        assertTrue(action is DnsAction.Block)
        assertEquals(BlockMode.NXDOMAIN, (action as DnsAction.Block).mode)
    }

    @Test
    fun `拦截模式返回空地址应答`() {
        val request = query("ads.example.com")
        val response = DnsDecision.buildBlockedResponse(request, BlockMode.BLOCK)
        val answers = response.answers
        assertTrue("应有一条空地址应答", answers.isNotEmpty())
        assertTrue("应答地址应全为 0", answers.all { rec -> rec.data.all { b -> b == 0.toByte() } })
    }

    @Test
    fun `NXDOMAIN 模式返回域名不存在`() {
        val request = query("ads.example.com")
        val response = DnsDecision.buildBlockedResponse(request, BlockMode.NXDOMAIN)
        assertEquals(com.skipad.core.dns.DnsRcode.NXDOMAIN, response.flags.rcode)
    }

    @Test
    fun `应答可被标准解析器还原`() {
        val request = query("ads.example.com")
        val bytes = DnsCodec.encode(DnsDecision.buildBlockedResponse(request, BlockMode.BLOCK))
        val decoded = DnsCodec.decode(bytes)!!
        assertEquals("ads.example.com", decoded.queryName)
    }
}

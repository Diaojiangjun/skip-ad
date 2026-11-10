package com.skipad.core.vpn

import com.skipad.core.blocklist.BlocklistEngine
import com.skipad.core.blocklist.RuleParser
import com.skipad.core.dns.DnsCodec
import com.skipad.core.dns.DnsFlags
import com.skipad.core.dns.DnsMessage
import com.skipad.core.dns.DnsQuestion
import com.skipad.core.dns.DnsType
import com.skipad.core.net.IpPacket
import com.skipad.core.net.PacketCodec
import com.skipad.data.prefs.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拦截链路的集成测试：构造查询 → 判定 → 生成应答 → 封装回 IP 报文。
 */
class DnsInterceptionTest {

    private val clientIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 50)
    private val proxyIp = byteArrayOf(10, 111, 222.toByte(), 1)

    /**
     * 模拟一次完整的 DNS 查询处理，返回将要写回 TUN 的应答 IP 报文。
     */
    private fun processQuery(
        engine: BlocklistEngine,
        domain: String,
        mode: BlockMode = BlockMode.BLOCK,
    ): ByteArray {
        val request = DnsMessage(
            id = 0x7788,
            flags = DnsFlags(isResponse = false, recursionDesired = true),
            questions = listOf(DnsQuestion(domain, DnsType.A, 1)),
        )
        val dnsBytes = DnsCodec.encode(request)

        // 模拟请求方发出的 IP 报文
        val queryPacket = PacketCodec.buildIpv4Udp(
            sourceAddress = clientIp,
            destinationAddress = proxyIp,
            sourcePort = 54321,
            destinationPort = 53,
            payload = dnsBytes,
        )

        val ipPacket: IpPacket = PacketCodec.parseIp(queryPacket)!!
        val udp = PacketCodec.parseUdp(ipPacket.payload)!!

        val decodedRequest = DnsCodec.decode(udp.payload)!!

        // 走与运行路径完全相同的判定实现，避免测试自己写一份逻辑
        val response = when (
            val action = DnsDecision.decide(
                decodedRequest,
                engine.isBlocked(decodedRequest.queryName!!),
                mode,
            )
        ) {
            is DnsAction.Block -> DnsDecision.buildBlockedResponse(decodedRequest, action.mode)
            is DnsAction.Record -> null
            is DnsAction.Forward -> null
        }

        val payload = response?.let { DnsCodec.encode(it) } ?: return ByteArray(0)
        return DnsPacketBuilder.buildResponse(ipPacket, udp, payload)
    }

    @Test
    fun `命中规则的查询返回空地址`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val responsePacket = processQuery(engine, "ads.example.com")
        assertTrue(responsePacket.isNotEmpty())

        val ip = PacketCodec.parseIp(responsePacket)!!
        // 源/目的地址应与请求相反
        assertTrue(ip.sourceAddress.contentEquals(proxyIp))
        assertTrue(ip.destinationAddress.contentEquals(clientIp))

        val udp = PacketCodec.parseUdp(ip.payload)!!
        assertEquals(53, udp.sourcePort)
        assertEquals(54321, udp.destinationPort)

        val dns = DnsCodec.decode(udp.payload)!!
        assertTrue(dns.flags.isResponse)
        assertEquals(1, dns.answers.size)
        assertTrue(dns.answers[0].data.all { it == 0.toByte() })
    }

    @Test
    fun `NXDOMAIN 模式返回域名不存在`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val responsePacket = processQuery(engine, "ads.example.com", BlockMode.NXDOMAIN)
        val ip = PacketCodec.parseIp(responsePacket)!!
        val udp = PacketCodec.parseUdp(ip.payload)!!
        val dns = DnsCodec.decode(udp.payload)!!

        assertEquals(com.skipad.core.dns.DnsRcode.NXDOMAIN, dns.flags.rcode)
        assertTrue(dns.answers.isEmpty())
    }

    @Test
    fun `未命中规则的查询不产生拦截应答`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val responsePacket = processQuery(engine, "normal.example.com")
        assertTrue(responsePacket.isEmpty())
    }

    @Test
    fun `子域名同样被拦截`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val responsePacket = processQuery(engine, "cdn.ads.example.com")
        assertTrue(responsePacket.isNotEmpty())
    }

    @Test
    fun `应答报文可通过标准解析器还原`() {
        val engine = BlocklistEngine()
        engine.replaceRules(RuleParser.parse("0.0.0.0 ads.example.com"))

        val responsePacket = processQuery(engine, "ads.example.com")
        val ip = PacketCodec.parseIp(responsePacket)!!
        val udp = PacketCodec.parseUdp(ip.payload)!!
        val dns = DnsCodec.decode(udp.payload)!!

        assertEquals(0x7788, dns.id)
        assertEquals("ads.example.com", dns.questions[0].name)
        assertEquals("ads.example.com", dns.answers[0].name)
    }
}

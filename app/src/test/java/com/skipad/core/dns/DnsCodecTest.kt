package com.skipad.core.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DNS 编解码测试。
 */
class DnsCodecTest {

    @Test
    fun `编解码往返一致`() {
        val original = DnsMessage(
            id = 0x1234,
            flags = DnsFlags(isResponse = false, recursionDesired = true),
            questions = listOf(DnsQuestion("ads.example.com", DnsType.A, 1)),
        )

        val decoded = DnsCodec.decode(DnsCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(0x1234, decoded!!.id)
        assertEquals(1, decoded.questions.size)
        assertEquals("ads.example.com", decoded.questions[0].name)
        assertEquals(DnsType.A, decoded.questions[0].type)
        assertEquals(1, decoded.questions[0].clazz)
        assertTrue(!decoded.flags.isResponse)
        assertTrue(decoded.flags.recursionDesired)
    }

    @Test
    fun `响应标志位正确置位`() {
        val original = DnsMessage(
            id = 0x0001,
            flags = DnsFlags(
                isResponse = true,
                opcode = DnsOpcode.QUERY,
                recursionDesired = true,
                recursionAvailable = true,
                rcode = DnsRcode.NXDOMAIN,
            ),
            questions = listOf(DnsQuestion("a.test", DnsType.A)),
        )

        val decoded = DnsCodec.decode(DnsCodec.encode(original))!!

        assertTrue(decoded.flags.isResponse)
        assertTrue(decoded.flags.recursionAvailable)
        assertEquals(DnsRcode.NXDOMAIN, decoded.flags.rcode)
    }

    @Test
    fun `解析压缩指针指向的域名`() {
        // 构造一个标准的压缩应答：
        // 偏移 0..11  头部：QDCOUNT=1, ANCOUNT=1
        // 偏移 12..28 问题名：完整域名 ads.example.com
        // 偏移 29..32 问题类型与类别
        // 偏移 33..34 答案名：压缩指针 C0 0C，回指偏移 12 的问题名
        // 偏移 35..48 答案记录
        val bytes = byteArrayOf(
            0x12, 0x34.toByte(), 0x81.toByte(), 0x80.toByte(),
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            // 偏移 12: 完整域名 ads.example.com
            0x03, 0x61, 0x64, 0x73, 0x07, 0x65, 0x78, 0x61,
            0x6D, 0x70, 0x6C, 0x65, 0x03, 0x63, 0x6F, 0x6D,
            0x00,
            // 偏移 29: QTYPE=A, QCLASS=IN
            0x00, 0x01, 0x00, 0x01,
            // 偏移 33: 答案名为压缩指针 C0 0C
            0xC0.toByte(), 0x0C,
            // 偏移 35: TYPE=A, CLASS=IN, TTL=60, RDLENGTH=4
            0x00, 0x01, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C, 0x00, 0x04,
            0x00, 0x00, 0x00, 0x00,
        )

        val decoded = DnsCodec.decode(bytes)

        assertNotNull(decoded)
        assertEquals("ads.example.com", decoded!!.questions[0].name)
        assertEquals(1, decoded.answers.size)
        // 答案名通过压缩指针回指问题名，应还原出同样的域名
        assertEquals("ads.example.com", decoded.answers[0].name)
        assertEquals(DnsType.A, decoded.answers[0].type)
        assertEquals(60, decoded.answers[0].ttl)
        assertEquals(4, decoded.answers[0].data.size)
    }

    @Test
    fun `自引用压缩指针不会造成死循环`() {
        // 偏移 12 处是指向自身的指针，应被判定为非法而返回 null
        val bytes = byteArrayOf(
            0x12, 0x34.toByte(), 0x01, 0x00,
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xC0.toByte(), 0x0C,
            0x00, 0x01, 0x00, 0x01,
        )

        assertNull(DnsCodec.decode(bytes))
    }

    @Test
    fun `拦截响应把 A 记录指向零地址`() {
        val request = DnsMessage(
            id = 0x4242,
            flags = DnsFlags(isResponse = false, recursionDesired = true),
            questions = listOf(DnsQuestion("tracker.example.com", DnsType.A, 1)),
        )

        val response = request.toBlockedResponse(request)
        val decoded = DnsCodec.decode(DnsCodec.encode(response))!!

        assertTrue(decoded.flags.isResponse)
        assertEquals(DnsRcode.NOERROR, decoded.flags.rcode)
        assertEquals(1, decoded.answers.size)
        assertEquals(DnsType.A, decoded.answers[0].type)
        // 地址必须全为 0
        assertTrue(decoded.answers[0].data.all { it == 0.toByte() })
        // 问题区需原样回显
        assertEquals("tracker.example.com", decoded.questions[0].name)
    }

    @Test
    fun `拦截响应把 AAAA 记录指向全零地址`() {
        val request = DnsMessage(
            id = 1,
            flags = DnsFlags(),
            questions = listOf(DnsQuestion("v6.example.com", DnsType.AAAA, 1)),
        )

        val response = request.toBlockedResponse(request)
        val decoded = DnsCodec.decode(DnsCodec.encode(response))!!

        assertEquals(DnsType.AAAA, decoded.answers[0].type)
        assertEquals(16, decoded.answers[0].data.size)
        assertTrue(decoded.answers[0].data.all { it == 0.toByte() })
    }

    @Test
    fun `NXDOMAIN 错误响应`() {
        val request = DnsMessage(
            id = 9,
            flags = DnsFlags(),
            questions = listOf(DnsQuestion("x.example.com", DnsType.A)),
        )

        val response = request.toErrorResponse(request, DnsRcode.NXDOMAIN)
        val decoded = DnsCodec.decode(DnsCodec.encode(response))!!

        assertTrue(decoded.flags.isResponse)
        assertEquals(DnsRcode.NXDOMAIN, decoded.flags.rcode)
        assertTrue(decoded.answers.isEmpty())
    }

    @Test
    fun `EDNS0 OPT 记录被正确附加`() {
        val request = DnsMessage(
            id = 5,
            flags = DnsFlags(),
            questions = listOf(DnsQuestion("e.test", DnsType.A)),
        )

        val withEdns = DnsCodec.withEdns0(request, 1232)
        val decoded = DnsCodec.decode(DnsCodec.encode(withEdns))!!

        assertEquals(1, decoded.additionals.size)
        assertEquals(DnsType.OPT, decoded.additionals[0].type)
        // OPT 记录中 CLASS 字段承载 UDP 负载大小
        assertEquals(1232, decoded.additionals[0].clazz)
    }

    @Test
    fun `畸形报文返回 null 而非抛异常`() {
        assertNull(DnsCodec.decode(ByteArray(0)))
        assertNull(DnsCodec.decode(byteArrayOf(0x12)))
        // 声明了 1 个问题但没有实际问题内容
        assertNull(DnsCodec.decode(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `域名大小写归一化`() {
        val original = DnsMessage(
            id = 1,
            flags = DnsFlags(),
            questions = listOf(DnsQuestion("ADS.Example.COM", DnsType.A)),
        )

        val decoded = DnsCodec.decode(DnsCodec.encode(original))!!
        assertEquals("ads.example.com", decoded.questions[0].name)
    }
}

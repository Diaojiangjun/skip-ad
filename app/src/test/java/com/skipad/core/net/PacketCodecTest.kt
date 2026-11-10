package com.skipad.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IP / UDP 封包测试。
 */
class PacketCodecTest {

    private val clientIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 100)
    private val serverIp = byteArrayOf(10, 111, 222.toByte(), 1)

    @Test
    fun `构造并解析 IPv4 UDP 报文`() {
        val payload = "hello-dns".toByteArray()
        val packet = PacketCodec.buildIpv4Udp(
            sourceAddress = serverIp,
            destinationAddress = clientIp,
            sourcePort = 53,
            destinationPort = 45678,
            payload = payload,
        )

        val ip = PacketCodec.parseIp(packet)
        assertNotNull(ip)
        assertEquals(IpVersion.IPv4, ip!!.version)
        assertTrue(ip.isUdp)
        assertTrue(ip.sourceAddress.contentEquals(serverIp))
        assertTrue(ip.destinationAddress.contentEquals(clientIp))

        val udp = PacketCodec.parseUdp(ip.payload)
        assertNotNull(udp)
        assertEquals(53, udp!!.sourcePort)
        assertEquals(45678, udp.destinationPort)
        assertTrue(udp.payload.contentEquals(payload))
    }

    @Test
    fun `IPv4 头部校验和正确`() {
        val packet = PacketCodec.buildIpv4Udp(
            sourceAddress = serverIp,
            destinationAddress = clientIp,
            sourcePort = 53,
            destinationPort = 45678,
            payload = byteArrayOf(1, 2, 3),
        )

        // 校验和字段应使整个头部按 16 位求和后为 0xFFFF
        val header = packet.copyOfRange(0, 20)
        var sum = 0L
        var i = 0
        while (i + 1 < 20) {
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        assertEquals(0xFFFFL, sum)
    }

    @Test
    fun `构造并解析 IPv6 UDP 报文`() {
        val srcV6 = ByteArray(16).also {
            it[0] = 0x20.toByte()
            it[1] = 0x01
            it[15] = 1
        }
        val dstV6 = ByteArray(16).also {
            it[0] = 0x20.toByte()
            it[1] = 0x01
            it[15] = 2
        }
        val payload = byteArrayOf(9, 8, 7)

        val packet = PacketCodec.buildIpv6Udp(
            sourceAddress = srcV6,
            destinationAddress = dstV6,
            sourcePort = 53,
            destinationPort = 33333,
            payload = payload,
        )

        val ip = PacketCodec.parseIp(packet)
        assertNotNull(ip)
        assertEquals(IpVersion.IPv6, ip!!.version)
        assertTrue(ip.sourceAddress.contentEquals(srcV6))

        val udp = PacketCodec.parseUdp(ip.payload)
        assertNotNull(udp)
        assertEquals(53, udp!!.sourcePort)
        assertTrue(udp.payload.contentEquals(payload))
    }

    @Test
    fun `IPv6 UDP 校验和字段非零`() {
        val srcV6 = ByteArray(16).also { it[15] = 1 }
        val dstV6 = ByteArray(16).also { it[15] = 2 }

        val packet = PacketCodec.buildIpv6Udp(srcV6, dstV6, 53, 33333, byteArrayOf(1, 2, 3, 4))

        val checksum = ((packet[46].toInt() and 0xFF) shl 8) or (packet[47].toInt() and 0xFF)
        // IPv6 强制要求 UDP 校验和，因此不能为 0
        assertTrue("IPv6 UDP 校验和不应为 0", checksum != 0)
    }

    @Test
    fun `非 IP 报文返回 null`() {
        assertNull(PacketCodec.parseIp(byteArrayOf(0x00, 0x11, 0x22)))
        assertNull(PacketCodec.parseIp(ByteArray(0)))
    }

    @Test
    fun `非 UDP 协议不被当作 UDP 解析`() {
        val packet = ByteArray(20 + 8)
        packet[0] = 0x45
        packet[9] = IpProtocol.TCP.toByte()
        val ip = PacketCodec.parseIp(packet)!!
        assertTrue(!ip.isUdp)
    }

    @Test
    fun `IP 地址格式化`() {
        assertEquals("192.168.1.100", PacketCodec.formatIp(clientIp))
        assertEquals("10.111.222.1", PacketCodec.formatIp(serverIp))
    }

    @Test
    fun `IPv6 地址格式化按 RFC 5952 压缩零段`() {
        val v6 = ByteArray(16).also {
            it[0] = 0x20.toByte()
            it[1] = 0x01
            it[15] = 1
        }
        assertEquals("2001::1", PacketCodec.formatIp(v6))
    }

    @Test
    fun `IPv6 全零地址格式化为双冒号`() {
        assertEquals("::", PacketCodec.formatIp(ByteArray(16)))
    }

    @Test
    fun `UDP 长度不足时返回 null`() {
        assertNull(PacketCodec.parseUdp(ByteArray(4)))
    }
}

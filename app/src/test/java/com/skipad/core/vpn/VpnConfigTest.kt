package com.skipad.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VPN 地址配置的一致性测试。
 *
 * 这些断言守护一个关键设计：**代理 DNS 地址必须与 TUN 接口地址分离**。
 *
 * 原理：分配给 VPN 接口的地址会进入内核的 local 路由表，而 local 表的优先级
 * 高于自定义路由。若代理 DNS 地址与接口地址相同，发往它的查询会被本地投递，
 * 不会进入 TUN，拦截功能将完全失效。
 */
class VpnConfigTest {

    @Test
    fun `代理 DNS 地址与 TUN 接口地址不同`() {
        assertFalse(
            "代理 DNS 地址不能与 TUN 接口地址相同，否则查询不会进入隧道",
            VpnConfig.PROXY_DNS_V4 == VpnConfig.TUN_ADDRESS_V4
        )
        assertFalse(
            "代理 DNS 地址不能与 TUN 接口地址相同，否则查询不会进入隧道",
            VpnConfig.PROXY_DNS_V6 == VpnConfig.TUN_ADDRESS_V6
        )
    }

    @Test
    fun `代理 DNS 与 TUN 地址属于同一网段以便精确路由`() {
        // 两者需在同一 /24 内，这样 addRoute(/32) 才能精确覆盖代理地址
        val tunPrefix = VpnConfig.TUN_ADDRESS_V4.substringBeforeLast('.')
        val proxyPrefix = VpnConfig.PROXY_DNS_V4.substringBeforeLast('.')
        assertTrue(
            "代理 DNS 与 TUN 地址应位于同一 /24 网段",
            tunPrefix == proxyPrefix
        )
    }

    @Test
    fun `代理地址的字节表示与字符串形式一致`() {
        val expected = byteArrayOf(10, 111, 222.toByte(), 2)
        assertTrue(
            "IPv4 字节表示应与 ${VpnConfig.PROXY_DNS_V4} 对应",
            VpnConfig.PROXY_IPV4.contentEquals(expected)
        )
    }

    @Test
    fun `IPv6 代理地址字节表示正确`() {
        val expected = ByteArray(16).also {
            it[0] = 0xFD.toByte()
            it[15] = 2.toByte()
        }
        assertTrue(
            "IPv6 字节表示应与 ${VpnConfig.PROXY_DNS_V6} 对应",
            VpnConfig.PROXY_IPV6.contentEquals(expected)
        )
    }

    @Test
    fun `代理地址不是回环或广播地址`() {
        val octets = VpnConfig.PROXY_IPV4.map { it.toInt() and 0xFF }
        assertFalse("代理地址不能是回环地址", octets[0] == 127)
        assertFalse("代理地址不能是广播地址", octets == listOf(255, 255, 255, 255))
        assertFalse("代理地址不能是零地址", octets.all { it == 0 })
    }
}

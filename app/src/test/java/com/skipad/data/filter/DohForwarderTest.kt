package com.skipad.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoH 上游的地址判定与端点解析。
 *
 * 这里测的是「纯逻辑」部分；真正的 HTTPS 往返需要在联网环境验证。
 */
class DohForwarderTest {

    @Test
    fun `https 开头判定为 DoH`() {
        assertTrue(DohForwarder.isDohEndpoint("https://dns.alidns.com/dns-query"))
        assertTrue(DohForwarder.isDohEndpoint("HTTPS://doh.pub/dns-query"))
        assertTrue(DohForwarder.isDohEndpoint("  https://cloudflare-dns.com/dns-query  "))
    }

    @Test
    fun `IP 地址判定为明文 DNS`() {
        assertFalse(DohForwarder.isDohEndpoint("223.5.5.5"))
        assertFalse(DohForwarder.isDohEndpoint("1.1.1.1"))
        assertFalse(DohForwarder.isDohEndpoint(""))
    }

    @Test
    fun `取出端点主机名`() {
        assertEquals(
            "dns.alidns.com",
            DohForwarder.endpointHost("https://dns.alidns.com/dns-query"),
        )
        assertEquals(
            "doh.pub",
            DohForwarder.endpointHost("https://doh.pub/dns-query"),
        )
        assertEquals(
            "cloudflare-dns.com",
            DohForwarder.endpointHost("https://cloudflare-dns.com:443/dns-query"),
        )
    }

    @Test
    fun `非 DoH 地址取不出主机名`() {
        assertNull(DohForwarder.endpointHost("223.5.5.5"))
        assertNull(DohForwarder.endpointHost(""))
    }

    @Test
    fun `无法解析时返回空列表而不是抛异常`() {
        // 这个域名不存在，解析应当失败并返回空列表
        val ips = DohForwarder.resolveEndpoint("https://this-host-does-not-exist.invalid/dns-query")
        assertTrue(ips.isEmpty())
    }
}

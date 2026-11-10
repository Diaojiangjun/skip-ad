package com.skipad.data.filter

import com.skipad.core.vpn.DnsForwarder
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DNS over HTTPS（RFC 8484）上游转发器。
 *
 * 为什么需要它：
 * - 设备开启「私人 DNS（DoT）」时，系统的加密解析器优先级更高，
 *   本应用的 DNS 代理可能被绕过；
 * - 明文 UDP 查询会被沿途网络设备看到。
 *
 * 报文格式：请求体是原始 DNS 查询报文（`application/dns-message`），
 * 响应体是原始 DNS 应答报文，与明文 UDP 完全一致，隧道无需改动。
 *
 * ## 必须避开回环
 *
 * DoH 端点本身是个域名（如 `dns.alidns.com`），解析它需要 DNS；
 * 而本应用的 DNS 代理正是隧道 —— 若在隧道已建立后去解析，
 * 就会形成「查询 → 隧道 → DoH → 需要解析域名 → 查询」的无限递归。
 *
 * 解决办法：**在建立隧道之前**（此时系统 DNS 仍是正常的）把端点域名解析好，
 * 之后用固定 IP 直连，TLS 的 SNI 与证书校验仍按域名走。
 */
class DohForwarder(
    private val endpoint: String,
    /** 预解析到的 IP。解析不到时退回系统解析（可能失败，但不会无限递归）。 */
    private val bootstrapIps: List<String> = emptyList(),
) : DnsForwarder {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            if (bootstrapIps.isNotEmpty()) {
                dns(StaticDns(bootstrapIps))
            }
        }
        .build()

    override fun forward(query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(endpoint)
            .post(query.toRequestBody(DNS_MESSAGE_TYPE))
            .header("Accept", "application/dns-message")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /**
     * 只返回预置 IP 的解析器。
     */
    private class StaticDns(private val ips: List<String>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            ips.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
    }

    companion object {
        /** DoH 的报文媒体类型。 */
        private val DNS_MESSAGE_TYPE = "application/dns-message".toMediaType()

        /**
         * 判断一个上游地址是否应当走 DoH。
         *
         * 约定：`https://` 开头即为 DoH 端点，其余按明文 DNS 地址处理。
         */
        fun isDohEndpoint(address: String): Boolean =
            address.trim().startsWith("https://", ignoreCase = true)

        /**
         * 取出 DoH 端点的主机名。
         */
        fun endpointHost(endpoint: String): String? {
            val trimmed = endpoint.trim()
            if (!trimmed.contains("://")) return null
            return runCatching {
                val withoutScheme = trimmed.substringAfter("://")
                withoutScheme.substringBefore('/').substringBefore(':').ifBlank { null }
            }.getOrNull()
        }

        /**
         * 预解析 DoH 端点的主机名。
         *
         * 必须在**隧道建立之前**调用，否则解析请求会再次进入隧道形成回环。
         */
        fun resolveEndpoint(endpoint: String): List<String> {
            val host = endpointHost(endpoint) ?: return emptyList()
            return runCatching {
                InetAddress.getAllByName(host).map { it.hostAddress ?: it.toString() }
            }.getOrDefault(emptyList())
        }
    }
}

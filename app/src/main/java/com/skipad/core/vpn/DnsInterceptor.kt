package com.skipad.core.vpn

import com.skipad.core.dns.DnsCodec
import com.skipad.core.dns.DnsMessage
import com.skipad.core.dns.DnsType
import com.skipad.core.net.PacketCodec
import com.skipad.core.net.UdpDatagram
import com.skipad.data.prefs.BlockMode
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 上游 DNS 转发器。
 *
 * 使用 UDP 明文向指定的上游 DNS 服务器转发查询。
 * 出站 socket 必须 `protect()` 以避免流量被重新路由回 VPN 造成回环。
 */
class UpstreamResolver(
    private val upstreamAddress: String,
    private val protectSocket: (DatagramSocket) -> Unit,
) : DnsForwarder {

    private val socket = DatagramSocket().apply {
        soTimeout = UPSTREAM_TIMEOUT_MS
        protectSocket(this)
    }

    /**
     * 转发查询并返回响应报文。失败返回 null。
     */
    override fun forward(query: ByteArray): ByteArray? = forwardInternal(query, query.size)

    /**
     * 转发查询并返回响应报文。失败返回 null。
     */
    fun forwardInternal(query: ByteArray, queryLength: Int): ByteArray? {
        val server = try {
            InetAddress.getByName(upstreamAddress)
        } catch (e: Exception) {
            return null
        }

        val request = DatagramPacket(query, queryLength, server, 53)
        return try {
            socket.send(request)
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            buffer.copyOf(response.length)
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val UPSTREAM_TIMEOUT_MS = 5000
    }
}

/**
 * DNS 拦截处理器。
 *
 * 负责解析 TUN 收到的 UDP 报文、判断是否命中规则，并生成应答报文。
 * 本类不直接操作文件描述符，便于单元测试。
 */
/**
 * 管理 DNS 报文的构造与回写所需的辅助对象。
 */
object DnsPacketBuilder {

    /**
     * 构造一个 DNS 应答的 IP 报文，源/目的地址与请求相反。
     */
    fun buildResponse(
        requestPacket: com.skipad.core.net.IpPacket,
        requestUdp: UdpDatagram,
        dnsPayload: ByteArray,
    ): ByteArray {
        return when (requestPacket.version) {
            com.skipad.core.net.IpVersion.IPv4 -> PacketCodec.buildIpv4Udp(
                sourceAddress = requestPacket.destinationAddress,
                destinationAddress = requestPacket.sourceAddress,
                sourcePort = requestUdp.destinationPort,
                destinationPort = requestUdp.sourcePort,
                payload = dnsPayload,
            )

            com.skipad.core.net.IpVersion.IPv6 -> PacketCodec.buildIpv6Udp(
                sourceAddress = requestPacket.destinationAddress,
                destinationAddress = requestPacket.sourceAddress,
                sourcePort = requestUdp.destinationPort,
                destinationPort = requestUdp.sourcePort,
                payload = dnsPayload,
            )
        }
    }
}

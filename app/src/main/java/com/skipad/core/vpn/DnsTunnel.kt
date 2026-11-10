package com.skipad.core.vpn

import com.skipad.core.dns.DnsCodec
import com.skipad.core.dns.DnsMessage
import com.skipad.core.dns.DnsRcode
import com.skipad.core.net.IpPacket
import com.skipad.core.net.PacketCodec
import com.skipad.core.net.UdpDatagram
import com.skipad.data.prefs.BlockMode
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS sinkhole 隧道。
 *
 * 工作原理：
 * 1. VPN 只把发往本机 DNS 代理地址（[proxyIpv4]:53）的流量路由进 TUN，
 *    其余流量（网页、视频等）仍走系统默认路由，不受影响，也无需自建 TCP/IP 协议栈。
 * 2. 从 TUN 读出 UDP 报文，解析出 DNS 查询域名。
 * 3. 命中规则 → 直接构造 0.0.0.0 应答写回 TUN，请求方立即失败。
 * 4. 未命中 → 交给 [UpstreamResolver] 转发到真实 DNS，将应答写回 TUN。
 *
 * 线程模型：读取线程只负责收包与判定，真正的上游转发在独立的转发线程池中执行，
 * 避免一个慢速上游查询阻塞整条管道。
 */
class DnsTunnel(
    private val input: FileInputStream,
    private val output: FileOutputStream,
    private val proxyIpv4: ByteArray,
    private val proxyIpv6: ByteArray,
    private val upstreamResolver: DnsForwarder,
    private val isBlocked: (String) -> Boolean,
    private val blockModeProvider: () -> BlockMode,
    private val onBlocked: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private val readThread = Thread({ readLoop() }, "skipad-tun-read").apply { isDaemon = true }

    /** 写入 TUN 的锁，避免多线程并发写导致报文交错。 */
    private val writeLock = Any()

    private val forwardExecutor: ExecutorService = Executors.newFixedThreadPool(FORWARD_THREADS) { runnable ->
        Thread(runnable, "skipad-tun-forward").apply { isDaemon = true }
    }

    private val receivedCount = AtomicLong(0)
    private val forwardedCount = AtomicLong(0)
    private val blockedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    private val buffer = ByteArray(MAX_PACKET_SIZE)

    fun start() {
        if (running.compareAndSet(false, true)) {
            readThread.start()
        }
    }

    fun stop() {
        running.set(false)
        forwardExecutor.shutdownNow()
        runCatching { upstreamResolver.close() }
        // 关闭流会解除读取线程的阻塞
        runCatching { input.close() }
        runCatching { output.close() }
    }

    val stats: Stats
        get() = Stats(receivedCount.get(), forwardedCount.get(), blockedCount.get(), errorCount.get())

    /**
     * 隧道统计。
     *
     * @param received 收到的 DNS 查询总数（用于确认隧道确实在工作）
     * @param forwarded 转发到上游的查询数
     * @param blocked 命中规则被拦截的查询数
     * @param errors 处理异常数
     */
    data class Stats(
        val received: Long,
        val forwarded: Long,
        val blocked: Long,
        val errors: Long,
    )

    /**
     * TUN 读取循环。
     *
     * 依赖 TUN 文件描述符处于**阻塞模式**：无报文时 read() 挂起等待，
     * 有报文时返回长度。若 fd 被设为非阻塞，read() 会立即返回 0 而造成空转。
     */
    private fun readLoop() {
        var emptyReads = 0
        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running.get()) onError("TUN 读取失败: ${e.message}")
                break
            }

            if (length <= 0) {
                if (emptyReads == 0) {
                    onError("TUN 读取返回 $length（预期 >0），接口可能已关闭或处于非阻塞模式")
                }
                // 连续空读说明 fd 可能处于非阻塞模式或已被关闭，主动退出避免空转耗电
                if (++emptyReads > MAX_EMPTY_READS) {
                    onError("TUN 连续空读 ${MAX_EMPTY_READS} 次，停止读取线程")
                    break
                }
                continue
            }
            emptyReads = 0

            try {
                handlePacket(buffer, length)
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                onError("处理报文异常: ${e.message}")
            }
        }
    }

    private fun writeToTun(data: ByteArray) {
        synchronized(writeLock) {
            output.write(data)
            output.flush()
        }
    }

    /**
     * 处理一个从 TUN 读出的 IP 报文。
     */
    private fun handlePacket(data: ByteArray, length: Int) {
        val ipPacket = PacketCodec.parseIp(data, length) ?: return
        if (!ipPacket.isUdp) return

        val udp = PacketCodec.parseUdp(ipPacket.payload) ?: return
        if (udp.destinationPort != DNS_PORT) return

        // 只处理发往本代理地址的查询
        if (!isProxyAddress(ipPacket.destinationAddress)) return

        val request = DnsCodec.decode(udp.payload) ?: return
        val domain = request.queryName ?: return
        if (domain.isEmpty() || domain == ".") return

        receivedCount.incrementAndGet()

        val mode = blockModeProvider()
        // 判定与应答构造统一走 DnsDecision，保证运行路径与单元测试是同一份实现
        when (val action = DnsDecision.decide(request, isBlocked(domain), mode)) {
            is DnsAction.Block -> {
                val response = DnsDecision.buildBlockedResponse(request, action.mode)
                writeDnsResponse(ipPacket, udp, DnsCodec.encode(response))
                blockedCount.incrementAndGet()
                onBlocked(domain)
                return
            }

            is DnsAction.Record -> {
                // 监控模式：记录但不拦截
                blockedCount.incrementAndGet()
                onBlocked(domain)
            }

            is DnsAction.Forward -> Unit
        }

        // 放行：向上游转发，放到线程池避免阻塞读取线程
        forwardExecutor.execute {
            forwardToUpstream(ipPacket, udp, request)
        }
    }

    private fun forwardToUpstream(ipPacket: IpPacket, udp: UdpDatagram, request: DnsMessage) {
        try {
            val queryBytes = DnsCodec.encode(request)
            val upstreamResponse = upstreamResolver.forward(queryBytes)
            if (upstreamResponse == null) {
                errorCount.incrementAndGet()
                // 上游超时，回一个 SERVFAIL，避免请求方长时间等待
                val failResponse = request.toErrorResponse(request, DnsRcode.SERVFAIL)
                writeDnsResponse(ipPacket, udp, DnsCodec.encode(failResponse))
                return
            }
            writeDnsResponse(ipPacket, udp, upstreamResponse)
            forwardedCount.incrementAndGet()
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            onError("上游转发异常: ${e.message}")
        }
    }

    /**
     * 把 DNS 响应封装成 IP 报文写回 TUN。
     */
    private fun writeDnsResponse(ipPacket: IpPacket, udp: UdpDatagram, payload: ByteArray) {
        val packet = DnsPacketBuilder.buildResponse(ipPacket, udp, payload)
        writeToTun(packet)
    }

    private fun isProxyAddress(address: ByteArray): Boolean =
        address.contentEquals(proxyIpv4) || address.contentEquals(proxyIpv6)

    companion object {
        const val DNS_PORT = 53
        private const val MAX_PACKET_SIZE = 32767
        private const val FORWARD_THREADS = 8

        /** 连续空读次数上限，超出则判定 fd 异常并停止读取线程。 */
        private const val MAX_EMPTY_READS = 100
    }
}

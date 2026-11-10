package com.skipad.core.vpn

import com.skipad.core.dns.DnsMessage
import com.skipad.core.dns.DnsRcode
import com.skipad.data.prefs.BlockMode

/**
 * DNS 查询的处置结果。
 */
sealed interface DnsAction {
    /** 命中拦截规则，构造空地址应答。 */
    data class Block(val domain: String, val mode: BlockMode) : DnsAction

    /** 命中规则但处于监控模式：只记录，不改变应答。 */
    data class Record(val domain: String) : DnsAction

    /** 放行，向上游转发。 */
    data class Forward(val domain: String) : DnsAction
}

/**
 * DNS 拦截的判定与应答构造。
 *
 * 抽成独立对象的原因：这段逻辑原先有两份实现 ——
 * 一份在隧道运行路径里（`DnsTunnel.handlePacket`），一份在测试里内联复写。
 * 结果是「测试全绿但测的是测试自己的副本」，运行路径实际上没被覆盖。
 *
 * 现在运行路径与单元测试共用同一份实现。
 */
object DnsDecision {

    /**
     * 判定一次查询该如何处理。
     *
     * @param request 查询报文
     * @param blocked 该域名是否命中拦截规则
     * @param mode 拦截模式
     */
    fun decide(request: DnsMessage, blocked: Boolean, mode: BlockMode): DnsAction {
        val domain = request.queryName.orEmpty()
        if (!blocked) return DnsAction.Forward(domain)
        // 监控模式只记录，不改变应答
        return if (mode == BlockMode.MONITOR) {
            DnsAction.Record(domain)
        } else {
            DnsAction.Block(domain, mode)
        }
    }

    /**
     * 构造拦截应答报文。
     *
     * [BlockMode.MONITOR] 下不应调用本方法（会原样返回请求报文）。
     */
    fun buildBlockedResponse(request: DnsMessage, mode: BlockMode): DnsMessage = when (mode) {
        BlockMode.BLOCK -> request.toBlockedResponse(request)
        BlockMode.NXDOMAIN -> request.toErrorResponse(request, DnsRcode.NXDOMAIN)
        BlockMode.MONITOR -> request
    }
}

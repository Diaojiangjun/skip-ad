package com.skipad.core.vpn

/**
 * DNS 查询转发器。
 *
 * 抽象成接口是为了让「明文 UDP」与「DNS over HTTPS」两种上游共用同一条隧道代码路径：
 * 隧道只负责把查询交给转发器、把应答写回 TUN，不关心上游用的是什么协议。
 */
interface DnsForwarder {

    /**
     * 转发一次查询并返回原始应答报文。
     *
     * @return 失败时返回 null（调用方会回 SERVFAIL，避免请求方长时间等待）
     */
    fun forward(query: ByteArray): ByteArray?

    /** 释放资源。 */
    fun close() {}
}

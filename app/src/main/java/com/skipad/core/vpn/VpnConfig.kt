package com.skipad.core.vpn

/**
 * VPN 隧道的地址配置。
 *
 * 单独抽出以便于单元测试直接验证这些配置之间的约束关系。
 */
object VpnConfig {

    /**
     * TUN 接口自身持有的地址。系统视其为 VPN 侧的网络标识。
     */
    const val TUN_ADDRESS_V4 = "10.111.222.1"
    const val TUN_ADDRESS_V6 = "fd00::1"

    /**
     * 代理 DNS 的虚拟地址。
     *
     * **必须与 TUN 接口地址分离**。分配给 VPN 接口的地址会进入内核的 local 路由表，
     * 而 local 表优先级高于自定义路由：若代理 DNS 地址与接口地址相同，
     * 发往它的查询会被本地投递而非进入 TUN，拦截功能将完全失效。
     *
     * 因此这里使用一个未被分配、仅通过路由指向 TUN 的地址。
     */
    const val PROXY_DNS_V4 = "10.111.222.2"
    const val PROXY_DNS_V6 = "fd00::2"

    /** 代理 DNS 的 IPv4 字节表示。 */
    val PROXY_IPV4: ByteArray = byteArrayOf(10, 111, 222.toByte(), 2)

    /** 代理 DNS 的 IPv6 字节表示（fd00::2）。 */
    val PROXY_IPV6: ByteArray = ByteArray(16).also {
        it[0] = 0xFD.toByte()
        it[15] = 2.toByte()
    }
}

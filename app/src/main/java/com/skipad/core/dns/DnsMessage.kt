package com.skipad.core.dns

/**
 * DNS 资源记录类型。
 */
object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41
    const val HTTPS = 65
    const val ANY = 255
}

/**
 * DNS 响应码。
 */
object DnsRcode {
    const val NOERROR = 0
    const val FORMERR = 1
    const val SERVFAIL = 2
    const val NXDOMAIN = 3
    const val NOTIMP = 4
    const val REFUSED = 5
}

/**
 * DNS 操作码。
 */
object DnsOpcode {
    const val QUERY = 0
    const val IQUERY = 1
    const val STATUS = 2
    const val NOTIFY = 4
    const val UPDATE = 5
}

/**
 * DNS 报文的标志位。
 *
 * 位布局 (RFC 1035 §4.1.1):
 * ```
 * 0                               1                               2                               3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                               Header                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 */
data class DnsFlags(
    val isResponse: Boolean = false,
    val opcode: Int = DnsOpcode.QUERY,
    val authoritative: Boolean = false,
    val truncated: Boolean = false,
    val recursionDesired: Boolean = true,
    val recursionAvailable: Boolean = false,
    val reserved: Boolean = false,
    val authenticData: Boolean = false,
    val checkingDisabled: Boolean = false,
    val rcode: Int = DnsRcode.NOERROR,
) {
    fun toShort(): Int {
        var value = 0
        if (isResponse) value = value or 0x8000
        value = value or ((opcode and 0x0F) shl 11)
        if (authoritative) value = value or 0x0400
        if (truncated) value = value or 0x0200
        if (recursionDesired) value = value or 0x0100
        if (recursionAvailable) value = value or 0x0080
        if (reserved) value = value or 0x0040
        if (authenticData) value = value or 0x0020
        if (checkingDisabled) value = value or 0x0010
        value = value or (rcode and 0x0F)
        return value
    }

    companion object {
        fun fromShort(value: Int): DnsFlags = DnsFlags(
            isResponse = value and 0x8000 != 0,
            opcode = (value shr 11) and 0x0F,
            authoritative = value and 0x0400 != 0,
            truncated = value and 0x0200 != 0,
            recursionDesired = value and 0x0100 != 0,
            recursionAvailable = value and 0x0080 != 0,
            reserved = value and 0x0040 != 0,
            authenticData = value and 0x0020 != 0,
            checkingDisabled = value and 0x0010 != 0,
            rcode = value and 0x0F,
        )
    }
}

/**
 * DNS 报文的问答区条目。
 */
data class DnsQuestion(
    val name: String,
    val type: Int,
    val clazz: Int = 1,
) {
    /** QCLASS 1 = IN (Internet)。 */
    val isInternetClass: Boolean get() = clazz == 1
}

/**
 * 资源记录的通用表示。
 */
data class DnsRecord(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsRecord) return false
        return name == other.name && type == other.type && clazz == other.clazz &&
            ttl == other.ttl && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type
        result = 31 * result + clazz
        result = 31 * result + ttl
        result = 31 * result + data.contentHashCode()
        return result
    }
}

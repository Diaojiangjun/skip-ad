package com.skipad.core.net

/**
 * IP 协议版本。
 */
enum class IpVersion(val value: Int) {
    IPv4(4),
    IPv6(6);

    companion object {
        fun fromValue(value: Int): IpVersion? = when (value) {
            4 -> IPv4
            6 -> IPv6
            else -> null
        }
    }
}

/**
 * 传输层协议号。
 */
object IpProtocol {
    const val TCP = 6
    const val UDP = 17
    const val ICMP = 1
}

/**
 * 解析后的 IP 报文，保留原始字节以便原样回写。
 */
data class IpPacket(
    val version: IpVersion,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val protocol: Int,
    val payload: ByteArray,
    val headerLength: Int,
    /** 原始完整报文（含头部），用于转发时保持不变。 */
    val raw: ByteArray,
) {
    val isUdp: Boolean get() = protocol == IpProtocol.UDP

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpPacket) return false
        return version == other.version &&
            protocol == other.protocol &&
            sourceAddress.contentEquals(other.sourceAddress) &&
            destinationAddress.contentEquals(other.destinationAddress) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + protocol
        result = 31 * result + sourceAddress.contentHashCode()
        result = 31 * result + destinationAddress.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * 解析后的 UDP 报文。
 */
data class UdpDatagram(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpDatagram) return false
        return sourcePort == other.sourcePort &&
            destinationPort == other.destinationPort &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * IP / UDP 报文的解析与构造。
 */
object PacketCodec {

    /**
     * 解析 IP 报文。返回 null 表示不是可解析的 IP 报文。
     */
    fun parseIp(bytes: ByteArray, length: Int = bytes.size): IpPacket? {
        if (length < 1) return null
        val versionValue = (bytes[0].toInt() shr 4) and 0x0F
        val version = IpVersion.fromValue(versionValue) ?: return null

        return when (version) {
            IpVersion.IPv4 -> parseIpv4(bytes, length)
            IpVersion.IPv6 -> parseIpv6(bytes, length)
        }
    }

    private fun parseIpv4(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 20) return null
        val headerLength = (bytes[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || headerLength > length) return null

        val totalLength = readU16(bytes, 2)
        val protocol = bytes[9].toInt() and 0xFF
        val source = bytes.copyOfRange(12, 16)
        val destination = bytes.copyOfRange(16, 20)

        val end = if (totalLength in 1..length) totalLength else length
        val payload = bytes.copyOfRange(headerLength, end)

        return IpPacket(
            version = IpVersion.IPv4,
            sourceAddress = source,
            destinationAddress = destination,
            protocol = protocol,
            payload = payload,
            headerLength = headerLength,
            raw = bytes.copyOfRange(0, end),
        )
    }

    private fun parseIpv6(bytes: ByteArray, length: Int): IpPacket? {
        if (length < 40) return null
        val payloadLength = readU16(bytes, 4)
        val nextHeader = bytes[6].toInt() and 0xFF
        val source = bytes.copyOfRange(8, 24)
        val destination = bytes.copyOfRange(24, 40)

        val end = if (payloadLength in 1..(length - 40)) 40 + payloadLength else length
        val payload = bytes.copyOfRange(40, end)

        return IpPacket(
            version = IpVersion.IPv6,
            sourceAddress = source,
            destinationAddress = destination,
            protocol = nextHeader,
            payload = payload,
            headerLength = 40,
            raw = bytes.copyOfRange(0, end),
        )
    }

    /**
     * 解析 UDP 报文。
     */
    fun parseUdp(bytes: ByteArray, length: Int = bytes.size): UdpDatagram? {
        if (length < 8) return null
        val sourcePort = readU16(bytes, 0)
        val destinationPort = readU16(bytes, 2)
        val udpLength = readU16(bytes, 4)
        val end = if (udpLength in 8..length) udpLength else length
        val payload = bytes.copyOfRange(8, end)
        return UdpDatagram(sourcePort, destinationPort, payload)
    }

    /**
     * 构造 IPv4 + UDP 报文，源/目的地址互换（即"应答包"）。
     *
     * @param sourceAddress 源 IP（应答方）
     * @param destinationAddress 目的 IP（请求方）
     */
    fun buildIpv4Udp(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        identification: Int = 0,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val ipTotalLength = 20 + udpLength
        val packet = ByteArray(ipTotalLength)

        // IPv4 头部
        packet[0] = 0x45
        packet[1] = 0
        writeU16(packet, 2, ipTotalLength)
        writeU16(packet, 4, identification and 0xFFFF)
        writeU16(packet, 6, 0x4000) // Don't Fragment
        packet[8] = 64 // TTL
        packet[9] = IpProtocol.UDP.toByte()
        writeU16(packet, 10, 0) // 校验和稍后计算
        System.arraycopy(sourceAddress, 0, packet, 12, 4)
        System.arraycopy(destinationAddress, 0, packet, 16, 4)
        writeU16(packet, 10, checksum(packet, 0, 20))

        // UDP 头部
        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, destinationPort)
        writeU16(packet, 24, udpLength)
        writeU16(packet, 26, 0) // 校验和在 IPv4 中可选，置 0
        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    /**
     * 构造 IPv6 + UDP 报文。
     */
    fun buildIpv6Udp(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        hopLimit: Int = 64,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)

        // IPv6 头部
        packet[0] = 0x60
        packet[1] = 0
        packet[2] = 0
        packet[3] = 0
        writeU16(packet, 4, udpLength)
        packet[6] = IpProtocol.UDP.toByte()
        packet[7] = hopLimit.toByte()
        System.arraycopy(sourceAddress, 0, packet, 8, 16)
        System.arraycopy(destinationAddress, 0, packet, 24, 16)

        // UDP 头部
        writeU16(packet, 40, sourcePort)
        writeU16(packet, 42, destinationPort)
        writeU16(packet, 44, udpLength)
        writeU16(packet, 46, 0) // 校验和稍后计算
        System.arraycopy(payload, 0, packet, 48, payload.size)

        // IPv6 的 UDP 校验和是强制字段，必须计算。
        val pseudoHeader = ByteArray(40)
        System.arraycopy(sourceAddress, 0, pseudoHeader, 0, 16)
        System.arraycopy(destinationAddress, 0, pseudoHeader, 16, 16)
        writeInt32(pseudoHeader, 32, udpLength)
        pseudoHeader[39] = IpProtocol.UDP.toByte()

        val udpSegment = packet.copyOfRange(40, 40 + udpLength)
        val checksumValue = checksum(pseudoHeader, 0, pseudoHeader.size, udpSegment, 0, udpSegment.size)
        writeU16(packet, 46, checksumValue)

        return packet
    }

    /**
     * 计算 IP 头校验和（RFC 1071）。
     */
    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int =
        checksum(bytes, offset, length, null, 0, 0)

    private fun checksum(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        extra: ByteArray?,
        extraOffset: Int,
        extraLength: Int,
    ): Int {
        var sum = 0L

        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong() or
                (bytes[i + 1].toInt() and 0xFF).toLong()
            i += 2
        }
        if (i < end) {
            sum += (bytes[i].toInt() and 0xFF).toLong() shl 8
        }

        if (extra != null) {
            var j = extraOffset
            val extraEnd = extraOffset + extraLength
            while (j + 1 < extraEnd) {
                sum += ((extra[j].toInt() and 0xFF) shl 8).toLong() or
                    (extra[j + 1].toInt() and 0xFF).toLong()
                j += 2
            }
            if (j < extraEnd) {
                sum += (extra[j].toInt() and 0xFF).toLong() shl 8
            }
        }

        // 回卷进位 (RFC 1071 §2)
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (result == 0) 0xFFFF else result
    }

    /**
     * 将字节数组格式化为可读 IP 地址。
     */
    fun formatIp(bytes: ByteArray): String = when (bytes.size) {
        4 -> bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        16 -> {
            val groups = IntArray(8) { index ->
                ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
            }
            compressIpv6(groups)
        }
        else -> "?"
    }

    /**
     * 按 RFC 5952 格式化 IPv6 地址：使用最长零段压缩，十六进制小写且不带前导零。
     */
    private fun compressIpv6(groups: IntArray): String {
        // 找出最长的连续零段
        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < 8) {
            if (groups[index] == 0) {
                var length = 0
                while (index + length < 8 && groups[index + length] == 0) length++
                if (length > bestLength) {
                    bestLength = length
                    bestStart = index
                }
                index += length
            } else {
                index++
            }
        }

        // 零段不足 2 个时不做压缩
        if (bestLength < 2) {
            return groups.joinToString(":") { Integer.toHexString(it) }
        }

        val builder = StringBuilder()
        for (position in 0 until bestStart) {
            if (position > 0) builder.append(':')
            builder.append(Integer.toHexString(groups[position]))
        }
        builder.append("::")
        for (position in bestStart + bestLength until 8) {
            if (position > bestStart + bestLength) builder.append(':')
            builder.append(Integer.toHexString(groups[position]))
        }
        return builder.toString()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}

package com.skipad.core.dns

import java.io.ByteArrayOutputStream

/**
 * DNS 报文的内存表示。
 */
data class DnsMessage(
    val id: Int,
    val flags: DnsFlags,
    val questions: List<DnsQuestion> = emptyList(),
    val answers: List<DnsRecord> = emptyList(),
    val authorities: List<DnsRecord> = emptyList(),
    val additionals: List<DnsRecord> = emptyList(),
) {
    /**
     * 返回第一个查询的域名，若无查询则返回 null。
     */
    val queryName: String? get() = questions.firstOrNull()?.name

    /**
     * 返回第一个查询的类型。
     */
    val queryType: Int get() = questions.firstOrNull()?.type ?: DnsType.A

    /**
     * 构造一个用于拦截的响应：把 A/AAAA 查询解析到 0.0.0.0 / ::。
     *
     * 使用 0.0.0.0 而非 NXDOMAIN 的原因：部分 App 会缓存 NXDOMAIN 或对空应答做特殊处理，
     * 而指向空地址能让连接快速失败且不泄漏域名存在性。
     */
    fun toBlockedResponse(request: DnsMessage, ttlSeconds: Int = 120): DnsMessage {
        val answers = request.questions.map { question ->
            val address = when (question.type) {
                DnsType.AAAA -> ByteArray(16)
                else -> ByteArray(4)
            }
            DnsRecord(
                name = question.name,
                type = question.type,
                clazz = 1,
                ttl = ttlSeconds,
                data = address,
            )
        }
        return DnsMessage(
            id = request.id,
            flags = DnsFlags(
                isResponse = true,
                opcode = request.flags.opcode,
                authoritative = false,
                truncated = false,
                recursionDesired = request.flags.recursionDesired,
                recursionAvailable = true,
                rcode = DnsRcode.NOERROR,
            ),
            questions = request.questions,
            answers = answers,
        )
    }

    /**
     * 构造一个错误响应（例如 NXDOMAIN 或 SERVFAIL）。
     */
    fun toErrorResponse(request: DnsMessage, rcode: Int): DnsMessage = DnsMessage(
        id = request.id,
        flags = DnsFlags(
            isResponse = true,
            opcode = request.flags.opcode,
            recursionDesired = request.flags.recursionDesired,
            recursionAvailable = true,
            rcode = rcode,
        ),
        questions = request.questions,
    )
}

/**
 * DNS 报文编解码器。
 *
 * 全部使用手写字节操作，不依赖任何第三方 DNS 库，
 * 以便完全掌控压缩指针与 EDNS0 的处理细节。
 */
object DnsCodec {

    private const val HEADER_SIZE = 12
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_UDP_PAYLOAD = 4096

    /**
     * 解析 DNS 报文。解析失败时返回 null，由调用方决定如何处理畸形报文。
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): DnsMessage? {
        if (length < HEADER_SIZE) return null

        val id = readU16(bytes, 0)
        val flags = DnsFlags.fromShort(readU16(bytes, 2))
        val questionCount = readU16(bytes, 4)
        val answerCount = readU16(bytes, 6)
        val authorityCount = readU16(bytes, 8)
        val additionalCount = readU16(bytes, 10)

        var offset = HEADER_SIZE

        val questions = ArrayList<DnsQuestion>(questionCount)
        repeat(questionCount) {
            val name = readName(bytes, length, offset) ?: return null
            offset = name.nextOffset
            if (offset + 4 > length) return null
            val type = readU16(bytes, offset)
            val clazz = readU16(bytes, offset + 2)
            offset += 4
            questions += DnsQuestion(name.value, type, clazz)
        }

        val answers = readRecords(bytes, length, offset, answerCount) ?: return null
        val authorities = readRecords(bytes, length, answers.nextOffset, authorityCount) ?: return null
        val additionals = readRecords(bytes, length, authorities.nextOffset, additionalCount) ?: return null

        return DnsMessage(
            id = id,
            flags = flags,
            questions = questions,
            answers = answers.records,
            authorities = authorities.records,
            additionals = additionals.records,
        )
    }

    /**
     * 序列化 DNS 报文。域名不压缩输出，兼容性最好且报文体积可接受。
     */
    fun encode(message: DnsMessage): ByteArray {
        val out = ByteArrayOutputStream(512)
        writeU16(out, message.id and 0xFFFF)
        writeU16(out, message.flags.toShort())
        writeU16(out, message.questions.size)
        writeU16(out, message.answers.size)
        writeU16(out, message.authorities.size)
        writeU16(out, message.additionals.size)

        message.questions.forEach { question ->
            writeName(out, question.name)
            writeU16(out, question.type)
            writeU16(out, question.clazz)
        }
        message.answers.forEach { writeRecord(out, it) }
        message.authorities.forEach { writeRecord(out, it) }
        message.additionals.forEach { writeRecord(out, it) }

        return out.toByteArray()
    }

    /**
     * 构造携带 EDNS0 OPT 伪记录的查询，用于向上游声明更大的 UDP 缓冲区。
     */
    fun withEdns0(request: DnsMessage, udpPayloadSize: Int = MAX_UDP_PAYLOAD): DnsMessage {
        val opt = DnsRecord(
            name = "",
            type = DnsType.OPT,
            clazz = udpPayloadSize,
            ttl = 0,
            data = ByteArray(0),
        )
        return request.copy(additionals = request.additionals + opt)
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private data class NameResult(val value: String, val nextOffset: Int)

    private data class RecordResult(val records: List<DnsRecord>, val nextOffset: Int)

    /**
     * 读取域名，支持 RFC 1035 §4.1.4 的压缩指针。
     */
    private fun readName(bytes: ByteArray, limit: Int, offset: Int): NameResult? {
        val labels = ArrayList<String>(8)
        var current = offset
        var nextOffset = -1
        var jumps = 0
        var totalLength = 0

        while (true) {
            if (current >= limit) return null
            val length = bytes[current].toInt() and 0xFF

            when {
                length == 0 -> {
                    current++
                    if (nextOffset < 0) nextOffset = current
                    break
                }

                (length and 0xC0) == 0xC0 -> {
                    // 压缩指针：0b11xxxxxx + 14 位偏移
                    if (current + 1 >= limit) return null
                    val pointer = ((length and 0x3F) shl 8) or (bytes[current + 1].toInt() and 0xFF)
                    if (pointer >= limit) return null
                    if (nextOffset < 0) nextOffset = current + 2
                    if (++jumps > MAX_POINTER_JUMPS) return null
                    current = pointer
                }

                (length and 0xC0) != 0 -> {
                    // 保留的标签类型，视为非法
                    return null
                }

                else -> {
                    val labelLength = length
                    if (labelLength > MAX_LABEL_LENGTH) return null
                    if (current + 1 + labelLength > limit) return null
                    totalLength += labelLength + 1
                    if (totalLength > MAX_NAME_LENGTH) return null

                    val start = current + 1
                    labels += String(bytes, start, labelLength, Charsets.UTF_8).lowercase()
                    current = start + labelLength
                }
            }
        }

        val name = if (labels.isEmpty()) "." else labels.joinToString(".")
        return NameResult(name, nextOffset)
    }

    private fun readRecords(bytes: ByteArray, limit: Int, offset: Int, count: Int): RecordResult? {
        val records = ArrayList<DnsRecord>(count.coerceAtMost(16))
        var current = offset

        repeat(count) {
            val name = readName(bytes, limit, current) ?: return null
            current = name.nextOffset
            if (current + 10 > limit) return null

            val type = readU16(bytes, current)
            val clazz = readU16(bytes, current + 2)
            val ttl = readInt32(bytes, current + 4)
            val rdLength = readU16(bytes, current + 8)
            current += 10
            if (current + rdLength > limit) return null

            val data = bytes.copyOfRange(current, current + rdLength)
            current += rdLength
            records += DnsRecord(name.value, type, clazz, ttl, data)
        }
        return RecordResult(records, current)
    }

    private fun writeRecord(out: ByteArrayOutputStream, record: DnsRecord) {
        writeName(out, record.name)
        writeU16(out, record.type)
        writeU16(out, record.clazz)
        writeInt32(out, record.ttl)
        writeU16(out, record.data.size)
        out.write(record.data)
    }

    /**
     * 写出域名。不做压缩；根域写单个 0 字节。
     */
    private fun writeName(out: ByteArrayOutputStream, name: String) {
        if (name.isEmpty() || name == ".") {
            out.write(0)
            return
        }
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            val length = bytes.size.coerceAtMost(MAX_LABEL_LENGTH)
            out.write(length)
            out.write(bytes, 0, length)
        }
        out.write(0)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

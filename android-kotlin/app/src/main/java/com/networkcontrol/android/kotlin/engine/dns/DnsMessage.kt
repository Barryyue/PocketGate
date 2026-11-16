package com.networkcontrol.android.kotlin.engine.dns

import java.net.InetAddress

/** 一筆 A / AAAA 答案。 */
data class DnsRecord(val address: InetAddress, val ttlSeconds: Long)

/** DoH 用的最小 DNS 報文：建立查詢、解析 A / AAAA 答案（支援名稱壓縮與 CNAME 鏈）。 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val HEADER = 12

    /** RFC 8484 建議 DoH 查詢的 ID 設為 0，方便 HTTP 快取。 */
    fun buildQuery(name: String, type: Int, id: Int = 0): ByteArray {
        val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
        require(labels.isNotEmpty() && labels.all { it.length in 1..63 }) { "bad name" }
        val out = java.io.ByteArrayOutputStream(HEADER + name.length + 6)
        out.write(id ushr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) }
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(type ushr 8); out.write(type and 0xFF)
        out.write(0); out.write(CLASS_IN)
        return out.toByteArray()
    }

    /** 回應碼（RCODE）；格式錯誤時回傳 -1。 */
    fun rcode(message: ByteArray): Int = if (message.size < HEADER) -1 else message[3].toInt() and 0x0F

    fun parseAnswers(message: ByteArray, type: Int): List<DnsRecord> {
        if (message.size < HEADER) return emptyList()
        if ((message[2].toInt() and 0x80) == 0) return emptyList()
        val questions = u16(message, 4)
        val answers = u16(message, 6)
        var offset = HEADER
        repeat(questions) {
            offset = skipName(message, offset) ?: return emptyList()
            offset += 4
        }
        val result = ArrayList<DnsRecord>()
        repeat(answers) {
            offset = skipName(message, offset) ?: return result
            if (offset + 10 > message.size) return result
            val recordType = u16(message, offset)
            val klass = u16(message, offset + 2)
            val ttl = ((message[offset + 4].toLong() and 0xFF) shl 24) or
                ((message[offset + 5].toLong() and 0xFF) shl 16) or
                ((message[offset + 6].toLong() and 0xFF) shl 8) or
                (message[offset + 7].toLong() and 0xFF)
            val length = u16(message, offset + 8)
            offset += 10
            if (offset + length > message.size) return result
            val expected = if (type == TYPE_A) 4 else 16
            if (recordType == type && klass == CLASS_IN && length == expected) {
                result += DnsRecord(InetAddress.getByAddress(message.copyOfRange(offset, offset + length)), ttl)
            }
            offset += length
        }
        return result
    }

    private fun skipName(message: ByteArray, from: Int): Int? {
        var offset = from
        var jumps = 0
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                (length and 0xC0) == 0xC0 -> return if (offset + 2 <= message.size) offset + 2 else null
                length > 63 -> return null
                else -> {
                    offset += 1 + length
                    if (++jumps > 128) return null
                }
            }
        }
        return null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

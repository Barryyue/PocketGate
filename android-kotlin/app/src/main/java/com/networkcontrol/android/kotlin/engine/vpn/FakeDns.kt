package com.networkcontrol.android.kotlin.engine.vpn

import java.net.Inet4Address
import java.net.InetAddress

class FakeDnsPool(capacity: Int = 1 shl 17) {
    private val lock = Any()
    private val byName = HashMap<String, Int>()
    private val byIndex = arrayOfNulls<String>(capacity)
    private var next = 1

    fun allocate(name: String): InetAddress {
        require(name.isNotEmpty())
        synchronized(lock) {
            val existing = byName[name]
            if (existing != null) return toAddress(existing)
            var index = next
            next = if (next + 1 >= byIndex.size) 1 else next + 1
            byIndex[index]?.let { byName.remove(it) }
            byIndex[index] = name
            byName[name] = index
            return toAddress(index)
        }
    }

    fun tryGetName(address: InetAddress): String? {
        if (address !is Inet4Address) return null
        val value = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val index = (value - BASE).toInt()
        if (index <= 0 || index >= byIndex.size) return null
        synchronized(lock) { return byIndex[index] }
    }

    companion object {
        private val BASE_ADDRESS = InetAddress.getByName("198.18.0.0")
        private val BASE = BASE_ADDRESS.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        val DnsServer: InetAddress = InetAddress.getByName("10.111.222.2")
        val Ipv4Address: InetAddress = InetAddress.getByName("10.111.222.1")
        val Ipv6Address: InetAddress = InetAddress.getByName("fd00:6e63::1")

        fun contains(address: InetAddress): Boolean {
            if (address !is Inet4Address) return false
            val value = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            return value in BASE..(BASE + (1L shl 17) - 1)
        }

        private fun toAddress(index: Int): InetAddress {
            val value = BASE + index
            return InetAddress.getByAddress(
                byteArrayOf(
                    ((value ushr 24) and 0xFF).toByte(),
                    ((value ushr 16) and 0xFF).toByte(),
                    ((value ushr 8) and 0xFF).toByte(),
                    (value and 0xFF).toByte(),
                ),
            )
        }
    }
}

data class DnsQuery(
    val id: Int,
    val recursionDesired: Boolean,
    val name: String,
    val type: Int,
    val questionLength: Int,
)

object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val HEADER_LENGTH = 12
    private const val MAX_NAME_LENGTH = 253

    fun tryParseQuery(message: ByteArray): DnsQuery? {
        if (message.size < HEADER_LENGTH) return null
        val flags = readU16(message, 2)
        if ((flags and 0x8000) != 0) return null
        if (((flags ushr 11) and 0x0F) != 0) return null
        if (readU16(message, 4) == 0) return null

        var offset = HEADER_LENGTH
        val name = StringBuilder()
        while (true) {
            if (offset >= message.size) return null
            val length = message[offset++].toInt() and 0xFF
            if (length == 0) break
            if (length > 63 || offset + length > message.size || name.length + length + 1 > MAX_NAME_LENGTH) {
                return null
            }
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until length) {
                val b = message[offset + i].toInt() and 0xFF
                name.append(if (b in 'A'.code..'Z'.code) (b + 32).toChar() else b.toChar())
            }
            offset += length
        }

        if (offset + 4 > message.size) return null
        val type = readU16(message, offset)
        val questionClass = readU16(message, offset + 2)
        if (questionClass != CLASS_IN) return null
        offset += 4
        return DnsQuery(
            id = readU16(message, 0),
            recursionDesired = (flags and 0x0100) != 0,
            name = name.toString(),
            type = type,
            questionLength = offset - HEADER_LENGTH,
        )
    }

    fun buildResponse(query: DnsQuery, question: ByteArray, address: InetAddress?, ttlSeconds: Long): ByteArray {
        require(address == null || address is Inet4Address) { "只支援 IPv4 位址。" }
        val answerLength = if (address == null) 0 else 16
        val response = ByteArray(HEADER_LENGTH + question.size + answerLength)
        writeU16(response, 0, query.id)
        writeU16(response, 2, 0x8080 or if (query.recursionDesired) 0x0100 else 0)
        writeU16(response, 4, 1)
        writeU16(response, 6, if (address == null) 0 else 1)
        question.copyInto(response, HEADER_LENGTH)
        if (address != null) {
            var offset = HEADER_LENGTH + question.size
            writeU16(response, offset, 0xC00C)
            writeU16(response, offset + 2, TYPE_A)
            writeU16(response, offset + 4, CLASS_IN)
            writeU32(response, offset + 6, ttlSeconds)
            writeU16(response, offset + 10, 4)
            address.address.copyInto(response, offset + 12)
        }
        return response
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}

package com.networkcontrol.android.kotlin.engine.vpn

import com.networkcontrol.android.kotlin.core.model.L4Protocol
import java.net.InetAddress
import java.net.InetSocketAddress

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

data class PacketInfo(
    val protocol: L4Protocol,
    val source: InetSocketAddress,
    val destination: InetSocketAddress,
    val tcpFlags: Int = 0,
    val payloadOffset: Int = 0,
    val payloadLength: Int = 0,
    val tcpSequence: Long = 0,
) {
    val isConnectionAttempt: Boolean
        get() = when (protocol) {
            L4Protocol.Tcp -> (tcpFlags and (TcpFlags.SYN or TcpFlags.ACK)) == TcpFlags.SYN
            L4Protocol.Udp -> true
            else -> false
        }
}

object IpPacketParser {
    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val MAX_IPV6_EXTENSION_HEADERS = 8

    private const val NEXT_HOP_BY_HOP = 0
    private const val NEXT_ROUTING = 43
    private const val NEXT_FRAGMENT = 44
    private const val NEXT_AUTHENTICATION = 51
    private const val NEXT_DESTINATION = 60

    fun tryParse(packet: ByteArray, length: Int = packet.size): PacketInfo? {
        if (length <= 0 || packet.isEmpty()) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray, length: Int): PacketInfo? {
        if (length < IPV4_MIN_HEADER) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        val totalLength = readU16(packet, 2)
        if (headerLength < IPV4_MIN_HEADER || totalLength < headerLength || totalLength > length) return null
        val fragmentOffset = readU16(packet, 6) and 0x1FFF
        if (fragmentOffset != 0) return null

        val source = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val destination = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        return parseTransport(
            packet[9].toInt() and 0xFF,
            packet,
            headerLength,
            totalLength,
            source,
            destination,
        )
    }

    private fun parseIpv6(packet: ByteArray, length: Int): PacketInfo? {
        if (length < IPV6_HEADER) return null
        val payloadLength = readU16(packet, 4)
        if (IPV6_HEADER + payloadLength > length) return null

        val source = InetAddress.getByAddress(packet.copyOfRange(8, 24))
        val destination = InetAddress.getByAddress(packet.copyOfRange(24, 40))
        var nextHeader = packet[6].toInt() and 0xFF
        var offset = IPV6_HEADER
        val end = IPV6_HEADER + payloadLength

        repeat(MAX_IPV6_EXTENSION_HEADERS) {
            val headerLength = when (nextHeader) {
                NEXT_HOP_BY_HOP, NEXT_ROUTING, NEXT_DESTINATION -> {
                    if (offset + 8 > end) return null
                    (packet[offset + 1].toInt() + 1) * 8
                }
                NEXT_FRAGMENT -> {
                    if (offset + 8 > end) return null
                    if ((readU16(packet, offset + 2) ushr 3) != 0) return null
                    8
                }
                NEXT_AUTHENTICATION -> {
                    if (offset + 8 > end) return null
                    (packet[offset + 1].toInt() + 2) * 4
                }
                else -> return parseTransport(nextHeader, packet, offset, end, source, destination)
            }
            if (offset + headerLength > end) return null
            nextHeader = packet[offset].toInt() and 0xFF
            offset += headerLength
        }
        return null
    }

    private fun parseTransport(
        protocolNumber: Int,
        packet: ByteArray,
        segmentOffset: Int,
        segmentEnd: Int,
        source: InetAddress,
        destination: InetAddress,
    ): PacketInfo? {
        val protocol = L4Protocol.fromNumber(protocolNumber)
        val segmentLength = segmentEnd - segmentOffset
        var sourcePort = 0
        var destinationPort = 0
        var payloadOffset = 0
        var payloadLength = 0
        var sequence = 0L
        var flags = 0

        when (protocol) {
            L4Protocol.Tcp -> {
                if (segmentLength < 20) return null
                sourcePort = readU16(packet, segmentOffset)
                destinationPort = readU16(packet, segmentOffset + 2)
                sequence = readU32(packet, segmentOffset + 4)
                flags = packet[segmentOffset + 13].toInt() and 0x1F
            }
            L4Protocol.Udp -> {
                if (segmentLength < 8) return null
                sourcePort = readU16(packet, segmentOffset)
                destinationPort = readU16(packet, segmentOffset + 2)
                val udpLength = readU16(packet, segmentOffset + 4)
                if (udpLength < 8 || udpLength > segmentLength) return null
                payloadOffset = segmentOffset + 8
                payloadLength = udpLength - 8
            }
            else -> Unit
        }

        return PacketInfo(
            protocol = protocol,
            source = InetSocketAddress(source, sourcePort),
            destination = InetSocketAddress(destination, destinationPort),
            tcpFlags = flags,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            tcpSequence = sequence,
        )
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}

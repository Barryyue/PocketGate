package com.networkcontrol.android.kotlin.engine.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

object Ipv4 {
    fun isIpv4(address: InetAddress): Boolean = address is Inet4Address
}

object IpPacketBuilder {
    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20
    private const val ICMP_HEADER = 8
    private const val PROTOCOL_ICMP = 1
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17
    private const val PROTOCOL_ICMPV6 = 58
    private const val ICMPV4_QUOTE = 8
    private const val MAX_IPV6_PACKET = 1280

    fun udp4(source: InetSocketAddress, destination: InetSocketAddress, payload: ByteArray): ByteArray {
        require(source.address is Inet4Address && destination.address is Inet4Address) { "只支援 IPv4。" }
        val udpLength = UDP_HEADER + payload.size
        val totalLength = IPV4_HEADER + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeU16(packet, 2, totalLength)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = PROTOCOL_UDP.toByte()
        source.address.address.copyInto(packet, 12)
        destination.address.address.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER, 0))
        writeU16(packet, IPV4_HEADER, source.port)
        writeU16(packet, IPV4_HEADER + 2, destination.port)
        writeU16(packet, IPV4_HEADER + 4, udpLength)
        payload.copyInto(packet, IPV4_HEADER + UDP_HEADER)
        val addresses = packet.copyOfRange(12, 20)
        val sum = checksum(packet, IPV4_HEADER, udpLength, transportPseudoSum(addresses, PROTOCOL_UDP, udpLength))
        writeU16(packet, IPV4_HEADER + 6, if (sum == 0) 0xFFFF else sum)
        return packet
    }

    fun tcpReset(source: InetSocketAddress, destination: InetSocketAddress, acknowledgment: Long): ByteArray =
        when {
            source.address is Inet4Address && destination.address is Inet4Address ->
                tcpReset4(source, destination, acknowledgment)
            source.address is Inet6Address && destination.address is Inet6Address ->
                tcpReset6(source, destination, acknowledgment)
            else -> throw IllegalArgumentException("只支援 IPv4 或 IPv6。")
        }

    fun tcpReset4(source: InetSocketAddress, destination: InetSocketAddress, acknowledgment: Long): ByteArray {
        val packet = ByteArray(IPV4_HEADER + TCP_HEADER)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = PROTOCOL_TCP.toByte()
        source.address.address.copyInto(packet, 12)
        destination.address.address.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER, 0))
        writeTcpReset(packet, IPV4_HEADER, source.port, destination.port, acknowledgment)
        val addresses = packet.copyOfRange(12, 20)
        val sum = checksum(
            packet,
            IPV4_HEADER,
            TCP_HEADER,
            transportPseudoSum(addresses, PROTOCOL_TCP, TCP_HEADER),
        )
        writeU16(packet, IPV4_HEADER + 16, sum)
        return packet
    }

    fun tcpReset6(source: InetSocketAddress, destination: InetSocketAddress, acknowledgment: Long): ByteArray {
        val packet = ByteArray(IPV6_HEADER + TCP_HEADER)
        packet[0] = 0x60
        writeU16(packet, 4, TCP_HEADER)
        packet[6] = PROTOCOL_TCP.toByte()
        packet[7] = 64
        source.address.address.copyInto(packet, 8)
        destination.address.address.copyInto(packet, 24)
        writeTcpReset(packet, IPV6_HEADER, source.port, destination.port, acknowledgment)
        val addresses = packet.copyOfRange(8, 40)
        val sum = checksum(
            packet,
            IPV6_HEADER,
            TCP_HEADER,
            transportPseudoSum(addresses, PROTOCOL_TCP, TCP_HEADER),
        )
        writeU16(packet, IPV6_HEADER + 16, sum)
        return packet
    }

    fun icmpPortUnreachable(source: InetSocketAddress, destination: InetSocketAddress, original: ByteArray): ByteArray =
        when {
            source.address is Inet4Address && destination.address is Inet4Address ->
                icmpUnreachable4(source, destination, original)
            source.address is Inet6Address && destination.address is Inet6Address ->
                icmpUnreachable6(source, destination, original)
            else -> throw IllegalArgumentException("只支援 IPv4 或 IPv6。")
        }

    fun icmpUnreachable4(source: InetSocketAddress, destination: InetSocketAddress, original: ByteArray): ByteArray {
        val originalHeader = if (original.size >= IPV4_HEADER) {
            (original[0].toInt() and 0x0F) * 4
        } else {
            IPV4_HEADER
        }
        val quoteLength = minOf(original.size, maxOf(originalHeader, IPV4_HEADER) + ICMPV4_QUOTE)
        val icmpLength = ICMP_HEADER + quoteLength
        val packet = ByteArray(IPV4_HEADER + icmpLength)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = PROTOCOL_ICMP.toByte()
        source.address.address.copyInto(packet, 12)
        destination.address.address.copyInto(packet, 16)
        writeU16(packet, 10, checksum(packet, 0, IPV4_HEADER, 0))
        packet[IPV4_HEADER] = 3
        packet[IPV4_HEADER + 1] = 3
        original.copyInto(packet, IPV4_HEADER + ICMP_HEADER, 0, quoteLength)
        writeU16(packet, IPV4_HEADER + 2, checksum(packet, IPV4_HEADER, icmpLength, 0))
        return packet
    }

    fun icmpUnreachable6(source: InetSocketAddress, destination: InetSocketAddress, original: ByteArray): ByteArray {
        val quoteLength = minOf(original.size, MAX_IPV6_PACKET - IPV6_HEADER - ICMP_HEADER)
        val icmpLength = ICMP_HEADER + quoteLength
        val packet = ByteArray(IPV6_HEADER + icmpLength)
        packet[0] = 0x60
        writeU16(packet, 4, icmpLength)
        packet[6] = PROTOCOL_ICMPV6.toByte()
        packet[7] = 64
        source.address.address.copyInto(packet, 8)
        destination.address.address.copyInto(packet, 24)
        packet[IPV6_HEADER] = 1
        packet[IPV6_HEADER + 1] = 4
        original.copyInto(packet, IPV6_HEADER + ICMP_HEADER, 0, quoteLength)
        val addresses = packet.copyOfRange(8, 40)
        val sum = checksum(
            packet,
            IPV6_HEADER,
            icmpLength,
            transportPseudoSum(addresses, PROTOCOL_ICMPV6, icmpLength),
        )
        writeU16(packet, IPV6_HEADER + 2, sum)
        return packet
    }

    private fun writeTcpReset(
        packet: ByteArray,
        offset: Int,
        sourcePort: Int,
        destinationPort: Int,
        acknowledgment: Long,
    ) {
        writeU16(packet, offset, sourcePort)
        writeU16(packet, offset + 2, destinationPort)
        writeU32(packet, offset + 4, 0)
        writeU32(packet, offset + 8, acknowledgment)
        packet[offset + 12] = 0x50
        packet[offset + 13] = (TcpFlags.RST or TcpFlags.ACK).toByte()
        writeU16(packet, offset + 14, 0)
    }

    private fun transportPseudoSum(addresses: ByteArray, protocol: Int, length: Int): Int {
        var sum = 0
        var i = 0
        while (i + 1 < addresses.size) {
            sum += ((addresses[i].toInt() and 0xFF) shl 8) or (addresses[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < addresses.size) sum += (addresses[i].toInt() and 0xFF) shl 8
        sum += protocol
        sum += length
        return sum
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (bytes[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

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

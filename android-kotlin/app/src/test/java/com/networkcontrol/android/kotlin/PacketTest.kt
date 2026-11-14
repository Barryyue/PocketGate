package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.engine.vpn.DnsWire
import com.networkcontrol.android.kotlin.engine.vpn.FakeDnsPool
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketParser
import com.networkcontrol.android.kotlin.engine.vpn.TcpFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class PacketTest {
    private val v4Source = InetSocketAddress("10.111.222.2", 53)
    private val v4Destination = InetSocketAddress("10.111.222.1", 40000)
    private val v6Source = InetSocketAddress(InetAddress.getByName("fd00:6e63::1"), 443)
    private val v6Destination = InetSocketAddress(InetAddress.getByName("fd00:6e63::2"), 40000)

    @Test
    fun udpPacketRoundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = IpPacketBuilder.udp4(v4Source, v4Destination, payload)
        val info = IpPacketParser.tryParse(packet)
        assertNotNull(info)
        assertEquals(L4Protocol.Udp, info!!.protocol)
        assertEquals(v4Source, info.source)
        assertEquals(v4Destination, info.destination)
        assertEquals(payload.size, info.payloadLength)
    }

    @Test
    fun tcpReset4HasResetAckFlags() {
        val packet = IpPacketBuilder.tcpReset4(v4Source, v4Destination, 1234)
        val info = IpPacketParser.tryParse(packet)
        assertNotNull(info)
        assertEquals(L4Protocol.Tcp, info!!.protocol)
        assertEquals(v4Source, info.source)
        assertEquals(v4Destination, info.destination)
        assertTrue((info.tcpFlags and TcpFlags.RST) != 0)
        assertTrue((info.tcpFlags and TcpFlags.ACK) != 0)
    }

    @Test
    fun tcpReset6Parses() {
        val packet = IpPacketBuilder.tcpReset6(v6Source, v6Destination, 99)
        val info = IpPacketParser.tryParse(packet)
        assertNotNull(info)
        assertEquals(L4Protocol.Tcp, info!!.protocol)
        assertEquals(v6Source.port, info.source.port)
        assertEquals(v6Destination.port, info.destination.port)
    }

    @Test
    fun icmpUnreachable4Parses() {
        val original = IpPacketBuilder.udp4(v4Source, v4Destination, byteArrayOf(9, 9))
        val packet = IpPacketBuilder.icmpUnreachable4(v4Destination, v4Source, original)
        val info = IpPacketParser.tryParse(packet)
        assertNotNull(info)
        assertEquals(L4Protocol.Icmp, info!!.protocol)
    }

    @Test
    fun dnsQueryParsesAndResponseContainsAddress() {
        val query = buildQuery("example.com")
        val parsed = DnsWire.tryParseQuery(query)
        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.name)
        assertEquals(DnsWire.TYPE_A, parsed.type)

        val address = FakeDnsPool().allocate("example.com")
        val response = DnsWire.buildResponse(parsed, query.copyOfRange(12, 12 + parsed.questionLength), address, 1)
        assertTrue(response.size > query.size)
        assertTrue(response.takeLast(4).toByteArray().contentEquals(address.address))
    }

    @Test
    fun fakeDnsRoundTrips() {
        val pool = FakeDnsPool()
        val address = pool.allocate("api.example.com")
        assertEquals("api.example.com", pool.tryGetName(address))
        assertEquals(address, pool.allocate("api.example.com"))
    }

    private fun buildQuery(name: String): ByteArray {
        val labels = name.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val bytes = ByteArray(size)
        bytes[0] = 0x12
        bytes[1] = 0x34
        bytes[2] = 0x01
        bytes[3] = 0x00
        bytes[5] = 1
        var offset = 12
        labels.forEach { label ->
            bytes[offset++] = label.length.toByte()
            label.forEach { bytes[offset++] = it.code.toByte() }
        }
        bytes[offset++] = 0
        bytes[offset++] = 0
        bytes[offset++] = 1
        bytes[offset++] = 0
        bytes[offset] = 1
        return bytes
    }
}

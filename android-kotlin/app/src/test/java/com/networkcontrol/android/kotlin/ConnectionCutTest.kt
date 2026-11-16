package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketParser
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import com.networkcontrol.android.kotlin.engine.vpn.TcpFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ConnectionCutTest {
    private val client = InetSocketAddress("10.111.222.1", 41000)
    private val server = InetSocketAddress("100.64.0.9", 443)

    private class Writer : PacketWriter {
        val packets = mutableListOf<ByteArray>()
        override fun write(packet: ByteArray) {
            packets += packet
        }
    }

    private fun tcp(from: InetSocketAddress, to: InetSocketAddress, flags: Int, seq: Long, ack: Long): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45; p[3] = 40; p[6] = 0x40; p[8] = 64; p[9] = 6
        from.address.address.copyInto(p, 12)
        to.address.address.copyInto(p, 16)
        p[20] = (from.port ushr 8).toByte(); p[21] = from.port.toByte()
        p[22] = (to.port ushr 8).toByte(); p[23] = to.port.toByte()
        for (i in 0 until 4) {
            p[24 + i] = (seq ushr (24 - 8 * i)).toByte()
            p[28 + i] = (ack ushr (24 - 8 * i)).toByte()
        }
        p[32] = 0x50; p[33] = flags.toByte()
        return p
    }

    private fun u32(p: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (p[offset + i].toLong() and 0xFF) }

    private class Harness {
        var decision = ForwardingDecision.Forward
        val tun = Writer()
        val hev = Writer()
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10100 },
            decide = { _, _, _ -> decision },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
        ).also { it.toHev = hev }
    }

    @Test
    fun ruleChangeCutsEstablishedConnectionOnNextClientPacket() {
        val h = Harness()
        assertTrue(h.filter.onTunPacket(tcp(client, server, TcpFlags.SYN, 1000, 0).let { it }, 40, h.tun))
        val ack = tcp(client, server, TcpFlags.ACK, 1001, 5000)
        assertTrue(h.filter.onTunPacket(ack, ack.size, h.tun))

        h.decision = ForwardingDecision.Block
        h.filter.invalidate()
        val next = tcp(client, server, TcpFlags.ACK or TcpFlags.PSH, 1001, 5000)
        assertFalse(h.filter.onTunPacket(next, next.size, h.tun))

        // 給應用的 RST：伺服器 → 應用，序號 = 應用期待的下一個序號（它送出的 ACK）
        val toClient = h.tun.packets.single()
        val parsedClient = IpPacketParser.tryParse(toClient, toClient.size)!!
        assertEquals(server, parsedClient.source)
        assertEquals(client, parsedClient.destination)
        assertTrue((parsedClient.tcpFlags and TcpFlags.RST) != 0)
        assertEquals(5000L, u32(toClient, 24))
        // 給 hev 的 RST：應用 → 伺服器，序號 = 應用自己的序號
        val toHev = h.hev.packets.single()
        assertEquals(1001L, u32(toHev, 24))
        assertEquals(1L, h.filter.counters.connectionsCut.get())

        // 只切一次
        assertTrue(h.filter.onTunPacket(next, next.size, h.tun))
    }

    @Test
    fun cutHappensOnServerPacketToo() {
        val h = Harness()
        h.filter.onTunPacket(tcp(client, server, TcpFlags.SYN, 1000, 0), 40, h.tun)
        h.decision = ForwardingDecision.Blocklisted
        h.filter.invalidate()
        val fromServer = tcp(server, client, TcpFlags.ACK or TcpFlags.PSH, 7000, 1001)
        assertFalse(h.filter.onHevPacket(fromServer, fromServer.size, h.tun))
        assertEquals(7000L, u32(h.tun.packets.single(), 24))
        assertEquals(1001L, u32(h.hev.packets.single(), 24))
    }

    @Test
    fun stillAllowedConnectionsAreUntouchedAndResetForgetsFlows() {
        val h = Harness()
        h.filter.onTunPacket(tcp(client, server, TcpFlags.SYN, 1000, 0), 40, h.tun)
        h.filter.invalidate()
        val next = tcp(client, server, TcpFlags.ACK, 1001, 5000)
        assertTrue(h.filter.onTunPacket(next, next.size, h.tun))
        assertTrue(h.tun.packets.isEmpty())

        h.filter.reset()
        h.decision = ForwardingDecision.Block
        h.filter.invalidate()
        assertTrue(h.filter.onTunPacket(next, next.size, h.tun))
    }
}

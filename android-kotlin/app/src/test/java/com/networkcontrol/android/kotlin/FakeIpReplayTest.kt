package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.engine.vpn.DnsWire
import com.networkcontrol.android.kotlin.engine.vpn.FakeIpMappings
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDefaults
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketParser
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class FakeIpReplayTest {
    private fun ip(last: Int) = InetAddress.getByName("100.64.0.$last")

    @Test
    fun replayPlanIsContiguousFromNetworkStart() {
        val m = FakeIpMappings()
        m.record("a.example", ip(0))
        m.record("b.example", ip(1))
        m.record("c.example", ip(2))
        m.record("z.example", ip(9)) // 缺口之後：無法保證位址相同
        assertEquals(listOf("a.example", "b.example", "c.example"), m.beginSession())
        assertEquals(FakeIpMappings.State.Pending, m.state(ip(1)))
        assertEquals(FakeIpMappings.State.Unknown, m.state(ip(9)))
        assertTrue(m.record("a.example", ip(0)))
        assertEquals(FakeIpMappings.State.Known, m.state(ip(0)))
        assertEquals(FakeIpMappings.State.NotFake, m.state(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun mismatchAbortsReplay() {
        val m = FakeIpMappings()
        m.record("a.example", ip(0))
        m.record("b.example", ip(1))
        m.beginSession()
        assertFalse(m.record("other.example", ip(0)))
        assertFalse(m.replaying)
        assertEquals(FakeIpMappings.State.Known, m.state(ip(0)))
        assertEquals(FakeIpMappings.State.Unknown, m.state(ip(1)))
    }

    @Test
    fun planIsEmptyWhenNetworkStartIsUnknown() {
        val m = FakeIpMappings()
        m.record("b.example", ip(1))
        assertTrue(m.beginSession().isEmpty())
        assertEquals(FakeIpMappings.State.Unknown, m.state(ip(1)))
    }

    @Test
    fun untrackedBeforeFirstSession() {
        val m = FakeIpMappings()
        assertEquals(FakeIpMappings.State.NotFake, m.state(ip(5)))
    }

    @Test
    fun queryRoundTripsThroughParser() {
        val query = FakeIpMappings.buildQuery("www.baidu.com", 0x1234)!!
        assertTrue(DnsWire.tryParseQuery(query)!!.questionLength > 0)
        assertEquals(null, FakeIpMappings.buildQuery("bad..name", 1))
    }

    private class Writer : PacketWriter {
        val packets = mutableListOf<ByteArray>()
        override fun write(packet: ByteArray) {
            packets += packet
        }
    }

    private val client = InetSocketAddress("10.111.222.1", 40000)
    private val mapDns = InetSocketAddress(ForwardingDefaults.MapDnsAddress, 53)

    private fun reply(query: ByteArray, address: InetAddress, port: Int): ByteArray {
        val parsed = DnsWire.tryParseQuery(query)!!
        val response = DnsWire.buildResponse(parsed, query.copyOfRange(12, 12 + parsed.questionLength), address, 1)
        return IpPacketBuilder.udp4(mapDns, InetSocketAddress(client.address, port), response)
    }

    private fun syn(to: InetAddress): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45; p[3] = 40; p[8] = 64; p[9] = 6
        client.address.address.copyInto(p, 12)
        to.address.copyInto(p, 16)
        p[20] = (40001 ushr 8).toByte(); p[21] = 40001.toByte()
        p[22] = 1; p[23] = 0xBB.toByte()
        p[27] = 1; p[32] = 0x50; p[33] = 0x02
        return p
    }

    @Test
    fun hevRestartReplaysMappingsAndGuardsStaleAddresses() {
        var now = 1_000L
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10123 },
            decide = { _, _, _ -> ForwardingDecision.Forward },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            clock = { now },
        )
        // 第一次 hev：應用解析了兩個網域
        val a = reply(FakeIpMappings.buildQuery("a.example", 1)!!, ip(0), 50000)
        assertTrue("應用的回覆照常寫回", filter.onHevPacket(a, a.size))
        val b = reply(FakeIpMappings.buildQuery("b.example", 2)!!, ip(1), 50001)
        filter.onHevPacket(b, b.size)

        // hev 重啟
        val hev = Writer()
        filter.toHev = hev
        filter.beginMappingReplay()
        assertEquals(2L, filter.counters.mappingsReplayed.get())
        val tun = Writer()

        // 重放未完成：發往舊位址的新連線先丟棄
        assertFalse(filter.onTunPacket(syn(ip(1)), 40, tun))
        assertTrue(tun.packets.isEmpty())
        assertEquals(1L, filter.counters.fakeIpHeld.get())

        // 放行執行緒依序送出重放查詢，hev 回覆相同位址
        now += 10
        filter.drainDns()
        val first = hev.packets.removeAt(0)
        assertEquals(FakeIpMappings.REPLAY_PORT, IpPacketParser.tryParse(first)!!.source.port)
        val firstReply = reply(first.copyOfRange(28, first.size), ip(0), FakeIpMappings.REPLAY_PORT)
        assertFalse("重放的回覆不寫回虛擬網卡", filter.onHevPacket(firstReply, firstReply.size))
        now += 5
        filter.drainDns()
        val second = hev.packets.removeAt(0)
        val secondReply = reply(second.copyOfRange(28, second.size), ip(1), FakeIpMappings.REPLAY_PORT)
        filter.onHevPacket(secondReply, secondReply.size)

        // 恢復後正常轉發；hev 不認得的假位址直接拒絕
        assertTrue(filter.onTunPacket(syn(ip(1)), 40, tun))
        assertFalse(filter.onTunPacket(syn(ip(30)), 40, tun))
        assertEquals(L4Protocol.Tcp, IpPacketParser.tryParse(tun.packets.single())!!.protocol)
        assertEquals(1L, filter.counters.fakeIpRejected.get())
    }
}

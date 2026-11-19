package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppRouting
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.engine.vpn.DnsAnswers
import com.networkcontrol.android.kotlin.engine.vpn.DnsWire
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDefaults
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPolicy
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketParser
import com.networkcontrol.android.kotlin.engine.vpn.PacketInfo
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import com.networkcontrol.android.kotlin.engine.vpn.TcpFlags
import com.networkcontrol.android.kotlin.engine.vpn.VpnTunnelConfig
import com.networkcontrol.android.kotlin.engine.vpn.writeForwardingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files

class ForwardingTest {
    private val client = InetSocketAddress("10.111.222.1", 40000)
    private val server = InetSocketAddress("100.64.0.7", 443)

    private class RecordingWriter : PacketWriter {
        val packets = mutableListOf<ByteArray>()
        override fun write(packet: ByteArray) {
            packets += packet
        }
    }

    private class Harness(
        decision: ForwardingDecision,
        blockUdp: Boolean = false,
        uid: Int? = 10123,
        var now: Long = 1_000L,
    ) {
        var lookups = 0
        var currentDecision = decision
        val blocked = mutableListOf<Triple<PacketInfo, Int?, Boolean>>()
        val writer = RecordingWriter()
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ ->
                lookups++
                uid
            },
            decide = { _, _, _ -> currentDecision },
            onBlocked = { info, owner, ask -> blocked += Triple(info, owner, ask) },
            blockUdp = { blockUdp },
            clock = { now },
        )
    }

    @Test
    fun allowedSynIsForwardedAndCached() {
        val h = Harness(ForwardingDecision.Forward)
        val syn = tcp4(client, server, TcpFlags.SYN)
        assertTrue(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertTrue(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertEquals(1, h.lookups)
        assertTrue(h.blocked.isEmpty())
        assertTrue(h.writer.packets.isEmpty())
    }

    @Test
    fun blockedSynIsRejectedWithResetAndReportedOnce() {
        val h = Harness(ForwardingDecision.Block)
        val syn = tcp4(client, server, TcpFlags.SYN, sequence = 41)
        assertFalse(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertFalse(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertEquals(1, h.blocked.size)
        assertFalse(h.blocked.single().third)
        assertEquals(2, h.writer.packets.size)
        val reset = IpPacketParser.tryParse(h.writer.packets.first())!!
        assertEquals(server, reset.source)
        assertEquals(client, reset.destination)
        assertTrue((reset.tcpFlags and TcpFlags.RST) != 0)
    }

    @Test
    fun askDecisionIsReportedAsPrompt() {
        val h = Harness(ForwardingDecision.BlockAndAsk)
        val syn = tcp4(client, server, TcpFlags.SYN)
        assertFalse(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertTrue(h.blocked.single().third)
        assertEquals(10123, h.blocked.single().second)
    }

    @Test
    fun establishedTcpTakesFastPathWithoutLookup() {
        val h = Harness(ForwardingDecision.Block)
        val ack = tcp4(client, server, TcpFlags.ACK or TcpFlags.PSH)
        assertTrue(h.filter.onTunPacket(ack, ack.size, h.writer))
        assertEquals(0, h.lookups)
    }

    @Test
    fun invalidateForcesNewDecision() {
        val h = Harness(ForwardingDecision.BlockAndAsk)
        val syn = tcp4(client, server, TcpFlags.SYN)
        assertFalse(h.filter.onTunPacket(syn, syn.size, h.writer))
        h.currentDecision = ForwardingDecision.Forward
        assertFalse(h.filter.onTunPacket(syn, syn.size, h.writer))
        h.filter.invalidate()
        assertTrue(h.filter.onTunPacket(syn, syn.size, h.writer))
        assertEquals(2, h.lookups)
    }

    @Test
    fun cachedTcpDecisionExpires() {
        val h = Harness(ForwardingDecision.Forward)
        val syn = tcp4(client, server, TcpFlags.SYN)
        h.filter.onTunPacket(syn, syn.size, h.writer)
        h.now += ForwardingPacketFilter.TCP_TTL_MS + 1
        h.filter.onTunPacket(syn, syn.size, h.writer)
        assertEquals(2, h.lookups)
    }

    @Test
    fun dnsToMappedServerIsAlwaysForwarded() {
        val h = Harness(ForwardingDecision.Block, blockUdp = true)
        val dns = IpPacketBuilder.udp4(
            client,
            InetSocketAddress(ForwardingDefaults.MapDnsAddress, 53),
            buildQuery("example.com"),
        )
        assertTrue(h.filter.onTunPacket(dns, dns.size, h.writer))
        assertEquals(0, h.lookups)
    }

    @Test
    fun udpIsRejectedWhenUdpForwardingIsOff() {
        val h = Harness(ForwardingDecision.Forward, blockUdp = true)
        val quic = IpPacketBuilder.udp4(client, InetSocketAddress("100.64.0.7", 443), byteArrayOf(1, 2, 3))
        assertFalse(h.filter.onTunPacket(quic, quic.size, h.writer))
        assertEquals(1, h.writer.packets.size)
        assertEquals(L4Protocol.Icmp, IpPacketParser.tryParse(h.writer.packets.single())!!.protocol)
        assertEquals(0, h.lookups)
    }

    @Test
    fun udpIsDecidedPerFlowWhenAllowed() {
        val h = Harness(ForwardingDecision.Forward)
        val packet = IpPacketBuilder.udp4(client, InetSocketAddress("100.64.0.7", 443), byteArrayOf(1))
        assertTrue(h.filter.onTunPacket(packet, packet.size, h.writer))
        assertTrue(h.filter.onTunPacket(packet, packet.size, h.writer))
        assertEquals(1, h.lookups)
    }

    @Test
    fun mappedDnsAnswersAreRecordedAsHostnames() {
        val h = Harness(ForwardingDecision.Forward)
        val query = buildQuery("www.example.com")
        val parsed = DnsWire.tryParseQuery(query)!!
        val mapped = InetAddress.getByName("100.64.0.9")
        val response = DnsWire.buildResponse(parsed, query.copyOfRange(12, 12 + parsed.questionLength), mapped, 1)
        val packet = IpPacketBuilder.udp4(InetSocketAddress(ForwardingDefaults.MapDnsAddress, 53), client, response)
        h.filter.onHevPacket(packet, packet.size)
        assertEquals("www.example.com", h.filter.hostnameFor(mapped))
        assertNull(h.filter.hostnameFor(InetAddress.getByName("100.64.0.10")))
    }

    @Test
    fun dnsAnswerParserHandlesEmptyAndMalformedResponses() {
        val query = buildQuery("example.com")
        val parsed = DnsWire.tryParseQuery(query)!!
        val empty = DnsWire.buildResponse(parsed, query.copyOfRange(12, 12 + parsed.questionLength), null, 1)
        assertTrue(DnsAnswers.parseA(empty, 0, empty.size).isEmpty())
        assertTrue(DnsAnswers.parseA(query, 0, query.size).isEmpty())
        assertTrue(DnsAnswers.parseA(byteArrayOf(1, 2, 3), 0, 3).isEmpty())
    }

    @Test
    fun policyFollowsRoutingPlan() {
        val plan = VpnRoutingPlan(
            mode = FilterMode.Ask,
            bypassPackages = emptySet(),
            byUid = mapOf(
                10001 to routing(10001, AppRoutingDecision.Bypass),
                10002 to routing(10002, AppRoutingDecision.Block),
                10003 to routing(10003, AppRoutingDecision.BlockAndAsk),
            ),
            skippedRules = emptyList(),
        )
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan, 10001))
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(plan, 10002))
        assertEquals(ForwardingDecision.BlockAndAsk, ForwardingPolicy.decide(plan, 10003))
        assertEquals(ForwardingDecision.BlockAndAsk, ForwardingPolicy.decide(plan, 10999))
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan, 1051))
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan, null))
    }

    @Test
    fun policyBlocksUnknownOwnersOnlyWhenBlockingEverything() {
        val blockAll = VpnRoutingPlan(FilterMode.BlockAll, emptySet(), emptyMap(), emptyList())
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(blockAll, null))
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(blockAll, 10999))
        val blacklist = VpnRoutingPlan(FilterMode.Blacklist, emptySet(), emptyMap(), emptyList())
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(blacklist, 10999))
        val whitelist = VpnRoutingPlan(FilterMode.Whitelist, emptySet(), emptyMap(), emptyList())
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(whitelist, 10999))
        // 白名單預設拒絕：查不到擁有者也拒絕
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(whitelist, null))
        val ask = VpnRoutingPlan(FilterMode.Ask, emptySet(), emptyMap(), emptyList())
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(ask, null))
    }

    @Test
    fun configUsesStandardUdpMapDnsAndQuietLogs() {
        val dir = Files.createTempDirectory("fwd").toFile()
        try {
            val file = File(dir, "hev.yml")
            writeForwardingConfig(
                file,
                VpnTunnelConfig(emptySet(), forwarding = true, upstreamHost = "127.0.0.1", upstreamPort = 7890, upstreamUdp = false),
            )
            val text = file.readText()
            assertTrue(text.contains("udp: 'udp'"))
            assertFalse(text.contains("udp: 'tcp'"))
            assertTrue(text.contains("mapdns:"))
            assertTrue(text.contains("address: ${ForwardingDefaults.MapDnsAddress.hostAddress}"))
            assertTrue(text.contains("network: 100.64.0.0"))
            assertTrue(text.contains("log-level: warn"))
            assertTrue(text.contains("port: 7890"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizedHevLogIsTruncated() {
        val dir = Files.createTempDirectory("fwd").toFile()
        try {
            val log = File(dir, "hev.log")
            log.writeBytes(ByteArray((ForwardingDefaults.MAX_LOG_BYTES + 10).toInt()))
            writeForwardingConfig(File(dir, "hev.yml"), VpnTunnelConfig(emptySet(), forwarding = true))
            assertEquals(0L, log.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multiSlotUdpFastPathHandlesConcurrentFlowsWithoutThrashing() {
        var now = 1000L
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10001 },
            decide = { _, _, _ -> ForwardingDecision.Forward },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            clock = { now },
        )
        val writer = object : PacketWriter {
            override fun write(packet: ByteArray) = Unit
        }
        val flows = (0 until 4).map { i ->
            IpPacketBuilder.udp4(
                InetSocketAddress(InetAddress.getByName("10.111.222.1"), 40000 + i),
                InetSocketAddress(InetAddress.getByName("1.1.1.${i + 1}"), 443),
                byteArrayOf(1, 2, 3, 4),
            )
        }
        // 首包建立 4 條 UDP 流
        flows.forEach { pkt -> assertTrue(filter.onTunPacket(pkt, pkt.size, writer)) }
        assertEquals(4L, filter.counters.lookups.get())
        // 交替發送 4 條流的後續封包，都應命中多槽位快速路徑，不再觸發完整解析與查詢
        repeat(3) {
            flows.forEach { pkt -> assertTrue(filter.onTunPacket(pkt, pkt.size, writer)) }
        }
        assertEquals(4L, filter.counters.lookups.get())
        assertEquals(12L, filter.counters.udpFastPath.get())
    }

    @Test
    fun tunnelConfigEquivalentIgnoresUpstreamUdpAndDetectsLoopbackHost() {
        val a = VpnTunnelConfig(bypassPackages = setOf("a"), forwarding = true, upstreamUdp = true)
        val b = VpnTunnelConfig(bypassPackages = setOf("a"), forwarding = true, upstreamUdp = false)
        assertTrue(a.equivalentTo(b))
        assertTrue(com.networkcontrol.android.kotlin.engine.vpn.VpnEngine.isLoopbackHost("127.0.0.1"))
        assertTrue(com.networkcontrol.android.kotlin.engine.vpn.VpnEngine.isLoopbackHost("localhost"))
        assertTrue(com.networkcontrol.android.kotlin.engine.vpn.VpnEngine.isLoopbackHost("::1"))
        assertFalse(com.networkcontrol.android.kotlin.engine.vpn.VpnEngine.isLoopbackHost("192.168.1.5"))
    }

    @Test
    fun cloneAppUidInheritsPrimaryUserRoutingDecision() {
        val wechatUid = 10234
        val vivoCloneUid = 999 * 100_000 + wechatUid
        val plan = VpnRoutingPlan(
            mode = FilterMode.Ask,
            bypassPackages = setOf("com.tencent.mm"),
            byUid = mapOf(wechatUid to routing(wechatUid, AppRoutingDecision.Bypass)),
            skippedRules = emptyList(),
        )
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan, wechatUid))
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(plan, vivoCloneUid))
        assertEquals("pkg.$wechatUid", plan.find(vivoCloneUid)?.packageName)
    }

    @Test
    fun activeConnectionsCanBeListedAndManuallyCut() {
        var now = 1000L
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10001 },
            decide = { _, _, _ -> ForwardingDecision.Forward },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            clock = { now },
        )
        val writer = object : PacketWriter {
            override fun write(packet: ByteArray) = Unit
        }
        val local = InetSocketAddress(InetAddress.getByName("10.111.222.1"), 51234)
        val remote = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443)
        val syn = tcp4(local, remote, TcpFlags.SYN, sequence = 100)
        assertTrue(filter.onTunPacket(syn, syn.size, writer))
        val live = filter.activeConnections()
        assertEquals(1, live.size)
        assertEquals(remote, live[0].remote)
        assertTrue(filter.cutConnection(local, remote))
        assertTrue(filter.activeConnections().isEmpty())

        // 驗證 StatusBarSpeedIcon 雙行網速格式
        assertEquals("512" to "B/s", com.networkcontrol.android.kotlin.platform.StatusBarSpeedIcon.splitSpeed(512))
        assertEquals("128" to "KB/s", com.networkcontrol.android.kotlin.platform.StatusBarSpeedIcon.splitSpeed(128 * 1024))
        assertEquals("2.5" to "MB/s", com.networkcontrol.android.kotlin.platform.StatusBarSpeedIcon.splitSpeed((2.5 * 1024 * 1024).toLong()))
    }

    private fun routing(uid: Int, decision: AppRoutingDecision) =
        AppRouting(uid, "pkg.$uid", listOf("pkg.$uid"), decision, AppRoutingReason.Rule)

    private fun tcp4(
        source: InetSocketAddress,
        destination: InetSocketAddress,
        flags: Int,
        sequence: Long = 1,
    ): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[2] = 0
        packet[3] = 40
        packet[6] = 0x40
        packet[8] = 64
        packet[9] = 6
        source.address.address.copyInto(packet, 12)
        destination.address.address.copyInto(packet, 16)
        packet[20] = (source.port ushr 8).toByte()
        packet[21] = source.port.toByte()
        packet[22] = (destination.port ushr 8).toByte()
        packet[23] = destination.port.toByte()
        packet[24] = (sequence ushr 24).toByte()
        packet[25] = (sequence ushr 16).toByte()
        packet[26] = (sequence ushr 8).toByte()
        packet[27] = sequence.toByte()
        packet[32] = 0x50
        packet[33] = flags.toByte()
        return packet
    }

    private fun buildQuery(name: String): ByteArray {
        val labels = name.split('.')
        val question = ArrayList<Byte>()
        labels.forEach { label ->
            question += label.length.toByte()
            label.forEach { question += it.code.toByte() }
        }
        question += 0
        question += 0
        question += 1
        question += 0
        question += 1
        val header = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        return header + question.toByteArray()
    }
}

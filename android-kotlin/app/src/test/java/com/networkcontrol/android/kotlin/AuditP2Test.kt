package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatchOutcome
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.CompiledRule
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.LanRouteCalculator
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import org.junit.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuditP2Test {
    @Test
    fun lanRouteCalculatorExcludesPrivateRangesButKeepsLocalDnsAndMapDns() {
        val routes = LanRouteCalculator.ipv4Routes(allowLan = true)
            .map { (addr, prefix) -> IpNetwork.parse("$addr/$prefix") }

        fun isRouted(ip: String): Boolean {
            val addr = InetAddress.getByName(ip)
            return routes.any { it.contains(addr) }
        }

        // 私有區域網路位址必須被排除（直連區網）
        assertFalse(isRouted("192.168.1.1"))
        assertFalse(isRouted("10.0.0.1"))
        assertFalse(isRouted("172.16.5.10"))
        assertFalse(isRouted("169.254.1.1"))

        // 本機 VPN 介面 / 假 DNS / mapdns 與公網位址必須保留在路由內
        assertTrue(isRouted("10.111.222.1"))
        assertTrue(isRouted("10.111.222.2"))
        assertTrue(isRouted("10.111.222.3"))
        assertTrue(isRouted("100.64.0.5"))
        assertTrue(isRouted("198.18.0.1"))
        assertTrue(isRouted("1.1.1.1"))
        assertTrue(isRouted("8.8.8.8"))
    }

    @Test
    fun signingCertificateHistoryPreventsFalseTamperAlertOnKeyRotation() {
        val rule = Rule(
            id = "r-sig",
            app = AppMatcher(
                pathPattern = "android-app://com.example.bank",
                requiredSignerId = "old-cert-sha256",
            ),
            action = VerdictAction.Allow,
            lifetime = RuleLifetime.Permanent,
            source = RuleSource.User,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val compiled = CompiledRule(rule)
        val rotatedApp = AppIdentity(
            path = "android-app://com.example.bank",
            signature = CodeSignature(
                signerId = "new-cert-sha256",
                signerHistoryIds = setOf("old-cert-sha256", "new-cert-sha256"),
            ),
        )
        assertEquals(AppMatchOutcome.Match, compiled.matchApp(rotatedApp))

        val forgedApp = AppIdentity(
            path = "android-app://com.example.bank",
            signature = CodeSignature(
                signerId = "attacker-cert-sha256",
                signerHistoryIds = setOf("attacker-cert-sha256"),
            ),
        )
        assertEquals(AppMatchOutcome.Tampered, compiled.matchApp(forgedApp))
    }

    @Test
    fun forwardingFilterBlocksQuicUdp443WhenBlockQuicEnabledWhileAllowingOtherUdp() {
        val written = mutableListOf<ByteArray>()
        val writer = object : PacketWriter {
            override fun write(packet: ByteArray) {
                written += packet
            }
        }
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10123 },
            decide = { _, _, _ -> ForwardingDecision.Forward },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            blockQuic = { true },
        )
        val quicPacket = buildIpv4UdpPacket("10.111.222.1", 50000, "1.1.1.1", 443)
        assertFalse(filter.onTunPacket(quicPacket, quicPacket.size, writer))
        assertEquals(1L, filter.counters.udpDropped.get())
        assertTrue(written.isNotEmpty())

        val otherUdp = buildIpv4UdpPacket("10.111.222.1", 50001, "1.1.1.1", 123)
        assertTrue(filter.onTunPacket(otherUdp, otherUdp.size, writer))
    }

    @Test
    fun networkContextFingerprintIncludesRoamingState() {
        val normal = NetworkContext(kind = NetworkKind.Cellular, isRoaming = false)
        val roaming = NetworkContext(kind = NetworkKind.Cellular, isRoaming = true)
        assertNotEquals(normal.fingerprint, roaming.fingerprint)
    }

    private fun buildIpv4UdpPacket(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): ByteArray {
        val totalLen = 20 + 8 + 4
        val buf = ByteArray(totalLen)
        buf[0] = 0x45
        buf[2] = (totalLen ushr 8).toByte()
        buf[3] = totalLen.toByte()
        buf[8] = 64
        buf[9] = 17 // UDP
        System.arraycopy(InetAddress.getByName(srcIp).address, 0, buf, 12, 4)
        System.arraycopy(InetAddress.getByName(dstIp).address, 0, buf, 16, 4)
        buf[20] = (srcPort ushr 8).toByte()
        buf[21] = srcPort.toByte()
        buf[22] = (dstPort ushr 8).toByte()
        buf[23] = dstPort.toByte()
        buf[24] = 0
        buf[25] = 12
        return buf
    }
}

package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.AppRouting
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.FlowOrigin
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VpnRoutingPlan
import com.networkcontrol.android.kotlin.core.rules.RuleIdentity
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.engine.apps.AppForegroundState
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPolicy
import com.networkcontrol.android.kotlin.engine.vpn.IpPacketBuilder
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ReviewFixesTest {
    // ---- 「允許一次」的前台追蹤 ----

    @Test
    fun switchingActivitiesInsideAppIsNotLeaving() {
        val state = AppForegroundState()
        state.onResumed("com.example", "A")
        // 應用內從 A 打開 B：A PAUSED → B RESUMED → A STOPPED
        state.onPausedOrStopped("com.example", "A", 10, stopped = false)
        state.onResumed("com.example", "B")
        state.onPausedOrStopped("com.example", "A", 11, stopped = true)
        assertTrue(state.isForeground("com.example"))
        assertNull(state.leftAt("com.example"))
    }

    @Test
    fun stoppingLastActivityRecordsLeave() {
        val state = AppForegroundState()
        state.onResumed("com.example", "A")
        state.onPausedOrStopped("com.example", "A", 10, stopped = false)
        assertFalse(state.isForeground("com.example"))
        assertNull(state.leftAt("com.example"))
        state.onPausedOrStopped("com.example", "A", 12, stopped = true)
        assertEquals(12L, state.leftAt("com.example"))
        state.onResumed("com.example", "A")
        assertNull(state.leftAt("com.example"))
    }

    @Test
    fun screenOffIsNotLeaving() {
        val state = AppForegroundState()
        state.onResumed("com.example", "A")
        state.onScreenInteractive(false)
        state.onForegroundReset()
        state.onPausedOrStopped("com.example", "A", 20, stopped = true)
        assertFalse(state.isForeground("com.example"))
        assertNull(state.leftAt("com.example"))
    }

    // ---- 轉發模式的條件規則 ----

    private val app = AppIdentity(path = "android-app://com.example", displayName = "Example")

    private fun rule(
        id: String,
        action: VerdictAction,
        conditions: RuleConditions,
        path: String? = app.path,
    ) = Rule(
        id = id,
        app = path?.let { AppMatcher(it) },
        conditions = conditions,
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun flow(port: Int = 443) = FlowInfo(
        flowId = 0,
        direction = Direction.Outbound,
        protocol = L4Protocol.Tcp,
        local = InetSocketAddress("10.111.222.1", 40000),
        remote = InetSocketAddress("100.64.0.9", port),
        app = app,
        timestamp = 0,
        origin = FlowOrigin.BlockedEvent,
    )

    private fun plan(mode: FilterMode, decision: AppRoutingDecision, reason: AppRoutingReason = AppRoutingReason.Mode) =
        VpnRoutingPlan(
            mode = mode,
            bypassPackages = emptySet(),
            byUid = mapOf(10100 to AppRouting(10100, "com.example", listOf("com.example"), decision, reason)),
            skippedRules = emptyList(),
        )

    @Test
    fun domainAllowRuleForwardsOnlyMatchingDomain() {
        val rules = RuleSet(
            listOf(rule("allow-api", VerdictAction.Allow, RuleConditions(direction = Direction.Outbound, remote = RemoteTarget.Domain("api.example.com")))),
        )
        assertTrue(rules.hasFlowConditions)
        val whitelist = plan(FilterMode.Whitelist, AppRoutingDecision.Block)
        fun decide(host: String) = ForwardingPolicy.decide(whitelist, 10100) {
            ForwardingPolicy.conditionalAction(rules, flow(), DomainCandidates(listOf(host)), NetworkZone.Internet, NetworkContext.None)
        }
        assertEquals(ForwardingDecision.Forward, decide("api.example.com"))
        assertEquals(ForwardingDecision.Block, decide("ads.example.net"))
    }

    @Test
    fun globalPortDenyRuleBlocksAllowedApp() {
        val rules = RuleSet(
            listOf(rule("no-smtp", VerdictAction.Deny, RuleConditions(remotePort = PortRange.single(25)), path = null)),
        )
        val allowed = plan(FilterMode.Ask, AppRoutingDecision.Bypass, AppRoutingReason.Rule)
        fun decide(port: Int) = ForwardingPolicy.decide(allowed, 10100) {
            ForwardingPolicy.conditionalAction(rules, flow(port), DomainCandidates.Empty, NetworkZone.Internet, NetworkContext.None)
        }
        assertEquals(ForwardingDecision.Block, decide(25))
        assertEquals(ForwardingDecision.Forward, decide(443))
    }

    @Test
    fun appLevelRuleIsLeftToRoutingPlan() {
        val rules = RuleSet(
            listOf(
                rule("app-deny", VerdictAction.Deny, RuleConditions(direction = Direction.Outbound)),
                rule("port", VerdictAction.Allow, RuleConditions(remotePort = PortRange.single(8443)), path = "android-app://other"),
            ),
        )
        val action = ForwardingPolicy.conditionalAction(rules, flow(), DomainCandidates.Empty, NetworkZone.Internet, NetworkContext.None)
        assertNull(action)
    }

    @Test
    fun focusAndBlockAllIgnoreConditionalRules() {
        var called = false
        val blockAll = plan(FilterMode.BlockAll, AppRoutingDecision.Block)
        assertEquals(ForwardingDecision.Block, ForwardingPolicy.decide(blockAll, 10100) { called = true; VerdictAction.Allow })
        val focus = plan(FilterMode.Ask, AppRoutingDecision.Bypass, AppRoutingReason.Focus)
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(focus, 10100) { called = true; VerdictAction.Deny })
        assertEquals(ForwardingDecision.Forward, ForwardingPolicy.decide(focus, 1051) { called = true; VerdictAction.Deny })
        assertFalse(called)
    }

    @Test
    fun ruleSetWithoutConditionsSkipsFlowMatching() {
        val rules = RuleSet(listOf(rule("app", VerdictAction.Allow, RuleConditions(direction = Direction.Outbound))))
        assertFalse(rules.hasFlowConditions)
    }

    // ---- 規則合併 ----

    @Test
    fun mergingTemporaryRuleRestartsItsLifetime() {
        val existing = rule("old", VerdictAction.Allow, RuleConditions(direction = Direction.Outbound))
            .copy(createdAt = 100, lifetime = RuleLifetime.UntilAppExit)
        val incoming = existing.copy(id = "new", createdAt = 5_000, lifetime = RuleLifetime.UntilAppExit)
        val merged = RuleIdentity.merge(existing, incoming, now = 5_000)
        assertEquals("old", merged.id)
        assertEquals(5_000L, merged.createdAt)

        val permanent = incoming.copy(lifetime = RuleLifetime.Permanent)
        assertEquals(100L, RuleIdentity.merge(existing, permanent, now = 5_000).createdAt)
    }

    // ---- 轉發過濾層的 UDP 快速路徑 ----

    @Test
    fun forwardedUdpFlowUsesFastPathUntilInvalidated() {
        var now = 1_000L
        var decision = ForwardingDecision.Forward
        var decisions = 0
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> 10100 },
            decide = { _, _, _ -> decisions++; decision },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            clock = { now },
        )
        val writer = object : PacketWriter {
            override fun write(packet: ByteArray) = Unit
        }
        val src = InetSocketAddress("10.111.222.1", 50000)
        val quic = IpPacketBuilder.udp4(src, InetSocketAddress("100.64.0.9", 443), ByteArray(100))
        val other = IpPacketBuilder.udp4(src, InetSocketAddress("100.64.0.10", 443), ByteArray(100))

        assertTrue(filter.onTunPacket(quic, quic.size, writer))
        assertTrue(filter.onTunPacket(quic, quic.size, writer))
        assertTrue(filter.onTunPacket(quic, quic.size, writer))
        assertEquals(2L, filter.counters.udpFastPath.get())
        assertEquals(1, decisions)

        // 另一條流不能命中快速路徑
        assertTrue(filter.onTunPacket(other, other.size, writer))
        assertEquals(2L, filter.counters.udpFastPath.get())
        assertEquals(2, decisions)

        // 規則改變後必須重新判決
        decision = ForwardingDecision.Block
        filter.invalidate()
        assertFalse(filter.onTunPacket(other, other.size, writer))
        assertEquals(3, decisions)

        // 快速路徑到期後走完整路徑（仍命中五元組緩存，不重新判決）
        decision = ForwardingDecision.Forward
        filter.invalidate()
        assertTrue(filter.onTunPacket(quic, quic.size, writer))
        now += ForwardingPacketFilter.UDP_FAST_PATH_MS + 1
        val before = filter.counters.udpFastPath.get()
        assertTrue(filter.onTunPacket(quic, quic.size, writer))
        assertEquals(before, filter.counters.udpFastPath.get())
        assertEquals(4, decisions)
    }
}

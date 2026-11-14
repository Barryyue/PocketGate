package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.matching.DomainPattern
import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import com.networkcontrol.android.kotlin.core.matching.NetworkZones
import com.networkcontrol.android.kotlin.core.matching.PathPattern
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.core.rules.RuleSpecificity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class RulesTest {
    private val app = AppIdentity(
        path = "android-app://com.example.app",
        displayName = "Example",
        signature = CodeSignature("signer-a"),
    )

    private fun flow(port: Int = 443, protocol: L4Protocol = L4Protocol.Tcp) = FlowInfo(
        flowId = 1,
        direction = Direction.Outbound,
        protocol = protocol,
        local = InetSocketAddress("10.0.0.2", 51000),
        remote = InetSocketAddress("93.184.216.34", port),
        app = app,
        timestamp = 1_000,
    )

    private fun rule(
        id: String,
        action: VerdictAction,
        path: String? = app.path,
        signer: String? = "signer-a",
        conditions: RuleConditions = RuleConditions(direction = Direction.Outbound),
        manualPriority: Int = 0,
        updatedAt: Long = 1_000,
        lifetime: RuleLifetime = RuleLifetime.Permanent,
        expiresAt: Long? = null,
    ) = Rule(
        id = id,
        app = path?.let {
            com.networkcontrol.android.kotlin.core.model.AppMatcher(
                pathPattern = it,
                requiredSignerId = signer,
            )
        },
        conditions = conditions,
        action = action,
        lifetime = lifetime,
        expiresAt = expiresAt,
        source = RuleSource.User,
        manualPriority = manualPriority,
        createdAt = 1_000,
        updatedAt = updatedAt,
    )

    @Test
    fun pathPatternSupportsWildcardsAndCaseSensitivity() {
        assertTrue(PathPattern.parse("android-app://com.example.*").matches("android-app://com.example.app"))
        assertFalse(PathPattern.parse("android-app://com.example.*").matches("android-app://com.other.app"))
        assertTrue(PathPattern.parse("android-app://com.example.*").matches("android-app://com.example.a.b"))
        assertTrue(PathPattern.parse("android-app://**/app").matches("android-app://a/b/app"))
        assertFalse(PathPattern.parse("android-app://com.example.app").matches("android-app://COM.EXAMPLE.APP"))
    }

    @Test
    fun domainPatternMatchesBaseAndSubdomains() {
        val pattern = DomainPattern.parse("*.example.com")
        assertTrue(pattern.matches("example.com"))
        assertTrue(pattern.matches("api.example.com"))
        assertFalse(pattern.matches("badexample.com"))
        assertFalse(DomainPattern.parse("api.example.com").matches("www.example.com"))
    }

    @Test
    fun ipNetworksHandleIpv4AndIpv6() {
        val privateNet = IpNetwork.parse("192.168.0.0/16")
        assertTrue(privateNet.contains(InetAddress.getByName("192.168.1.20")))
        assertFalse(privateNet.contains(InetAddress.getByName("10.0.0.1")))
        assertEquals(NetworkZone.Loopback, NetworkZones.classify(InetAddress.getByName("127.0.0.1")))
        assertEquals(NetworkZone.LocalNetwork, NetworkZones.classify(InetAddress.getByName("10.1.2.3")))
        assertEquals(NetworkZone.Internet, NetworkZones.classify(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun specificityPrefersExactAppAndPort() {
        val appOnly = RuleSpecificity.compute(rule("a", VerdictAction.Allow))
        val withPort = RuleSpecificity.compute(
            rule("b", VerdictAction.Allow, conditions = RuleConditions(direction = Direction.Outbound, remotePort = PortRange.single(443))),
        )
        assertTrue(withPort > appOnly)
    }

    @Test
    fun denyWinsAtSameSpecificity() {
        val rules = RuleSet(
            listOf(
                rule("allow", VerdictAction.Allow),
                rule("deny", VerdictAction.Deny, updatedAt = 900),
            ),
        )
        val match = rules.evaluate(flow(), app, DomainCandidates.Empty, NetworkZone.Internet)
        assertEquals("deny", match.rule?.id)
    }

    @Test
    fun manualPriorityOverridesSpecificity() {
        val rules = RuleSet(
            listOf(
                rule("specific", VerdictAction.Deny),
                rule(
                    "manual",
                    VerdictAction.Allow,
                    conditions = RuleConditions(),
                    manualPriority = 10,
                ),
            ),
        )
        val match = rules.evaluate(flow(), app, DomainCandidates.Empty, NetworkZone.Internet)
        assertEquals("manual", match.rule?.id)
    }

    @Test
    fun signerMismatchIsReportedAsTampered() {
        val rules = RuleSet(listOf(rule("allow", VerdictAction.Allow, signer = "expected")))
        val match = rules.evaluate(flow(), app, DomainCandidates.Empty, NetworkZone.Internet)
        assertNull(match.rule)
        assertNotNull(match.tamperedRule)
    }

    @Test
    fun timedRuleExpires() {
        val rule = rule(
            "timed",
            VerdictAction.Allow,
            lifetime = RuleLifetime.Timed,
            expiresAt = 2_000,
        )
        assertTrue(rule.isLiveAt(1_999))
        assertFalse(rule.isLiveAt(2_000))
    }

    @Test
    fun findAppRuleIgnoresConnectionConditions() {
        val rules = RuleSet(
            listOf(
                rule(
                    "blocked-by-port",
                    VerdictAction.Deny,
                    conditions = RuleConditions(remotePort = PortRange.single(443)),
                ),
            ),
        )
        val match = rules.findAppRule(app) { true }
        assertEquals("blocked-by-port", match.rule?.id)
    }
}

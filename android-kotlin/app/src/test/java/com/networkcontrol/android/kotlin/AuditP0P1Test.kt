package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.matching.IpNetwork
import com.networkcontrol.android.kotlin.core.matching.NetworkZones
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.DomainCandidates
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.FlowInfo
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkZone
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.UserSettings
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.core.update.UpdateChecker
import com.networkcontrol.android.kotlin.data.backup.FullBackupCodec
import com.networkcontrol.android.kotlin.data.backup.PortablePrefsSnapshot
import com.networkcontrol.android.kotlin.data.backup.WebDavBackupClient
import com.networkcontrol.android.kotlin.data.transfer.RuleTransferService
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPacketFilter
import com.networkcontrol.android.kotlin.engine.vpn.ForwardingPolicy
import com.networkcontrol.android.kotlin.engine.vpn.PacketWriter
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import com.networkcontrol.android.kotlin.platform.DiagnosticsRedactor
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuditP0P1Test {
    private fun testRule(
        id: String,
        app: AppMatcher? = null,
        profileId: String? = null,
        conditions: RuleConditions = RuleConditions(),
        action: VerdictAction,
    ) = Rule(
        id = id,
        profileId = profileId,
        app = app,
        conditions = conditions,
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun globalDomainDenyOverridesAppAllowRuleWhileAppDomainAllowStillWins() {
        val chrome = AppIdentity(path = "android-app://com.android.chrome", displayName = "Chrome")
        val appAllow = testRule(
            id = "allow-chrome",
            app = AppMatcher(pathPattern = chrome.path),
            action = VerdictAction.Allow,
        )
        val globalDenyAds = testRule(
            id = "deny-ads",
            app = null,
            conditions = RuleConditions(remote = RemoteTarget.Domain("ads.example.com")),
            action = VerdictAction.Deny,
        )
        val flow = FlowInfo(
            flowId = 1,
            direction = Direction.Outbound,
            protocol = L4Protocol.Tcp,
            local = InetSocketAddress(InetAddress.getByName("10.111.222.1"), 50000),
            remote = InetSocketAddress(InetAddress.getByName("100.64.0.5"), 443),
            app = chrome,
            executablePath = chrome.path,
            engineHostname = "ads.example.com",
            timestamp = 1_000L,
        )
        val domains = DomainCandidates(listOf("ads.example.com"))

        val set1 = RuleSet(listOf(appAllow, globalDenyAds))
        assertEquals(
            VerdictAction.Deny,
            ForwardingPolicy.conditionalAction(set1, flow, domains, NetworkZone.Internet, NetworkContext.None),
        )

        val appAllowAds = testRule(
            id = "allow-chrome-ads",
            app = AppMatcher(pathPattern = chrome.path),
            conditions = RuleConditions(remote = RemoteTarget.Domain("ads.example.com")),
            action = VerdictAction.Allow,
        )
        val set2 = RuleSet(listOf(appAllow, globalDenyAds, appAllowAds))
        assertEquals(
            VerdictAction.Allow,
            ForwardingPolicy.conditionalAction(set2, flow, domains, NetworkZone.Internet, NetworkContext.None),
        )
    }

    @Test
    fun ipv4MappedIpv6IsClassifiedAsLocalAndMatchesIpv4Network() {
        val mappedBytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(),
            192.toByte(), 168.toByte(), 1, 42,
        )
        val addr = InetAddress.getByAddress(mappedBytes)
        assertEquals(NetworkZone.LocalNetwork, NetworkZones.classify(addr))
        assertTrue(IpNetwork.parse("192.168.0.0/16").contains(addr))
    }

    @Test
    fun sharedUidEvaluatesRulesAcrossAllPackagesWithDenyPriority() {
        val appA = InstalledApp("com.example.a", uid = 10200, label = "A", isSystem = false, versionCode = 1, lastUpdated = 1_000L, signerSha256 = null)
        val appB = InstalledApp("com.example.b", uid = 10200, label = "B", isSystem = false, versionCode = 1, lastUpdated = 1_000L, signerSha256 = null)
        val allowA = testRule(
            id = "allow-a",
            app = AppMatcher(pathPattern = "android-app://com.example.a"),
            action = VerdictAction.Allow,
        )
        val denyB = testRule(
            id = "deny-b",
            app = AppMatcher(pathPattern = "android-app://com.example.b"),
            action = VerdictAction.Deny,
        )
        val plan = VpnRoutingPlanner.plan(
            rules = RuleSet(listOf(allowA, denyB)),
            mode = FilterMode.Ask,
            apps = listOf(appA, appB),
            selfPackage = "com.networkcontrol.android.kotlin",
        )
        assertEquals(AppRoutingDecision.Block, plan.find(10200)?.decision)
        assertEquals("deny-b", plan.find(10200)?.ruleId)
    }

    @Test
    fun forwardingFilterFailClosedDropsUnparseablePacketsInBlockAll() {
        val filter = ForwardingPacketFilter(
            ownerOf = { _, _, _ -> null },
            decide = { _, _, _ -> com.networkcontrol.android.kotlin.engine.vpn.ForwardingDecision.Block },
            onBlocked = { _, _, _ -> },
            blockUdp = { false },
            failClosed = { true },
        )
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val noopWriter = object : PacketWriter {
            override fun write(packet: ByteArray) = Unit
        }
        val forwarded = filter.onTunPacket(garbage, garbage.size, noopWriter)
        assertFalse(forwarded)
        assertEquals(1L, filter.counters.blocked.get())
    }

    @Test
    fun diagnosticsRedactorMasksSsidIpv6AndLogSecrets() {
        assertEquals("<redacted(len=8)>", DiagnosticsRedactor.redactSsid("HomeWiFi"))
        assertEquals("240e:3b4::/32(redacted)", DiagnosticsRedactor.redactIpv6("240e:3b4:1234:5678::1"))
        assertEquals("fe80::1", DiagnosticsRedactor.redactIpv6("fe80::1"))
        val redacted = DiagnosticsRedactor.redactLogLine(
            "PUT https://alice:s3cr3t@dav.example.com/bak password=hunter2 token=abc123",
        )
        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("https://***@dav.example.com"))
    }

    @Test
    fun updateCheckerParsesSha256SumsAndComputesFileHash() {
        val tmp = File.createTempFile("pg-sha-test", ".apk")
        try {
            tmp.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02))
            val actual = UpdateChecker.sha256Of(tmp)
            val sums = "$actual  dist/PocketGate-0.17.3.apk\n"
            assertEquals(actual, UpdateChecker.parseSha256Sums(sums, "PocketGate-0.17.3.apk"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun webDavClientSupportsV2EncryptionAndRejectsRemoteHttp() {
        val encrypted = WebDavBackupClient.encrypt("{\"ok\":true}", "strong-pass")
        assertEquals("PGWB2", String(encrypted.copyOfRange(0, 5)))
        assertEquals("{\"ok\":true}", WebDavBackupClient.decrypt(encrypted, "strong-pass"))
        assertFailsWith<IllegalArgumentException> {
            WebDavBackupClient.resolveTargetUrl("http://dav.example.com/backup")
        }
        assertTrue(WebDavBackupClient.resolveTargetUrl("http://127.0.0.1:8080/dav").startsWith("http://127.0.0.1"))
    }

    @Test
    fun fullBackupCodecRoundTripAndV1BackwardCompatibility() {
        val workProfile = Profile(id = "work", name = "Work", mode = FilterMode.Whitelist, sortOrder = 1)
        val rule = testRule(
            id = "r1",
            profileId = "work",
            app = AppMatcher(pathPattern = "android-app://com.example.app"),
            action = VerdictAction.Allow,
        )
        val json = FullBackupCodec.export(
            rules = listOf(rule),
            profiles = listOf(Profile.Default, workProfile),
            activeProfileId = "work",
            userSettings = UserSettings.Default.copy(allowSystemAppsByDefault = true, logRetentionDays = 14),
            portablePrefs = PortablePrefsSnapshot(
                forwardingEnabled = true,
                forwardingDirectPackages = setOf("com.example.excluded"),
                offlineMode = true,
            ),
            automationSubscriptionUrls = listOf("https://example.com/gkd.json5"),
        )
        val parsed = FullBackupCodec.parse(json)
        assertTrue(parsed.isFullSnapshot)
        assertEquals(2, parsed.profiles.size)
        assertEquals("work", parsed.activeProfileId)
        assertEquals(true, parsed.userSettings?.allowSystemAppsByDefault)
        assertEquals(14, parsed.userSettings?.logRetentionDays)
        assertEquals(setOf("com.example.excluded"), parsed.portablePrefs?.forwardingDirectPackages)
        assertEquals(listOf("https://example.com/gkd.json5"), parsed.automationSubscriptionUrls)

        val importedRules = RuleTransferService.importForPlan(json, parsed.profiles)
        assertEquals(1, importedRules.size)
        assertEquals("work", importedRules.first().profileId)

        // 舊版 v1 netcontrol-rules 仍可透過 FullBackupCodec.parse 無縫匯入
        val v1Json = RuleTransferService.export(listOf(rule))
        val v1Parsed = FullBackupCodec.parse(v1Json)
        assertFalse(v1Parsed.isFullSnapshot)
        assertNotNull(RuleTransferService.importForPlan(v1Parsed.rulesJson, listOf(Profile.Default, workProfile)))
    }
}

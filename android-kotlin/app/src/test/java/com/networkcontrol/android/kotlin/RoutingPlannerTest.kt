package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.AppRoutingDecision
import com.networkcontrol.android.kotlin.core.model.AppRoutingReason
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.RuleSet
import com.networkcontrol.android.kotlin.engine.vpn.VpnRoutingPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPlannerTest {
    private val self = "com.networkcontrol.android.kotlin.debug"
    private val chrome = InstalledApp("com.android.chrome", 10123, "Chrome", false, 1, 1, "signer-a")
    private val firefox = InstalledApp("org.mozilla.firefox", 10124, "Firefox", false, 1, 1, "signer-a")
    private val system = InstalledApp("com.android.systemui", 1010, "System UI", true, 1, 1, "signer-a")

    private fun rule(packageName: String, action: VerdictAction, conditions: RuleConditions = RuleConditions()) = Rule(
        id = "$packageName-${action.name}",
        app = AppMatcher("android-app://$packageName", requiredSignerId = "signer-a"),
        conditions = conditions,
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun askModeUsesRulesAndPromptsUnknownApps() {
        val rules = RuleSet(
            listOf(
                rule("com.android.chrome", VerdictAction.Allow),
                rule("org.mozilla.firefox", VerdictAction.Deny),
            ),
        )
        val plan = VpnRoutingPlanner.plan(rules, FilterMode.Ask, listOf(chrome, firefox, system), self)
        assertEquals(AppRoutingDecision.Bypass, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingReason.Rule, plan.find(chrome.uid)?.reason)
        assertEquals(AppRoutingDecision.Block, plan.find(firefox.uid)?.decision)
        assertEquals(AppRoutingDecision.Bypass, plan.find(system.uid)?.decision)
        assertEquals(AppRoutingReason.SystemUid, plan.find(system.uid)?.reason)
        assertTrue(plan.bypassPackages.contains(self))
        assertTrue(plan.bypassPackages.contains("com.android.chrome"))
        assertTrue(plan.bypassPackages.contains("com.android.systemui"))
    }

    @Test
    fun blockAllOverridesRules() {
        val rules = RuleSet(listOf(rule("com.android.chrome", VerdictAction.Allow)))
        val plan = VpnRoutingPlanner.plan(rules, FilterMode.BlockAll, listOf(chrome, system), self)
        assertEquals(AppRoutingDecision.Block, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingDecision.Bypass, plan.find(system.uid)?.decision)
    }

    @Test
    fun disabledBypassesEverything() {
        val plan = VpnRoutingPlanner.plan(RuleSet.Empty, FilterMode.Disabled, listOf(chrome, system), self)
        assertEquals(AppRoutingDecision.Bypass, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingDecision.Bypass, plan.find(system.uid)?.decision)
    }

    @Test
    fun blacklistAllowsUnknownApps() {
        val plan = VpnRoutingPlanner.plan(RuleSet.Empty, FilterMode.Blacklist, listOf(firefox), self)
        assertEquals(AppRoutingDecision.Bypass, plan.find(firefox.uid)?.decision)
    }

    @Test
    fun connectionConditionRulesAreReportedAsSkipped() {
        val rules = RuleSet(
            listOf(
                rule(
                    "org.mozilla.firefox",
                    VerdictAction.Deny,
                    conditions = RuleConditions(direction = Direction.Outbound, remotePort = PortRange.single(443)),
                ),
            ),
        )
        val plan = VpnRoutingPlanner.plan(rules, FilterMode.Ask, listOf(firefox), self)
        assertEquals(1, plan.skippedRules.size)
        assertEquals(AppRoutingDecision.BlockAndAsk, plan.find(firefox.uid)?.decision)
    }

    @Test
    fun selfPackageIsAlwaysExcluded() {
        val app = InstalledApp(self, 10200, "NetworkControl KT", false, 1, 1, "signer-a")
        val plan = VpnRoutingPlanner.plan(RuleSet.Empty, FilterMode.BlockAll, listOf(app), self)
        assertEquals(AppRoutingDecision.Bypass, plan.find(app.uid)?.decision)
        assertEquals(AppRoutingReason.Self, plan.find(app.uid)?.reason)
    }

    @Test
    fun signerMismatchIsTamperedAndDoesNotAllow() {
        val app = chrome.copy(signerSha256 = "different")
        val rules = RuleSet(listOf(rule("com.android.chrome", VerdictAction.Allow)))
        val plan = VpnRoutingPlanner.plan(rules, FilterMode.Ask, listOf(app), self)
        assertEquals(AppRoutingDecision.BlockAndAsk, plan.find(app.uid)?.decision)
        assertTrue(plan.find(app.uid)?.tamperedRuleId != null)
    }

    @Test
    fun networkScopedRulesApplyOnlyOnMatchingNetwork() {
        val rules = RuleSet(
            listOf(
                rule(
                    "com.android.chrome",
                    VerdictAction.Allow,
                    conditions = RuleConditions(networkKinds = setOf(NetworkKind.Wifi)),
                ),
            ),
        )
        val wifiPlan = VpnRoutingPlanner.plan(
            rules,
            FilterMode.Ask,
            listOf(chrome),
            self,
            NetworkContext(kind = NetworkKind.Wifi, ssid = "Office"),
        )
        assertEquals(AppRoutingDecision.Bypass, wifiPlan.find(chrome.uid)?.decision)
        val cellularPlan = VpnRoutingPlanner.plan(
            rules,
            FilterMode.Ask,
            listOf(chrome),
            self,
            NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 1),
        )
        assertEquals(AppRoutingDecision.BlockAndAsk, cellularPlan.find(chrome.uid)?.decision)
        assertTrue(cellularPlan.skippedRules.isEmpty())
    }

    @Test
    fun simScopedRuleWinsOverGenericAppRule() {        val rules = RuleSet(
            listOf(
                rule("com.android.chrome", VerdictAction.Deny),
                rule(
                    "com.android.chrome",
                    VerdictAction.Allow,
                    conditions = RuleConditions(
                        networkKinds = setOf(NetworkKind.Cellular),
                        subscriptionIds = setOf(2),
                    ),
                ).copy(id = "com.android.chrome-sim2"),
            ),
        )
        val sim2 = VpnRoutingPlanner.plan(
            rules,
            FilterMode.Ask,
            listOf(chrome),
            self,
            NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 2),
        )
        assertEquals(AppRoutingDecision.Bypass, sim2.find(chrome.uid)?.decision)
        val sim1 = VpnRoutingPlanner.plan(
            rules,
            FilterMode.Ask,
            listOf(chrome),
            self,
            NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 1),
        )
        assertEquals(AppRoutingDecision.Block, sim1.find(chrome.uid)?.decision)
    }

    @Test
    fun trustedSignerAllowsAppsWithoutExplicitRule() {
        val plan = VpnRoutingPlanner.plan(
            RuleSet.Empty,
            FilterMode.Ask,
            listOf(chrome),
            self,
            trustedSigners = setOf("signer-a"),
        )
        assertEquals(AppRoutingDecision.Bypass, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingReason.Trusted, plan.find(chrome.uid)?.reason)
    }

    @Test
    fun explicitDenyBeatsTrustedSigner() {
        val rules = RuleSet(listOf(rule("com.android.chrome", VerdictAction.Deny)))
        val plan = VpnRoutingPlanner.plan(
            rules,
            FilterMode.Ask,
            listOf(chrome),
            self,
            trustedSigners = setOf("signer-a"),
        )
        assertEquals(AppRoutingDecision.Block, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingReason.Rule, plan.find(chrome.uid)?.reason)
    }

    @Test
    fun systemAppsDefaultAllowCanBeEnabled() {
        val systemApp = InstalledApp("com.android.calendar", 10200, "Calendar", true, 1, 1, "signer-a")
        val blocked = VpnRoutingPlanner.plan(RuleSet.Empty, FilterMode.Ask, listOf(systemApp), self)
        assertEquals(AppRoutingDecision.BlockAndAsk, blocked.find(systemApp.uid)?.decision)
        val allowed = VpnRoutingPlanner.plan(
            RuleSet.Empty,
            FilterMode.Ask,
            listOf(systemApp),
            self,
            allowSystemAppsByDefault = true,
        )
        assertEquals(AppRoutingDecision.Bypass, allowed.find(systemApp.uid)?.decision)
        assertEquals(AppRoutingReason.Trusted, allowed.find(systemApp.uid)?.reason)
    }

    @Test
    fun focusModeAllowsOnlySelectedPackages() {
        val plan = VpnRoutingPlanner.plan(
            RuleSet.Empty,
            FilterMode.BlockAll,
            listOf(chrome, firefox),
            self,
            focusAllowedPackages = setOf("com.android.chrome"),
        )
        assertEquals(AppRoutingDecision.Bypass, plan.find(chrome.uid)?.decision)
        assertEquals(AppRoutingReason.Focus, plan.find(chrome.uid)?.reason)
        assertEquals(AppRoutingDecision.Block, plan.find(firefox.uid)?.decision)
    }

    @Test
    fun tamperedSignerDoesNotBecomeTrusted() {
        val tampered = chrome.copy(signerSha256 = "different")
        val plan = VpnRoutingPlanner.plan(
            RuleSet(listOf(rule("com.android.chrome", VerdictAction.Allow))),
            FilterMode.Ask,
            listOf(tampered),
            self,
            trustedSigners = setOf("signer-a"),
        )
        assertEquals(AppRoutingDecision.BlockAndAsk, plan.find(tampered.uid)?.decision)
        assertTrue(plan.find(tampered.uid)?.tamperedRuleId != null)
    }

    @Test
    fun allowedByRulePackagesFiltersOnlyAppsAllowedInEnabledRules() {
        val telegram = InstalledApp("org.telegram.messenger", 10125, "Telegram", false, 1, 1, "signer-a")
        val tampered = InstalledApp("com.example.tampered", 10126, "Tampered", false, 1, 1, "wrong-signer")
        val globalRule = Rule(
            id = "global-allow",
            app = null,
            conditions = RuleConditions(direction = Direction.Outbound),
            action = VerdictAction.Allow,
            lifetime = RuleLifetime.Permanent,
            source = RuleSource.User,
            createdAt = 1,
            updatedAt = 1,
        )
        val rules = RuleSet(
            listOf(
                globalRule,
                rule("com.android.chrome", VerdictAction.Allow),
                rule("org.mozilla.firefox", VerdictAction.Deny),
                rule(
                    "org.telegram.messenger",
                    VerdictAction.Allow,
                    conditions = RuleConditions(networkKinds = setOf(NetworkKind.Wifi)),
                ),
                rule("com.example.tampered", VerdictAction.Allow),
            ),
        )
        val allowed = VpnRoutingPlanner.allowedByRulePackages(
            rules = rules,
            apps = listOf(chrome, firefox, telegram, tampered, system),
            network = NetworkContext(kind = NetworkKind.Cellular),
        )
        assertEquals(setOf("com.android.chrome", "org.telegram.messenger"), allowed)
    }

    @Test
    fun pickerFilterAndSelectAllRespectRuleFilterAndQuery() {
        val telegram = InstalledApp("org.telegram.messenger", 10125, "Telegram", false, 1, 1, "signer-a")
        val allApps = listOf(chrome, firefox, telegram)
        val allowedByRules = setOf("com.android.chrome", "org.telegram.messenger")

        val onlyRuleAllowed = VpnRoutingPlanner.filterPickerApps(
            apps = allApps,
            query = "",
            onlyRuleAllowed = true,
            allowedByRulePackages = allowedByRules,
        )
        assertEquals(listOf(chrome, telegram), onlyRuleAllowed)

        val selectedAfterSelectAll = VpnRoutingPlanner.selectAllVisible(
            selected = setOf("com.other.kept"),
            visibleApps = onlyRuleAllowed,
        )
        assertEquals(
            setOf("com.other.kept", "com.android.chrome", "org.telegram.messenger"),
            selectedAfterSelectAll,
        )

        val afterClearVisible = VpnRoutingPlanner.clearVisibleOrAll(
            selected = selectedAfterSelectAll,
            visibleApps = onlyRuleAllowed,
        )
        assertEquals(setOf("com.other.kept"), afterClearVisible)

        val afterClearWhenNoVisibleSelected = VpnRoutingPlanner.clearVisibleOrAll(
            selected = afterClearVisible,
            visibleApps = onlyRuleAllowed,
        )
        assertTrue(afterClearWhenNoVisibleSelected.isEmpty())
    }

    @Test
    fun deniedByRulePackagesDetectsEnabledDenyRulesAndNewlyExcludedConflicts() {
        val telegram = InstalledApp("org.telegram.messenger", 10125, "Telegram", false, 1, 1, "signer-a")
        val rules = RuleSet(
            listOf(
                rule("com.android.chrome", VerdictAction.Allow),
                rule("org.mozilla.firefox", VerdictAction.Deny),
                rule("org.telegram.messenger", VerdictAction.Deny).copy(enabled = false),
            ),
        )
        val denied = VpnRoutingPlanner.deniedByRulePackages(rules, listOf(chrome, firefox, telegram))
        assertEquals(setOf("org.mozilla.firefox"), denied)

        val newlyConflicting = VpnRoutingPlanner.newlyExcludedWithDenyRules(
            initial = setOf("com.android.chrome"),
            next = setOf("com.android.chrome", "org.mozilla.firefox", "org.telegram.messenger"),
            deniedByRulePackages = denied,
        )
        assertEquals(setOf("org.mozilla.firefox"), newlyConflicting)

        val alreadyExcludedNoNewConflict = VpnRoutingPlanner.newlyExcludedWithDenyRules(
            initial = setOf("org.mozilla.firefox"),
            next = setOf("org.mozilla.firefox", "com.android.chrome"),
            deniedByRulePackages = denied,
        )
        assertTrue(alreadyExcludedNoNewConflict.isEmpty())
    }
}

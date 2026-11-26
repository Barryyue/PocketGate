package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleGroup
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.ui.rules.RuleFilter
import com.networkcontrol.android.kotlin.ui.rules.filterRuleGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleFilterTest {

    private fun rule(
        id: String,
        action: VerdictAction = VerdictAction.Allow,
        domain: String? = null,
        pkg: String? = null,
        note: String? = null,
    ) = Rule(
        id = id,
        profileId = "default",
        app = pkg?.let { AppMatcher(pathPattern = "app://$it") },
        conditions = RuleConditions(
            direction = Direction.Outbound,
            remote = domain?.let { RemoteTarget.Domain(it) },
        ),
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        note = note,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun sampleGroups(): List<RuleGroup> = listOf(
        RuleGroup(
            key = "global",
            packageName = null,
            label = "All apps",
            rules = listOf(
                rule("g-allow-dom", VerdictAction.Allow, domain = "allowed.example.com", note = "blocklist exception"),
                rule("g-deny-dom", VerdictAction.Deny, domain = "ads.example.com", note = "domain deny from logs"),
            ),
        ),
        RuleGroup(
            key = "com.example.browser",
            packageName = "com.example.browser",
            label = "Browser",
            rules = listOf(
                rule("b-allow-all", VerdictAction.Allow, pkg = "com.example.browser"),
                rule("b-allow-dom", VerdictAction.Allow, domain = "api.service.dev", pkg = "com.example.browser", note = "blocklist exception"),
            ),
        ),
        RuleGroup(
            key = "com.example.game",
            packageName = "com.example.game",
            label = "Game",
            rules = listOf(
                rule("game-deny", VerdictAction.Deny, pkg = "com.example.game"),
            ),
        ),
    )

    @Test
    fun domainFilterKeepsGlobalAndPerAppDomainRulesOnly() {
        val result = filterRuleGroups(sampleGroups(), RuleFilter.Domain, "")
        assertEquals(listOf("global", "com.example.browser"), result.map { it.key })
        assertEquals(listOf("g-allow-dom", "g-deny-dom"), result[0].rules.map { it.id })
        assertEquals(listOf("b-allow-dom"), result[1].rules.map { it.id })
    }

    @Test
    fun globalFilterKeepsGlobalGroupOnly() {
        val result = filterRuleGroups(sampleGroups(), RuleFilter.Global, "")
        assertEquals(listOf("global"), result.map { it.key })
        assertEquals(2, result[0].rules.size)
    }

    @Test
    fun allowedAndDeniedFiltersSplitRulesAcrossGroups() {
        val allowed = filterRuleGroups(sampleGroups(), RuleFilter.Allowed, "")
        assertEquals(listOf("global", "com.example.browser"), allowed.map { it.key })
        assertEquals(listOf("g-allow-dom"), allowed[0].rules.map { it.id })
        assertEquals(listOf("b-allow-all", "b-allow-dom"), allowed[1].rules.map { it.id })

        val denied = filterRuleGroups(sampleGroups(), RuleFilter.Denied, "")
        assertEquals(listOf("global", "com.example.game"), denied.map { it.key })
        assertEquals(listOf("g-deny-dom"), denied[0].rules.map { it.id })
        assertEquals(listOf("game-deny"), denied[1].rules.map { it.id })
    }

    @Test
    fun domainFilterCombinesWithQuery() {
        val byDomain = filterRuleGroups(sampleGroups(), RuleFilter.Domain, "service.dev")
        assertEquals(listOf("com.example.browser"), byDomain.map { it.key })
        assertEquals(listOf("b-allow-dom"), byDomain[0].rules.map { it.id })

        val byAppName = filterRuleGroups(sampleGroups(), RuleFilter.Domain, "Browser")
        assertEquals(listOf("com.example.browser"), byAppName.map { it.key })
        assertEquals(listOf("b-allow-dom"), byAppName[0].rules.map { it.id })

        val noMatch = filterRuleGroups(sampleGroups(), RuleFilter.Domain, "Game")
        assertTrue(noMatch.isEmpty())
    }
}

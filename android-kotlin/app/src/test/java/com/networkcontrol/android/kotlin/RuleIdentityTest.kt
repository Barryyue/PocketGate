package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.RuleIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RuleIdentityTest {
    private fun rule(
        id: String,
        action: VerdictAction = VerdictAction.Allow,
        path: String = "android-app://com.example.app",
        conditions: RuleConditions = RuleConditions(networkKinds = setOf(NetworkKind.Wifi, NetworkKind.Cellular)),
    ) = Rule(
        id = id,
        profileId = "profile-1",
        app = AppMatcher(path, requiredSignerId = "signer"),
        conditions = conditions,
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        createdAt = 100,
        updatedAt = 100,
    )

    @Test
    fun sameSemanticRuleHasSameKey() {
        assertEquals(RuleIdentity.key(rule("a")), RuleIdentity.key(rule("b")))
        assertEquals(
            RuleIdentity.key(rule("a")),
            RuleIdentity.key(rule("c", action = VerdictAction.Deny)),
        )
    }

    @Test
    fun networkSetOrderDoesNotMatter() {
        val first = rule("a", conditions = RuleConditions(networkKinds = setOf(NetworkKind.Wifi, NetworkKind.Cellular)))
        val second = rule("b", conditions = RuleConditions(networkKinds = setOf(NetworkKind.Cellular, NetworkKind.Wifi)))
        assertEquals(RuleIdentity.key(first), RuleIdentity.key(second))
    }

    @Test
    fun pathKeyIsCaseInsensitiveOnlyForAndroidApps() {
        assertEquals(
            RuleIdentity.appPathKey("android-app://com.example.App"),
            RuleIdentity.appPathKey("android-app://com.example.app"),
        )
        assertNotEquals(RuleIdentity.appPathKey("/opt/App"), RuleIdentity.appPathKey("/opt/app"))
    }

    @Test
    fun differentScopeHasDifferentKey() {
        assertNotEquals(
            RuleIdentity.key(rule("a")),
            RuleIdentity.key(rule("b", path = "android-app://com.other.app")),
        )
        assertNotEquals(
            RuleIdentity.key(rule("a")),
            RuleIdentity.key(rule("b", conditions = RuleConditions(networkKinds = setOf(NetworkKind.Wifi)))),
        )
    }

    @Test
    fun mergeKeepsIdentityButAppliesNewContent() {
        val existing = rule("keep", action = VerdictAction.Allow)
        val incoming = rule("new", action = VerdictAction.Deny)
        val merged = RuleIdentity.merge(existing, incoming, now = 500)
        assertEquals("keep", merged.id)
        assertEquals(100, merged.createdAt)
        assertEquals(500, merged.updatedAt)
        assertEquals(VerdictAction.Deny, merged.action)
    }
}

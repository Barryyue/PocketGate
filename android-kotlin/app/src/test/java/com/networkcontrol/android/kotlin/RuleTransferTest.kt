package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AppMatcher
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.PortRange
import com.networkcontrol.android.kotlin.core.model.Profile
import com.networkcontrol.android.kotlin.core.model.RemoteTarget
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleImportMode
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.data.transfer.RuleTransferService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class RuleTransferTest {
    private fun rule(
        id: String = "r1",
        action: VerdictAction = VerdictAction.Deny,
        profileId: String? = Profile.DEFAULT_ID,
    ) = Rule(
        id = id,
        profileId = profileId,
        app = AppMatcher("android-app://com.example.app", requiredSignerId = "signer-a"),
        conditions = RuleConditions(
            direction = Direction.Outbound,
            remotePort = PortRange.single(443),
            remote = RemoteTarget.Domain("*.example.com"),
        ),
        action = action,
        lifetime = RuleLifetime.Permanent,
        source = RuleSource.User,
        manualPriority = 2,
        note = "test",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
    )

    @Test
    fun exportAndImportRoundTrip() {
        val json = RuleTransferService.export(listOf(rule()))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        assertEquals(1, imported.size)
        val restored = imported.single()
        assertEquals("r1", restored.id)
        assertEquals(VerdictAction.Deny, restored.action)
        assertEquals(Profile.DEFAULT_ID, restored.profileId)
        assertEquals("android-app://com.example.app", restored.app?.pathPattern)
        assertEquals("*.example.com", (restored.conditions.remote as RemoteTarget.Domain).pattern)
        assertEquals(443, restored.conditions.remotePort?.from)
        assertEquals(RuleSource.User, restored.source)
        assertEquals(2, restored.manualPriority)
        assertEquals("test", restored.note)
        assertEquals(1_700_000_000_000, restored.createdAt)
    }

    @Test
    fun unknownProfileBecomesSharedRule() {
        val json = RuleTransferService.export(listOf(rule(profileId = "missing-profile")))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        assertEquals(null, imported.single().profileId)
    }

    @Test
    fun rejectsInvalidFormatAndVersion() {
        assertFailsWith<IllegalArgumentException> {
            RuleTransferService.importForPlan("{\"format\":\"other\",\"version\":1,\"rules\":[]}", listOf(Profile.Default))
        }
        assertFailsWith<IllegalArgumentException> {
            RuleTransferService.importForPlan("{\"format\":\"netcontrol-rules\",\"version\":9,\"rules\":[]}", listOf(Profile.Default))
        }
    }

    @Test
    fun mergePlanKeepsExistingAndAddsNew() {
        val existing = rule("r1")
        val json = RuleTransferService.export(listOf(rule("r1"), rule("r2")))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        val plan = RuleTransferService.plan(listOf(existing), imported, RuleImportMode.Merge)
        assertEquals(1, plan.toAdd.size)
        assertEquals("r2", plan.toAdd.single().id)
        assertEquals(1, plan.unchanged)
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test
    fun replacePlanRemovesRulesMissingFromImport() {
        val existing = rule("r1")
        val json = RuleTransferService.export(listOf(rule("r2")))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        val plan = RuleTransferService.plan(listOf(existing), imported, RuleImportMode.Replace)
        assertEquals(1, plan.toAdd.size)
        assertEquals(1, plan.toRemove.size)
        assertEquals("r1", plan.toRemove.single().id)
    }

    @Test
    fun changedRuleIsUpdatedNotAdded() {
        val existing = rule("r1", action = VerdictAction.Allow)
        val json = RuleTransferService.export(listOf(rule("r1", action = VerdictAction.Deny)))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        val plan = RuleTransferService.plan(listOf(existing), imported, RuleImportMode.Merge)
        assertTrue(plan.toAdd.isEmpty())
        assertEquals(1, plan.toUpdate.size)
        assertEquals("r1", plan.toUpdate.single().id)
        assertEquals(VerdictAction.Deny, plan.toUpdate.single().action)
    }

    @Test
    fun exportSkipsBuiltinRules() {
        val builtin = rule("builtin").copy(source = RuleSource.Builtin)
        val json = RuleTransferService.export(listOf(builtin))
        val imported = RuleTransferService.importForPlan(json, listOf(Profile.Default))
        assertEquals(0, imported.size)
    }

    @Test
    fun exportedJsonContainsHeader() {
        val json = RuleTransferService.export(listOf(rule()))
        assertTrue(json.contains("\"format\": \"netcontrol-rules\""))
        assertTrue(json.contains("\"version\": 1"))
        assertNotNull(json)
    }
}

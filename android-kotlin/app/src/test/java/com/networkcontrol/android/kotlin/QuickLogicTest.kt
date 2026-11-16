package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.platform.quick.QuickAction
import com.networkcontrol.android.kotlin.platform.quick.QuickActions
import com.networkcontrol.android.kotlin.platform.quick.QuickLogic
import com.networkcontrol.android.kotlin.platform.quick.QuickState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLogicTest {
    private val now = 1_000_000L

    private fun state(
        protectionOn: Boolean = true,
        mode: FilterMode = FilterMode.Ask,
        base: FilterMode = mode,
        focusEndsAt: Long? = null,
        appLock: Boolean = true,
    ) = QuickState(
        protectionOn = protectionOn,
        status = if (protectionOn) ProtectionStatus.On else ProtectionStatus.Off,
        mode = mode,
        baseMode = base,
        doNotDisturbUntil = null,
        focusEndsAt = focusEndsAt,
        pendingPrompts = 0,
        appLockEnabled = appLock,
    )

    @Test
    fun modeCyclesAskWhitelistBlacklist() {
        assertEquals(FilterMode.Whitelist, QuickLogic.nextMode(FilterMode.Ask))
        assertEquals(FilterMode.Blacklist, QuickLogic.nextMode(FilterMode.Whitelist))
        assertEquals(FilterMode.Ask, QuickLogic.nextMode(FilterMode.Blacklist))
        assertEquals(FilterMode.Ask, QuickLogic.nextMode(FilterMode.BlockAll))
    }

    @Test
    fun turningProtectionOffNeedsAuthButTurningOnDoesNot() {
        assertTrue(QuickLogic.requiresAuth(QuickAction.ToggleProtection, state(protectionOn = true), now))
        assertFalse(QuickLogic.requiresAuth(QuickAction.ToggleProtection, state(protectionOn = false), now))
    }

    @Test
    fun onlyLooseningModeChangesNeedAuth() {
        // 詢問 → 白名單更嚴：不需要驗證
        assertFalse(QuickLogic.requiresAuth(QuickAction.CycleMode, state(mode = FilterMode.Ask), now))
        // 白名單 → 黑名單更寬：需要驗證
        assertTrue(QuickLogic.requiresAuth(QuickAction.CycleMode, state(mode = FilterMode.Whitelist), now))
        // 黑名單 → 詢問更嚴：不需要驗證
        assertFalse(QuickLogic.requiresAuth(QuickAction.CycleMode, state(mode = FilterMode.Blacklist), now))
        // 離開全部阻止：需要驗證
        assertTrue(QuickLogic.requiresAuth(QuickAction.CycleMode, state(mode = FilterMode.BlockAll, base = FilterMode.Whitelist), now))
    }

    @Test
    fun endingBlockAllOrFocusNeedsAuthButStartingDoesNot() {
        assertTrue(QuickLogic.requiresAuth(QuickAction.ToggleBlockAll, state(mode = FilterMode.BlockAll), now))
        assertFalse(QuickLogic.requiresAuth(QuickAction.ToggleBlockAll, state(), now))
        assertTrue(QuickLogic.requiresAuth(QuickAction.ToggleFocus, state(focusEndsAt = now + 60_000), now))
        assertFalse(QuickLogic.requiresAuth(QuickAction.ToggleFocus, state(focusEndsAt = now - 1), now))
        assertFalse(QuickLogic.requiresAuth(QuickAction.ToggleDnd, state(), now))
    }

    @Test
    fun withoutAppLockNothingNeedsAuth() {
        QuickAction.entries.forEach { action ->
            assertFalse(QuickLogic.requiresAuth(action, state(appLock = false, mode = FilterMode.Whitelist), now))
        }
    }

    @Test
    fun focusTurnsProtectionOnWhenItIsOff() {
        assertTrue(QuickLogic.turnsProtectionOn(QuickAction.ToggleFocus, state(protectionOn = false), now))
        assertFalse(QuickLogic.turnsProtectionOn(QuickAction.ToggleFocus, state(protectionOn = true), now))
    }

    @Test
    fun actionIdsRoundTrip() {
        QuickAction.entries.forEach { assertEquals(it, QuickAction.fromId(it.id)) }
        assertEquals(null, QuickActions.fromIntent(null))
    }
}

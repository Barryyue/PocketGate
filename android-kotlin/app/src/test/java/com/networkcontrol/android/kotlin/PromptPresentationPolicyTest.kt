package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.platform.prompt.PresentationEnvironment
import com.networkcontrol.android.kotlin.platform.prompt.PromptCandidate
import com.networkcontrol.android.kotlin.platform.prompt.PromptPresentation
import com.networkcontrol.android.kotlin.platform.prompt.PromptPresentationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresentationPolicyTest {
    private val prompts = listOf(
        PromptCandidate("older", appUid = 10001, createdAt = 100),
        PromptCandidate("newer", appUid = 10002, createdAt = 200),
    )

    private fun environment(
        appUiVisible: Boolean = false,
        autoOverlay: Boolean = true,
        overlayAllowed: Boolean = true,
        screenInteractive: Boolean = true,
        keyguardLocked: Boolean = false,
        foregroundUid: Int? = 10002,
    ) = PresentationEnvironment(
        appUiVisible = appUiVisible,
        autoOverlay = autoOverlay,
        overlayAllowed = overlayAllowed,
        screenInteractive = screenInteractive,
        keyguardLocked = keyguardLocked,
        foregroundUid = foregroundUid,
    )

    @Test
    fun emptyPendingShowsNothing() {
        val selection = PromptPresentationPolicy.select(emptyList(), environment())
        assertEquals(PromptPresentation.None, selection.presentation)
        assertEquals(null, selection.promptId)
    }

    @Test
    fun inAppTakesPriorityAndUsesSoftNotification() {
        val selection = PromptPresentationPolicy.select(prompts, environment(appUiVisible = true))
        assertEquals(PromptPresentation.InApp, selection.presentation)
        assertEquals("older", selection.promptId)
        assertTrue(selection.softNotification)
    }

    @Test
    fun foregroundAppGetsOverlay() {
        val selection = PromptPresentationPolicy.select(prompts, environment(foregroundUid = 10002))
        assertEquals(PromptPresentation.Overlay, selection.presentation)
        assertEquals("newer", selection.promptId)
        assertTrue(selection.softNotification)
    }

    @Test
    fun backgroundPromptFallsBackToNotification() {
        val selection = PromptPresentationPolicy.select(prompts, environment(foregroundUid = 10099))
        assertEquals(PromptPresentation.Notification, selection.presentation)
        assertEquals("older", selection.promptId)
        assertEquals(false, selection.softNotification)
    }

    @Test
    fun overlayNeedsPermissionModeScreenAndUnlock() {
        val denied = PromptPresentationPolicy.select(prompts, environment(overlayAllowed = false))
        assertEquals(PromptPresentation.Notification, denied.presentation)
        val manual = PromptPresentationPolicy.select(prompts, environment(autoOverlay = false))
        assertEquals(PromptPresentation.Notification, manual.presentation)
        val locked = PromptPresentationPolicy.select(prompts, environment(keyguardLocked = true))
        assertEquals(PromptPresentation.Notification, locked.presentation)
        val screenOff = PromptPresentationPolicy.select(prompts, environment(screenInteractive = false))
        assertEquals(PromptPresentation.Notification, screenOff.presentation)
    }

    @Test
    fun unknownForegroundOrUnknownAppUidFallsBack() {
        val unknownForeground = PromptPresentationPolicy.select(prompts, environment(foregroundUid = null))
        assertEquals(PromptPresentation.Notification, unknownForeground.presentation)
        val noUid = listOf(PromptCandidate("unknown", appUid = null, createdAt = 1))
        val selection = PromptPresentationPolicy.select(noUid, environment())
        assertEquals(PromptPresentation.Notification, selection.presentation)
    }
}

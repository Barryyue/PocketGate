package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.apps.TargetAppSessionTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnceSessionGraceTest {
    private val tenMinutes = 10 * 60_000L

    @Test
    fun shortTripToBackgroundKeepsTheDecision() {
        assertFalse(TargetAppSessionTracker.leftLongEnough(leftAt = 1_000, createdAt = 0, now = 1_000 + 60_000, graceMs = tenMinutes))
    }

    @Test
    fun stayingInBackgroundPastTheGraceEndsIt() {
        assertTrue(TargetAppSessionTracker.leftLongEnough(leftAt = 1_000, createdAt = 0, now = 1_000 + tenMinutes, graceMs = tenMinutes))
    }

    @Test
    fun zeroGraceStillWaitsTwoSeconds() {
        assertFalse(TargetAppSessionTracker.leftLongEnough(leftAt = 1_000, createdAt = 0, now = 2_500, graceMs = 0))
        assertTrue(TargetAppSessionTracker.leftLongEnough(leftAt = 1_000, createdAt = 0, now = 3_000, graceMs = 0))
    }

    @Test
    fun leavingBeforeTheRuleExistedDoesNotCount() {
        assertFalse(TargetAppSessionTracker.leftLongEnough(leftAt = 500, createdAt = 1_000, now = 1_000 + tenMinutes * 2, graceMs = tenMinutes))
        assertFalse(TargetAppSessionTracker.leftLongEnough(leftAt = null, createdAt = 0, now = tenMinutes * 2, graceMs = tenMinutes))
    }
}

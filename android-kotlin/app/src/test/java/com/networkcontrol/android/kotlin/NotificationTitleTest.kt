package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.vpn.notificationTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTitleTest {
    @Test
    fun blankCustomFallsBackToLocalizedDefault() {
        assertEquals("Protection is on", notificationTitle("", "Protection is on"))
        assertEquals("Protection is on", notificationTitle("   ", "Protection is on"))
    }

    @Test
    fun customTitleIsTrimmedAndUsed() {
        assertEquals("My guard", notificationTitle("  My guard  ", "Protection is on"))
    }
}

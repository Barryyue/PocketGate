package com.networkcontrol.android.kotlin.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconSwitcherTest {
    @Test
    fun fromKeyResolvesKnownAndFallsBackToDark() {
        assertEquals(AppLauncherIcon.Dark, AppLauncherIcon.fromKey("dark"))
        assertEquals(AppLauncherIcon.Light, AppLauncherIcon.fromKey("light"))
        assertEquals(AppLauncherIcon.Light, AppLauncherIcon.fromKey("LIGHT"))
        assertEquals(AppLauncherIcon.Dark, AppLauncherIcon.fromKey(null))
        assertEquals(AppLauncherIcon.Dark, AppLauncherIcon.fromKey("unknown"))
    }

    @Test
    fun aliasClassNamesAreUniqueAndQualified() {
        val names = AppLauncherIcon.entries.map { it.aliasClassName }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("com.networkcontrol.android.kotlin.") })
    }
}

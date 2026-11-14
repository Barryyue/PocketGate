package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.update.compareVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesNumericVersionSegments() {
        assertTrue(compareVersions("0.4.0", "0.3.0") > 0)
        assertEquals(0, compareVersions("0.4.0", "0.4"))
        assertTrue(compareVersions("0.4.1", "0.4.0") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
    }
}

package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.focus.FocusSchedule
import com.networkcontrol.android.kotlin.core.focus.contains
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScheduleTest {
    @Test
    fun sameDayWindowMatchesInsideOnly() {
        val schedule = FocusSchedule(startMinute = 9 * 60, endMinute = 18 * 60)
        assertTrue(schedule.contains(9 * 60))
        assertTrue(schedule.contains(12 * 60))
        assertFalse(schedule.contains(18 * 60))
        assertFalse(schedule.contains(8 * 60 + 59))
    }

    @Test
    fun overnightWindowWrapsMidnight() {
        val schedule = FocusSchedule(startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(schedule.crossesMidnight)
        assertTrue(schedule.contains(23 * 60))
        assertTrue(schedule.contains(2 * 60))
        assertFalse(schedule.contains(12 * 60))
    }
}

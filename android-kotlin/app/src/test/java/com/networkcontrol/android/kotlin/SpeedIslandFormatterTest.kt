package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.island.SpeedIslandFormatter
import com.networkcontrol.android.kotlin.core.island.SpeedIslandLabels
import com.networkcontrol.android.kotlin.core.island.SpeedIslandStats
import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedIslandFormatterTest {
    private val labels = SpeedIslandLabels(
        blocked = "Blocked",
        pending = "Pending",
        networkFallback = "Network",
    )
    private val stats = SpeedIslandStats(
        downloadBytesPerSecond = 1_258_291,
        uploadBytesPerSecond = 24_576,
        todayBytesIn = 1_932_735_283,
        todayBytesOut = 335_544_320,
        blockedToday = 12,
        pendingPrompts = 2,
        networkName = "Wi-Fi",
    )

    private fun format(
        content: SpeedIslandContent = SpeedIslandContent.Speed,
        mode: SpeedIslandMode = SpeedIslandMode.Full,
        swap: Boolean = false,
        showDownload: Boolean = true,
        value: SpeedIslandStats = stats,
    ) = SpeedIslandFormatter.format(content, mode, value, swap, showDownload, labels)

    @Test
    fun fullSpeedShowsDownloadFirstByDefault() {
        assertEquals("↓ 1.2 MB/s   ↑ 24.0 KB/s", format())
    }

    @Test
    fun swapOrderPutsUploadFirst() {
        assertEquals("↑ 24.0 KB/s   ↓ 1.2 MB/s", format(swap = true))
    }

    @Test
    fun miniUsesFullUnits() {
        assertEquals("↓1.2 MB/s ↑24.0 KB/s", format(mode = SpeedIslandMode.Mini))
    }

    @Test
    fun cycleAlternatesDirectionAndHonoursSwap() {
        assertEquals("↓ 1.2 MB/s", format(mode = SpeedIslandMode.Cycle, showDownload = true))
        assertEquals("↑ 24.0 KB/s", format(mode = SpeedIslandMode.Cycle, showDownload = false))
        assertEquals(
            "↑ 24.0 KB/s",
            format(mode = SpeedIslandMode.Cycle, swap = true, showDownload = true),
        )
    }

    @Test
    fun trafficUsesTodayTotals() {
        assertEquals("↓ 1.8 GB   ↑ 320.0 MB", format(content = SpeedIslandContent.Traffic))
        assertEquals(
            "↓1.8 GB ↑320.0 MB",
            format(content = SpeedIslandContent.Traffic, mode = SpeedIslandMode.Mini),
        )
    }

    @Test
    fun blockedAndPendingUseLocalizedLabels() {
        assertEquals("Blocked 12", format(content = SpeedIslandContent.Blocked))
        assertEquals("Pending 2", format(content = SpeedIslandContent.Pending))
        assertEquals(
            "Pending 2",
            format(content = SpeedIslandContent.Pending, mode = SpeedIslandMode.Mini),
        )
    }

    @Test
    fun networkFallsBackWhenUnknown() {
        assertEquals("Wi-Fi", format(content = SpeedIslandContent.Network))
        assertEquals(
            "Network",
            format(
                content = SpeedIslandContent.Network,
                value = stats.copy(networkName = null),
            ),
        )
    }

    @Test
    fun shouldFlipCycleWaitsForConfiguredInterval() {
        // 首次呼叫尚未記錄上次切換時間時不應翻轉
        assertFalse(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 0L, nowMs = 5_000L, intervalMs = 3_000L))
        // 未滿 3 秒（例如非定時 refresh）不應提前翻轉
        assertFalse(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 1_000L, nowMs = 3_999L, intervalMs = 3_000L))
        // 達到或超過 3 秒時翻轉
        assertTrue(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 1_000L, nowMs = 4_000L, intervalMs = 3_000L))
        assertTrue(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 1_000L, nowMs = 6_500L, intervalMs = 3_000L))
        // 異常過小的 intervalMs 會被夾緊為至少 1 秒
        assertFalse(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 1_000L, nowMs = 1_500L, intervalMs = 200L))
        assertTrue(SpeedIslandFormatter.shouldFlipCycle(lastSwitchMs = 1_000L, nowMs = 2_000L, intervalMs = 200L))
    }
}

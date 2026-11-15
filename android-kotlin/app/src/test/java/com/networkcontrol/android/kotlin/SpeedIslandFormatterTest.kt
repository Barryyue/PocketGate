package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.island.SpeedIslandFormatter
import com.networkcontrol.android.kotlin.core.island.SpeedIslandLabels
import com.networkcontrol.android.kotlin.core.island.SpeedIslandStats
import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import org.junit.Assert.assertEquals
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
    fun miniUsesCompactUnits() {
        assertEquals("↓1.2M ↑24K", format(mode = SpeedIslandMode.Mini))
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
            "↓1.8G ↑320M",
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
}

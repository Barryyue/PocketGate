package com.networkcontrol.android.kotlin.core.island

import com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode

/** 靈動島一次顯示所需的數據。 */
data class SpeedIslandStats(
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val todayBytesIn: Long = 0,
    val todayBytesOut: Long = 0,
    val blockedToday: Long = 0,
    val pendingPrompts: Int = 0,
    val networkName: String? = null,
)

/** 需要本地化的標籤，由界面層提供。 */
data class SpeedIslandLabels(
    val blocked: String,
    val pending: String,
    val networkFallback: String,
)

/**
 * 靈動島文字排版。純函數，方便 JVM 測試；不依賴 Android。
 */
object SpeedIslandFormatter {
    fun format(
        content: SpeedIslandContent,
        mode: SpeedIslandMode,
        stats: SpeedIslandStats,
        swapOrder: Boolean,
        showDownload: Boolean,
        labels: SpeedIslandLabels,
    ): String = when (content) {
        SpeedIslandContent.Speed -> formatSpeed(mode, stats, swapOrder, showDownload)
        SpeedIslandContent.Traffic -> formatTraffic(mode, stats, swapOrder, showDownload)
        SpeedIslandContent.Blocked -> "${labels.blocked} ${stats.blockedToday}"
        SpeedIslandContent.Pending -> "${labels.pending} ${stats.pendingPrompts}"
        SpeedIslandContent.Network -> stats.networkName?.takeIf { it.isNotBlank() }
            ?: labels.networkFallback
    }

    private fun formatSpeed(
        mode: SpeedIslandMode,
        stats: SpeedIslandStats,
        swapOrder: Boolean,
        showDownload: Boolean,
    ): String = when (mode) {
        SpeedIslandMode.Full -> pair(
            formatRate(stats.downloadBytesPerSecond) + "/s",
            formatRate(stats.uploadBytesPerSecond) + "/s",
            swapOrder,
        )
        SpeedIslandMode.Cycle -> {
            val download = if (swapOrder) !showDownload else showDownload
            if (download) {
                "↓ ${formatRate(stats.downloadBytesPerSecond)}/s"
            } else {
                "↑ ${formatRate(stats.uploadBytesPerSecond)}/s"
            }
        }
        SpeedIslandMode.Mini -> pair(
            formatRateShort(stats.downloadBytesPerSecond),
            formatRateShort(stats.uploadBytesPerSecond),
            swapOrder,
            compact = true,
        )
    }

    private fun formatTraffic(
        mode: SpeedIslandMode,
        stats: SpeedIslandStats,
        swapOrder: Boolean,
        showDownload: Boolean,
    ): String = when (mode) {
        SpeedIslandMode.Full -> pair(
            formatRate(stats.todayBytesIn),
            formatRate(stats.todayBytesOut),
            swapOrder,
        )
        SpeedIslandMode.Cycle -> {
            val download = if (swapOrder) !showDownload else showDownload
            if (download) {
                "↓ ${formatRate(stats.todayBytesIn)}"
            } else {
                "↑ ${formatRate(stats.todayBytesOut)}"
            }
        }
        SpeedIslandMode.Mini -> pair(
            formatRateShort(stats.todayBytesIn),
            formatRateShort(stats.todayBytesOut),
            swapOrder,
            compact = true,
        )
    }

    private fun pair(download: String, upload: String, swap: Boolean, compact: Boolean = false): String =
        if (compact) {
            if (swap) "↑$upload ↓$download" else "↓$download ↑$upload"
        } else {
            if (swap) "↑ $upload   ↓ $download" else "↓ $download   ↑ $upload"
        }

    fun formatRate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(value, units[index])
    }

    fun formatRateShort(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("K", "M", "G", "T")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (value >= 10) {
            "${value.toInt()}${units[index]}"
        } else {
            "%.1f%s".format(value, units[index])
        }
    }
}

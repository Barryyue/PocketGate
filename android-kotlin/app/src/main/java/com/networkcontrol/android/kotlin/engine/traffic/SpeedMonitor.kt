package com.networkcontrol.android.kotlin.engine.traffic

import android.net.TrafficStats
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpeedSnapshot(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
) {
    companion object {
        val Zero = SpeedSnapshot(0, 0)
    }
}

/**
 * 以 TrafficStats 的裝置總流量差值估算即時上下行速度。
 * 2 秒取樣一次；只用於顯示，不參與統計或判決。
 */
class SpeedMonitor {
    private val _speed = MutableStateFlow(SpeedSnapshot.Zero)
    val speed: StateFlow<SpeedSnapshot> = _speed.asStateFlow()

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (scope.isActive) {
                sample()
                delay(2_000)
            }
        }
    }

    @Synchronized
    private fun sample() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) {
            _speed.value = SpeedSnapshot.Zero
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRx < 0 || lastAt == 0L) {
            lastRx = rx
            lastTx = tx
            lastAt = now
            _speed.value = SpeedSnapshot.Zero
            return
        }
        val elapsed = (now - lastAt).coerceAtLeast(1)
        val down = ((rx - lastRx).coerceAtLeast(0) * 1000L / elapsed)
        val up = ((tx - lastTx).coerceAtLeast(0) * 1000L / elapsed)
        lastRx = rx
        lastTx = tx
        lastAt = now
        _speed.value = SpeedSnapshot(down, up)
    }
}

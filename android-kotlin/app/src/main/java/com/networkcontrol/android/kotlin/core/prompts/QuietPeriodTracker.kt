package com.networkcontrol.android.kotlin.core.prompts

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.QuietPeriodSummary
import com.networkcontrol.android.kotlin.core.settings.RuntimeStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 勿擾期間的阻斷彙總：只統計以「勿擾」為原因的判決。 */
class QuietPeriodTracker(
    private val runtime: RuntimeStateService,
    scope: CoroutineScope,
) {
    private val _summary = MutableStateFlow<QuietPeriodSummary?>(null)
    val summary: StateFlow<QuietPeriodSummary?> = _summary.asStateFlow()

    private var startedAt: Long? = null
    private var count = 0
    private val byApp = linkedMapOf<String, Int>()

    init {
        scope.launch {
            runtime.state.collect {
                applyState()
            }
        }
        // 勿擾到期不一定改變 RuntimeState，補一個低頻檢查。
        scope.launch {
            while (scope.isActive) {
                delay(5_000)
                applyState()
            }
        }
    }

    @Synchronized
    fun onBlocked(appName: String?) {
        if (startedAt == null && runtime.isDoNotDisturb) {
            startLocked()
        }
        if (startedAt == null) return
        count++
        val name = appName ?: "unknown"
        byApp[name] = (byApp[name] ?: 0) + 1
    }

    fun dismiss() {
        _summary.value = null
    }

    @Synchronized
    private fun applyState() {
        val active = runtime.isDoNotDisturb
        if (active && startedAt == null) {
            startLocked()
        } else if (!active && startedAt != null) {
            finishLocked()
        }
    }

    private fun startLocked() {
        startedAt = Time.now()
        count = 0
        byApp.clear()
    }

    private fun finishLocked() {
        val start = startedAt ?: return
        startedAt = null
        if (count > 0) {
            _summary.value = QuietPeriodSummary(
                startedAt = start,
                endedAt = Time.now(),
                blockedCount = count,
                topApps = byApp.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            )
        }
        count = 0
        byApp.clear()
    }
}

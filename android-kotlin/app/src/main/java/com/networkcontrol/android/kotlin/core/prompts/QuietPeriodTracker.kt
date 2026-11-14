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
            while (scope.isActive) {
                val active = runtime.isDoNotDisturb
                if (active && startedAt == null) {
                    startedAt = Time.now()
                    count = 0
                    byApp.clear()
                } else if (!active && startedAt != null) {
                    finish()
                }
                delay(5_000)
            }
        }
    }

    fun onBlocked(appName: String?) {
        if (startedAt == null) return
        count++
        val name = appName ?: "unknown"
        byApp[name] = (byApp[name] ?: 0) + 1
    }

    fun dismiss() {
        _summary.value = null
    }

    private fun finish() {
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

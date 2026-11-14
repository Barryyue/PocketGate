package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 定期移除過期規則，並把命中次數寫回數據庫。 */
class RuleLifetimeManager(
    private val rules: RuleService,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var lastFlush = Time.now()

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(1_000)
                runCatching { rules.removeExpired() }
                val now = Time.now()
                if (now - lastFlush >= 60_000) {
                    runCatching { rules.flushHits() }
                    lastFlush = now
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

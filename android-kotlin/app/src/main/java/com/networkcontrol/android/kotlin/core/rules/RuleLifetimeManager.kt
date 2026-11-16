package com.networkcontrol.android.kotlin.core.rules

import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 移除過期規則，並把命中次數寫回數據庫。
 * 不再每秒輪詢：睡到下一條限時規則到期（最多一分鐘，用於寫回命中次數），規則集變更時提前醒來重算。
 */
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
                runCatching { rules.removeExpired() }
                val now = Time.now()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    runCatching { rules.flushHits() }
                    lastFlush = now
                }
                val wait = nextWakeDelay(now)
                withTimeoutOrNull(wait) { rules.ruleSet.drop(1).first() }
            }
        }
    }

    internal fun nextWakeDelay(now: Long): Long {
        val nextExpiry = rules.all
            .filter { it.lifetime == RuleLifetime.Timed }
            .mapNotNull { it.expiresAt }
            .filter { it > now }
            .minOrNull()
        val untilFlush = (lastFlush + FLUSH_INTERVAL_MS - now).coerceAtLeast(MIN_WAIT_MS)
        val untilExpiry = nextExpiry?.let { (it - now).coerceAtLeast(MIN_WAIT_MS) } ?: Long.MAX_VALUE
        return minOf(untilFlush, untilExpiry)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 60_000L
        const val MIN_WAIT_MS = 50L
    }
}

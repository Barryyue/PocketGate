package com.networkcontrol.android.kotlin.engine.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleEndReason
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.rules.RuleService
import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「允許一次」的應用會話追蹤：
 * - 規則建立後，目標應用第一次回到前台時標記 seen；
 * - 目標應用的所有 Activity 都離開前台（ACTIVITY_STOPPED）2 秒後移除規則；
 *   應用內切換頁面不算離開（見 [AppForegroundState]）；
 * - 從未回到前台、或離開時沒有收到 STOPPED 的規則 10 分鐘後回收，避免背景同步讓規則永久殘留。
 *
 * 這是 Android 上可穩定取得的近似：一般應用無法可靠讀取其他進程的死亡事件，
 * 因此以 UsageStats 的 Activity STOPPED 作為「關閉應用」信號。
 */
class TargetAppSessionTracker(
    private val context: Context,
    private val rules: RuleService,
    private val scope: CoroutineScope,
) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var lastQuery = 0L
    private val state = AppForegroundState()
    private var noAccessLogged = false

    init {
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { AppLog.w("session", "tick failed: ${it.message}") }
                delay(2_000)
            }
        }
    }

    private suspend fun tick() {
        val targets = rules.all.filter { it.lifetime == RuleLifetime.UntilAppExit }
        if (targets.isEmpty()) {
            // 沒有目標時不查詢事件；下次有目標時從頭重建前台狀態，避免沿用過期的狀態。
            state.clear()
            lastQuery = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (!hasUsageAccess()) {
            if (!noAccessLogged) {
                noAccessLogged = true
                AppLog.w("session", "usage access missing; allow-once rules stay until app restart")
            }
            return
        }
        val manager = usage ?: return
        val start = if (lastQuery == 0L) {
            state.onScreenInteractive(power?.isInteractive != false)
            now - LOOKBACK_MS
        } else {
            maxOf(lastQuery - 1_000L, now - LOOKBACK_MS)
        }
        lastQuery = now
        runCatching {
            val events = manager.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                apply(event)
            }
        }

        val remove = mutableListOf<String>()
        targets.forEach { rule ->
            val packageName = packageOf(rule) ?: return@forEach
            if (state.isForeground(packageName)) return@forEach
            val left = state.leftAt(packageName)
            if (left != null && left >= rule.createdAt && now - left >= LEAVE_GRACE_MS) {
                remove += rule.id
                return@forEach
            }
            // 背景詢問：等使用者真的打開這個應用，或 10 分鐘後回收。
            if (now - rule.createdAt > ORPHAN_MS) remove += rule.id
        }
        if (remove.isNotEmpty()) {
            rules.remove(remove, RuleEndReason.Expired)
            AppLog.i("session", "removed ${remove.size} allow-once rules after app left foreground")
        }
        state.retainLeft(targets.mapNotNull(::packageOf).toSet())
    }

    private fun apply(event: UsageEvents.Event) {
        val packageName = event.packageName
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED ->
                if (packageName != null) state.onResumed(packageName, event.className)
            UsageEvents.Event.ACTIVITY_PAUSED ->
                if (packageName != null) {
                    state.onPausedOrStopped(packageName, event.className, event.timeStamp, stopped = false)
                }
            UsageEvents.Event.ACTIVITY_STOPPED ->
                if (packageName != null) {
                    state.onPausedOrStopped(packageName, event.className, event.timeStamp, stopped = true)
                }
            UsageEvents.Event.SCREEN_INTERACTIVE -> state.onScreenInteractive(true)
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                state.onScreenInteractive(false)
                state.onForegroundReset()
            }
            UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN,
            -> state.onForegroundReset()
        }
    }

    private fun packageOf(rule: Rule): String? =
        rule.app?.pathPattern
            ?.takeIf { it.startsWith("android-app://") }
            ?.removePrefix("android-app://")

    private fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private companion object {
        const val LOOKBACK_MS = 5 * 60_000L
        const val LEAVE_GRACE_MS = 2_000L
        const val ORPHAN_MS = 10 * 60_000L
    }
}

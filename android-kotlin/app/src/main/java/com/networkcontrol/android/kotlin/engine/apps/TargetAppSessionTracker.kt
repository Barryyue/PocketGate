package com.networkcontrol.android.kotlin.engine.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
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
 * - 目標應用 ACTIVITY_STOPPED（離開前台/關閉）2 秒後移除規則；
 * - 從未回到前台的規則 10 分鐘後回收，避免背景同步讓規則永久殘留。
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
    private val foreground = HashSet<String>()
    private val stoppedAt = HashMap<String, Long>()
    private val seen = HashSet<String>()
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
            seen.clear()
            stoppedAt.clear()
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
        val start = if (lastQuery == 0L) now - 60_000L else maxOf(lastQuery - 1_000L, now - 5 * 60_000L)
        lastQuery = now
        runCatching {
            val events = manager.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foreground += packageName
                        stoppedAt.remove(packageName)
                    }
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        foreground -= packageName
                        if (power?.isInteractive != false) {
                            stoppedAt[packageName] = event.timeStamp
                        }
                    }
                }
            }
        }

        val remove = mutableListOf<String>()
        targets.forEach { rule ->
            val packageName = rule.app?.pathPattern
                ?.takeIf { it.startsWith("android-app://") }
                ?.removePrefix("android-app://")
                ?: return@forEach
            if (packageName in foreground) {
                seen += packageName
                return@forEach
            }
            val stopped = stoppedAt[packageName]
            if (stopped != null && stopped >= rule.createdAt && now - stopped >= 2_000L) {
                remove += rule.id
                return@forEach
            }
            if (packageName !in seen) {
                // 背景詢問：等使用者真的打開這個應用，或 10 分鐘後回收。
                if (now - rule.createdAt > 10 * 60_000L) remove += rule.id
                return@forEach
            }
            if (now - rule.createdAt > 10 * 60_000L) {
                remove += rule.id
            }
        }
        if (remove.isNotEmpty()) {
            rules.remove(remove, RuleEndReason.Expired)
            AppLog.i("session", "removed ${remove.size} allow-once rules after app left foreground")
        }
        val activePackages = targets.mapNotNull {
            it.app?.pathPattern?.takeIf { path -> path.startsWith("android-app://") }
                ?.removePrefix("android-app://")
        }.toSet()
        seen.retainAll(activePackages)
        stoppedAt.keys.retainAll(activePackages)
    }

    private fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

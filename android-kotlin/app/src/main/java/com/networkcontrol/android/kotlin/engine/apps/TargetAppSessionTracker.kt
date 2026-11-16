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
 * - 目標應用的所有 Activity 都離開前台（ACTIVITY_STOPPED）並在背景停留超過
 *   [backgroundGraceMs]（使用者設定，預設 10 分鐘；0 時為 2 秒）後移除規則，
 *   期間回到前台不會重新詢問；應用內切換頁面不算離開（見 [AppForegroundState]）；
 * - 從未回到前台、或離開時沒有收到 STOPPED 的規則 10 分鐘後回收，避免背景同步讓規則永久殘留。
 *
 * 這是 Android 上可穩定取得的近似：一般應用無法可靠讀取其他進程的死亡事件，
 * 從最近任務劃掉應用也只會記錄 ACTIVITY_STOPPED（系統把 DESTROYED 轉成 STOPPED），
 * 因此以「離開前台且在背景停留一段時間」作為「關閉應用」信號。
 *
 * 使用者用 adb 授予 DUMP 權限後（見 [ProcessExitWatcher]），改用真正的進程結束記錄：
 * 目標應用的主進程在規則建立後結束才移除規則，切到背景多久都不影響（與紅魔的「直到應用關閉」一致）。
 */
class TargetAppSessionTracker(
    private val context: Context,
    private val rules: RuleService,
    private val scope: CoroutineScope,
    private val backgroundGraceMs: () -> Long = { LEAVE_GRACE_MS },
) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    val processExits = ProcessExitWatcher(context)
    private var lastQuery = 0L
    private val state = AppForegroundState()

    /** 「僅一次」因應用離開前台而撤銷時呼叫（參數為套件名稱）。 */
    @Volatile
    var onRevokedAfterLeaving: ((String) -> Unit)? = null

    /** 規則建立後出現在前台過的應用：之後只有「真正離開」才撤銷，不再按「從未打開」回收。 */
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
            // 沒有目標時不查詢事件；下次有目標時從頭重建前台狀態，避免沿用過期的狀態。
            state.clear()
            seen.clear()
            lastQuery = 0L
            return
        }
        val now = System.currentTimeMillis()
        val exitAware = processExits.available
        if (exitAware) {
            // 主進程在規則建立後結束 = 應用已關閉；不需要使用情況權限。
            val closed = targets.filter { rule ->
                val packageName = packageOf(rule) ?: return@filter false
                val exitedAt = processExits.lastMainProcessExit(packageName)
                exitedAt != null && exitedAt >= rule.createdAt
            }
            if (closed.isNotEmpty()) {
                rules.remove(closed.map { it.id }, RuleEndReason.Expired)
                closed.mapNotNull(::packageOf).toSet().forEach { onRevokedAfterLeaving?.invoke(it) }
                AppLog.i("session", "removed ${closed.size} until-app-exit rules after the app process ended")
                return
            }
        }
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
            // 首次建立狀態要回看夠久：使用者可能已經在應用裡停留很久才回答詢問，
            // 只看最近幾分鐘會不知道它在前台，10 分鐘後把正在使用的應用的規則當成孤兒回收。
            now - INITIAL_LOOKBACK_MS
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
        val leftPackages = linkedSetOf<String>()
        targets.forEach { rule ->
            val packageName = packageOf(rule) ?: return@forEach
            if (state.isForeground(packageName)) {
                seen += packageName
                return@forEach
            }
            val left = state.leftAt(packageName)
            if (!exitAware && leftLongEnough(left, rule.createdAt, now, backgroundGraceMs())) {
                remove += rule.id
                leftPackages += packageName
                return@forEach
            }
            // 背景詢問：應用從未出現在前台時，10 分鐘後回收。
            // 出現過（例如之後關屏很久）則保留，直到真正離開應用。
            if (packageName !in seen && now - rule.createdAt > ORPHAN_MS) remove += rule.id
        }
        if (remove.isNotEmpty()) {
            rules.remove(remove, RuleEndReason.Expired)
            leftPackages.forEach { packageName -> onRevokedAfterLeaving?.invoke(packageName) }
            AppLog.i("session", "removed ${remove.size} until-app-exit rules after app left foreground")
        }
        val active = targets.mapNotNull(::packageOf).toSet()
        state.retainLeft(active)
        seen.retainAll(active)
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

    companion object {
        /** 規則建立後應用離開前台，且在背景停留超過寬限時間（至少 2 秒）。 */
        fun leftLongEnough(leftAt: Long?, createdAt: Long, now: Long, graceMs: Long): Boolean =
            leftAt != null && leftAt >= createdAt && now - leftAt >= maxOf(LEAVE_GRACE_MS, graceMs)

        const val LOOKBACK_MS = 5 * 60_000L
        const val INITIAL_LOOKBACK_MS = 6 * 60 * 60_000L
        const val LEAVE_GRACE_MS = 2_000L
        const val ORPHAN_MS = 10 * 60_000L
    }
}

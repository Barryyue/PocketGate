package com.networkcontrol.android.kotlin.platform.prompt

import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.prompts.PromptBroker
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import com.networkcontrol.android.kotlin.platform.PromptNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 詢問呈現的唯一協調者：決定應用內卡片、系統懸浮面板或通知。
 * PromptNotifier 與 PromptOverlayController 只接受命令，不自己訂閱 PromptBroker。
 */
class PromptRouter(
    private val scope: CoroutineScope,
    private val broker: PromptBroker,
    private val notifier: PromptNotifier,
    private val overlay: PromptOverlayController,
    private val uiVisibility: AppUiVisibility,
    private val tracker: PromptForegroundTracker,
    private val protection: ProtectionController,
    private val preferences: AppPreferences,
    private val timedMinutes: () -> Int = { 60 },
) {
    private val gate = Mutex()
    private var started = false
    private var lastPendingIds: Set<String> = emptySet()

    fun start() {
        if (started) return
        started = true
        uiVisibility.onChange = { render() }
        overlay.onAnswer = { id, action, lifetime ->
            scope.launch {
                broker.answer(
                    id,
                    PromptDecision(
                        action = action,
                        lifetime = lifetime,
                        durationSeconds = if (lifetime == RuleLifetime.Timed) timedMinutes() * 60 else null,
                    ),
                )
            }
            render()
        }
        overlay.onDismiss = { id ->
            com.networkcontrol.android.kotlin.platform.AppLog.d("prompt", "later tapped $id")
            scope.launch { broker.dismiss(id) }
            render()
        }
        overlay.onHold = { id ->
            scope.launch { broker.pauseTimer(id) }
        }
        scope.launch { broker.pending.collect { render() } }
        scope.launch { protection.enabled.collect { render() } }
        scope.launch(Dispatchers.Default) {
            var suppressedSince = 0L
            while (isActive) {
                if (!broker.busy.value) {
                    // 沒有詢問也沒有「稍後」抑制：不輪詢前台應用，等到有工作再繼續。
                    suppressedSince = 0L
                    broker.busy.first { it }
                }
                if (broker.pending.value.isNotEmpty()) render()
                val quiet = broker.backgroundQuietPackages()
                if (quiet.isNotEmpty()) {
                    if (tracker.isAvailable()) {
                        tracker.foregroundPackage()
                        quiet.filter { tracker.isForeground(it) }.forEach { broker.endBackgroundQuiet(it) }
                    } else {
                        // 沒有使用情況權限時無法知道它何時回來，直接恢復詢問。
                        quiet.forEach { broker.endBackgroundQuiet(it) }
                    }
                }
                if (broker.hasSessionSuppressions()) {
                    if (!tracker.isAvailable()) {
                        if (suppressedSince == 0L) {
                            suppressedSince = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - suppressedSince > 60_000L) {
                            broker.clearAllSessionSuppressions()
                            suppressedSince = 0L
                        }
                    } else {
                        suppressedSince = 0L
                        // 刷新 UsageStats 事件，再看被抑制的應用是否在抑制之後離開過前台
                        // （回桌面、切到其他應用、從最近任務劃掉都算；應用內換頁、權限對話框、關屏不算）。
                        tracker.foregroundPackage()
                        broker.sessionSuppressions().forEach { (packageName, since) ->
                            val left = tracker.leftAt(packageName)
                            if (left != null && left >= since) {
                                com.networkcontrol.android.kotlin.platform.AppLog.i("prompt", "later cleared for $packageName: left foreground ${left - since}ms after suppression")
                                broker.clearSessionSuppression(packageName)
                            }
                        }
                    }
                } else {
                    suppressedSince = 0L
                }
                delay(1_000)
            }
        }
        render()
    }

    fun refresh() {
        render()
    }

    private fun render() {
        scope.launch(Dispatchers.Default) {
            gate.withLock { renderInternal() }
        }
    }

    private fun renderInternal() {
        val pending = broker.pending.value
        if (pending.isEmpty()) {
            overlay.hide()
            notifier.cancelAll()
            lastPendingIds = emptySet()
            return
        }
        val candidates = pending.map { prompt ->
            PromptCandidate(prompt.id, uidFor(prompt), prompt.createdAt)
        }
        val uiVisible = uiVisibility.visible
        val environment = PresentationEnvironment(
            appUiVisible = uiVisible,
            autoOverlay = preferences.promptPresentationMode == "auto",
            overlayAllowed = overlay.canDrawOverlays(),
            screenInteractive = tracker.isScreenInteractive(),
            keyguardLocked = tracker.isKeyguardLocked(),
            foregroundUid = if (uiVisible) null else tracker.foregroundUid(),
        )
        val selection = PromptPresentationPolicy.select(candidates, environment)
        val selected = pending.firstOrNull { it.id == selection.promptId }
        if (selection.presentation == PromptPresentation.Overlay && selected != null) {
            overlay.show(selected)
        } else {
            overlay.hide()
        }

        val pendingIds = pending.map { it.id }.toSet()
        (lastPendingIds - pendingIds).forEach { notifier.cancel(it) }
        lastPendingIds = pendingIds
        pending.forEach { prompt ->
            val isShown = prompt.id == selection.promptId
            val soft = isShown && selection.softNotification
            notifier.render(prompt, soft)
        }
    }

    private fun uidFor(prompt: PromptSnapshot): Int? {
        val path = prompt.app?.path ?: prompt.executablePath
        if (path == null) return null
        val packageName = path.removePrefix("android-app://")
        if (path.startsWith("android-app://uid:")) {
            return packageName.removePrefix("uid:").toIntOrNull()
        }
        return tracker.uidForPackage(packageName)
    }
}

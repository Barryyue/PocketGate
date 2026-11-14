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
                        durationSeconds = if (lifetime == RuleLifetime.Timed) 3600 else null,
                    ),
                )
            }
            render()
        }
        overlay.onDismiss = { id ->
            scope.launch { broker.dismiss(id) }
            render()
        }
        overlay.onHold = { id ->
            scope.launch { broker.pauseTimer(id) }
        }
        scope.launch { broker.pending.collect { render() } }
        scope.launch { protection.enabled.collect { render() } }
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (broker.pending.value.isNotEmpty()) render()
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

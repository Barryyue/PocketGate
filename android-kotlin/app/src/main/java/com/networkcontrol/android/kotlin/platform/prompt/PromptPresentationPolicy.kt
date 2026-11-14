package com.networkcontrol.android.kotlin.platform.prompt

/**
 * 詢問呈現的純 Kotlin 決策，方便單元測試。
 * 不依賴 Android；由 PromptRouter 提供環境。
 */
data class PromptCandidate(
    val id: String,
    val appUid: Int?,
    val createdAt: Long,
)

data class PresentationEnvironment(
    val appUiVisible: Boolean,
    val autoOverlay: Boolean,
    val overlayAllowed: Boolean,
    val screenInteractive: Boolean,
    val keyguardLocked: Boolean,
    val foregroundUid: Int?,
)

enum class PromptPresentation { None, InApp, Overlay, Notification }

data class PromptSelection(
    val presentation: PromptPresentation,
    val promptId: String?,
    val softNotification: Boolean,
)

object PromptPresentationPolicy {
    fun select(
        pending: List<PromptCandidate>,
        environment: PresentationEnvironment,
    ): PromptSelection {
        if (pending.isEmpty()) {
            return PromptSelection(PromptPresentation.None, null, false)
        }
        val oldest = pending.minByOrNull { it.createdAt } ?: pending.first()
        if (environment.appUiVisible) {
            // 應用內 Compose 卡片負責顯示；通知保持安靜，避免重複提示。
            return PromptSelection(PromptPresentation.InApp, oldest.id, softNotification = true)
        }
        if (environment.autoOverlay &&
            environment.overlayAllowed &&
            environment.screenInteractive &&
            !environment.keyguardLocked &&
            environment.foregroundUid != null
        ) {
            val matching = pending
                .filter { it.appUid != null && it.appUid == environment.foregroundUid }
                .minByOrNull { it.createdAt }
            if (matching != null) {
                return PromptSelection(PromptPresentation.Overlay, matching.id, softNotification = true)
            }
        }
        return PromptSelection(PromptPresentation.Notification, oldest.id, softNotification = false)
    }
}

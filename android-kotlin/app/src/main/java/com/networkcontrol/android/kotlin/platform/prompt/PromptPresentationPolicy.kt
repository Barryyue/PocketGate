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

/**
 * 詢問面板「拒絕」按鈕的規則時效。預設只在應用本次使用期間拒絕（誤觸時重開應用即恢復）；
 * 勾選「不再詢問」或在設定中開啟「拒絕預設為永久」時才建立永久規則。
 */
object PromptDenyPolicy {
    fun lifetime(
        dontAskAgain: Boolean,
        denyPermanentByDefault: Boolean,
    ): com.networkcontrol.android.kotlin.core.model.RuleLifetime =
        if (dontAskAgain || denyPermanentByDefault) {
            com.networkcontrol.android.kotlin.core.model.RuleLifetime.Permanent
        } else {
            com.networkcontrol.android.kotlin.core.model.RuleLifetime.UntilAppExit
        }
}

/** 詢問面板的主訊息（仿紅魔：「X 已禁止 Y 使用 WLAN 網路。是否允許此應用使用網路？」）。 */
object PromptSheetText {
    fun message(
        strings: com.networkcontrol.android.kotlin.ui.UiStrings,
        ownName: String,
        appLabel: String,
        network: com.networkcontrol.android.kotlin.core.model.NetworkKind?,
    ): String {
        val networkLabel = when (network) {
            com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi ->
                strings[com.networkcontrol.android.kotlin.ui.S.PromptNetWlan]
            com.networkcontrol.android.kotlin.core.model.NetworkKind.Cellular ->
                strings[com.networkcontrol.android.kotlin.ui.S.PromptNetMobile]
            else -> strings[com.networkcontrol.android.kotlin.ui.S.PromptNetAny]
        }
        return strings.format(com.networkcontrol.android.kotlin.ui.S.PromptSheetMessage, ownName, appLabel, networkLabel)
    }
}

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

package com.networkcontrol.android.kotlin.platform.quick

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.networkcontrol.android.kotlin.AppContainer
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import kotlinx.coroutines.launch

/**
 * 桌面掛件、控制中心磁貼、長按圖示捷徑共用的快捷操作。
 * 所有入口都走這裡，確保「削弱防護的操作在開啟應用鎖時要先驗證」只實作一次。
 */
enum class QuickAction(val id: String) {
    ToggleProtection("toggle_protection"),
    CycleMode("cycle_mode"),
    ToggleBlockAll("toggle_block_all"),
    ToggleDnd("toggle_dnd"),
    ToggleFocus("toggle_focus"),
    OpenApp("open_app"),
    ;

    companion object {
        fun fromId(id: String?): QuickAction? = entries.firstOrNull { it.id == id }
    }
}

/** 快捷入口需要的狀態快照（純資料，方便測試）。 */
data class QuickState(
    val protectionOn: Boolean,
    val status: ProtectionStatus,
    /** 目前生效的模式（含 BlockAll）。 */
    val mode: FilterMode,
    /** 全部阻止前的模式；切換模式時以它為基準。 */
    val baseMode: FilterMode,
    val doNotDisturbUntil: Long?,
    val focusEndsAt: Long?,
    val pendingPrompts: Int,
    val appLockEnabled: Boolean,
) {
    val blockAll: Boolean get() = mode == FilterMode.BlockAll
    fun dndActive(now: Long): Boolean = doNotDisturbUntil != null && doNotDisturbUntil > now
    fun focusActive(now: Long): Boolean = focusEndsAt != null && focusEndsAt > now
}

/** 不依賴 Android 的決策。 */
object QuickLogic {
    const val DND_MILLIS = 60 * 60_000L
    const val FOCUS_MINUTES = 30

    /** 模式循環：詢問 → 白名單 → 黑名單 → 詢問。 */
    fun nextMode(current: FilterMode): FilterMode = when (current) {
        FilterMode.Ask -> FilterMode.Whitelist
        FilterMode.Whitelist -> FilterMode.Blacklist
        else -> FilterMode.Ask
    }

    /** 放行程度：白名單最嚴、黑名單最寬。 */
    private fun permissiveness(mode: FilterMode): Int = when (mode) {
        FilterMode.BlockAll -> 0
        FilterMode.Whitelist -> 1
        FilterMode.Ask -> 2
        FilterMode.Blacklist -> 3
        FilterMode.Disabled -> 4
    }

    /** 這次操作是否會削弱防護（關閉保護、放寬模式、解除全部阻止、提前結束專注）。 */
    fun isWeakening(action: QuickAction, state: QuickState, now: Long): Boolean = when (action) {
        QuickAction.ToggleProtection -> state.protectionOn
        QuickAction.CycleMode ->
            state.blockAll || permissiveness(nextMode(state.baseMode)) > permissiveness(state.baseMode)
        QuickAction.ToggleBlockAll -> state.blockAll
        QuickAction.ToggleFocus -> state.focusActive(now)
        QuickAction.ToggleDnd, QuickAction.OpenApp -> false
    }

    fun requiresAuth(action: QuickAction, state: QuickState, now: Long): Boolean =
        state.appLockEnabled && isWeakening(action, state, now)

    /** 這次操作會開啟保護（需要 VPN 同意時要經過 Activity）。 */
    fun turnsProtectionOn(action: QuickAction, state: QuickState, now: Long): Boolean = when (action) {
        QuickAction.ToggleProtection -> !state.protectionOn
        QuickAction.ToggleFocus -> !state.focusActive(now) && !state.protectionOn
        else -> false
    }
}

sealed interface QuickResult {
    data object Done : QuickResult

    /** 需要先取得 VPN 同意；同意後呼叫 `setProtection(true)`。 */
    data class NeedsConsent(val intent: Intent) : QuickResult
}

object QuickActions {
    private const val SCHEME = "nckt-quick"

    fun state(container: AppContainer): QuickState {
        val runtime = container.runtime.state.value
        return QuickState(
            protectionOn = container.protection.isEnabled,
            status = container.vpnEngine.status(),
            mode = runtime.mode,
            baseMode = if (runtime.mode == FilterMode.BlockAll) runtime.modeBeforeBlockAll ?: FilterMode.Ask else runtime.mode,
            doNotDisturbUntil = runtime.doNotDisturbUntil,
            focusEndsAt = container.focus.session.value?.endsAt,
            pendingPrompts = container.prompts.pending.value.size,
            appLockEnabled = container.preferences.appLockEnabled,
        )
    }

    /** 這次操作是否必須經過 [QuickActionActivity]（驗證身分或 VPN 同意）。 */
    fun needsActivity(container: AppContainer, action: QuickAction): Boolean {
        if (action == QuickAction.OpenApp) return true
        val now = System.currentTimeMillis()
        val state = state(container)
        if (QuickLogic.requiresAuth(action, state, now) && deviceSecure(container.appContext)) return true
        return QuickLogic.turnsProtectionOn(action, state, now) && container.vpnEngine.createConsentIntent() != null
    }

    /** 執行操作；身分驗證由呼叫端負責。 */
    fun execute(container: AppContainer, action: QuickAction): QuickResult {
        val now = System.currentTimeMillis()
        val state = state(container)
        val backend = container.backend
        com.networkcontrol.android.kotlin.platform.AppLog.i("quick", "execute ${action.id}")
        when (action) {
            QuickAction.ToggleProtection -> {
                if (state.protectionOn) {
                    backend.setProtection(false)
                } else {
                    return enableProtection(container)
                }
            }
            QuickAction.CycleMode -> {
                val next = QuickLogic.nextMode(state.baseMode)
                container.scope.launch {
                    container.runtime.restoreFromBlockAll()
                    container.profiles.setActiveMode(next)
                }
            }
            QuickAction.ToggleBlockAll -> backend.setBlockAll(!state.blockAll)
            QuickAction.ToggleDnd -> backend.setDoNotDisturb(if (state.dndActive(now)) null else now + QuickLogic.DND_MILLIS)
            QuickAction.ToggleFocus -> {
                if (state.focusActive(now)) {
                    backend.stopFocus()
                } else {
                    backend.startFocus(QuickLogic.FOCUS_MINUTES, container.preferences.focusLastAllowed)
                    if (!state.protectionOn) return enableProtection(container)
                }
            }
            QuickAction.OpenApp -> Unit
        }
        container.quickSurfaces.refreshSoon()
        return QuickResult.Done
    }

    private fun enableProtection(container: AppContainer): QuickResult {
        val consent = container.vpnEngine.createConsentIntent()
        if (consent != null) return QuickResult.NeedsConsent(consent)
        container.backend.setProtection(true)
        container.quickSurfaces.refreshSoon()
        return QuickResult.Done
    }

    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    /** 指向 [QuickActionActivity] 的明確 Intent；data 讓不同操作的 PendingIntent 互不覆蓋。 */
    fun activityIntent(context: Context, action: QuickAction): Intent =
        Intent(context, QuickActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("$SCHEME://${action.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    fun fromIntent(intent: Intent?): QuickAction? =
        intent?.data?.takeIf { it.scheme == SCHEME }?.host?.let(QuickAction::fromId)
}

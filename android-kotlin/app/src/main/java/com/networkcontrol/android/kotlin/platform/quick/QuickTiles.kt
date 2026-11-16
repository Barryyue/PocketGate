package com.networkcontrol.android.kotlin.platform.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * 控制中心磁貼的共用邏輯：點擊時走 [QuickActions]；需要驗證身分或 VPN 同意時開啟 [QuickActionActivity]。
 * 鎖屏狀態下先要求解鎖（否則撿到手機的人可以從下拉選單關掉防火牆）。
 */
abstract class QuickTileService(private val action: QuickAction) : TileService() {
    protected abstract fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long)

    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
        // 操作是非同步生效的：顯示期間跟著狀態變化重繪，而不是只在點擊當下畫一次。
        val container = App.container(this)
        listening?.cancel()
        listening = container.scope.launch(Dispatchers.Main) {
            container.quickSurfaces.changes.collect { refresh() }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { perform() } else perform()
    }

    private fun perform() {
        val container = App.container(this)
        if (QuickActions.needsActivity(container, action)) {
            val intent = QuickActions.activityIntent(this, action)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        QuickActions.execute(container, action)
        refresh()
    }

    protected fun refresh() {
        val tile = qsTile ?: return
        val strings = UiStrings(resources.configuration.locales[0]?.toLanguageTag().orEmpty())
        render(tile, QuickActions.state(App.container(this)), strings, System.currentTimeMillis())
        tile.updateTile()
    }

    protected fun Tile.setSubtitleCompat(text: String?) {
        if (Build.VERSION.SDK_INT >= 29) subtitle = text
    }

    companion object {
        private val tiles = listOf(
            com.networkcontrol.android.kotlin.platform.ProtectionTileService::class.java,
            ModeTileService::class.java,
            BlockAllTileService::class.java,
            DndTileService::class.java,
            FocusTileService::class.java,
        )

        /** 狀態改變時請系統重新整理已加入控制中心的磁貼。 */
        fun requestRefreshAll(context: Context) {
            tiles.forEach { cls ->
                runCatching { requestListeningState(context, ComponentName(context, cls)) }
            }
        }
    }
}

fun modeLabel(strings: UiStrings, mode: FilterMode): String = when (mode) {
    FilterMode.Ask -> strings[S.ModeAsk]
    FilterMode.Whitelist -> strings[S.ModeWhitelist]
    FilterMode.Blacklist -> strings[S.ModeBlacklist]
    FilterMode.BlockAll -> strings[S.ModeBlockAll]
    FilterMode.Disabled -> strings[S.ModeDisabled]
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

class ModeTileService : QuickTileService(QuickAction.CycleMode) {
    override fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long) {
        tile.state = if (state.protectionOn && !state.blockAll) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = modeLabel(strings, state.baseMode)
        tile.setSubtitleCompat(strings.format(S.QuickNextMode, modeLabel(strings, QuickLogic.nextMode(state.baseMode))))
    }
}

class BlockAllTileService : QuickTileService(QuickAction.ToggleBlockAll) {
    override fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long) {
        tile.state = if (state.blockAll) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = strings[S.QuickBlockAll]
        tile.setSubtitleCompat(if (state.blockAll) strings[S.QuickOn] else strings[S.QuickOff])
    }
}

class DndTileService : QuickTileService(QuickAction.ToggleDnd) {
    override fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long) {
        val active = state.dndActive(now)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = strings[S.QuickDnd]
        tile.setSubtitleCompat(
            if (active) strings.format(S.QuickUntil, timeOf(state.doNotDisturbUntil!!)) else strings[S.QuickDndOneHour],
        )
    }
}

class FocusTileService : QuickTileService(QuickAction.ToggleFocus) {
    override fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long) {
        val active = state.focusActive(now)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = strings[S.QuickFocus]
        tile.setSubtitleCompat(
            if (active) strings.format(S.QuickUntil, timeOf(state.focusEndsAt!!))
            else strings.format(S.QuickFocusMinutes, QuickLogic.FOCUS_MINUTES),
        )
    }
}

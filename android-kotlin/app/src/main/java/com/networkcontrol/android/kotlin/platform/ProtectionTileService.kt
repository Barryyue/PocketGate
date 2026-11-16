package com.networkcontrol.android.kotlin.platform

import android.service.quicksettings.Tile
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus
import com.networkcontrol.android.kotlin.platform.quick.QuickAction
import com.networkcontrol.android.kotlin.platform.quick.QuickState
import com.networkcontrol.android.kotlin.platform.quick.QuickTileService
import com.networkcontrol.android.kotlin.platform.quick.modeLabel
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings

/**
 * 保護開關磁貼。類別名稱與套件保持不變，使用者已加入控制中心的磁貼升級後仍然有效。
 * 關閉保護在開啟應用鎖時需要先驗證（見 QuickActions）。
 */
class ProtectionTileService : QuickTileService(QuickAction.ToggleProtection) {
    override fun render(tile: Tile, state: QuickState, strings: UiStrings, now: Long) {
        val on = state.protectionOn && state.status != ProtectionStatus.Off
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = strings[S.QuickProtection]
        tile.setSubtitleCompat(if (on) modeLabel(strings, state.mode) else strings[S.WidgetProtectionOff])
    }
}


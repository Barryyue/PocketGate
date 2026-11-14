package com.networkcontrol.android.kotlin.platform

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.core.model.ProtectionStatus

class ProtectionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val container = (applicationContext as App).container
        val enable = !container.protection.isEnabled
        container.vpnEngine.setProtection(enable)
        if (enable) {
            container.vpnEngine.createConsentIntent()?.let { consent ->
                launchActivity(consent)
                return
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val container = (applicationContext as App).container
        val status = container.vpnEngine.status()
        qsTile?.apply {
            state = when (status) {
                ProtectionStatus.On -> Tile.STATE_ACTIVE
                ProtectionStatus.Starting -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = getString(com.networkcontrol.android.kotlin.R.string.tile_protection)
            updateTile()
        }
    }

    private fun launchActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

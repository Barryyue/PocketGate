package com.networkcontrol.android.kotlin.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.engine.vpn.NcVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        val container = app.container
        if (!container.preferences.protectionEnabled) return
        if (!container.vpnTunnel.isPrepared) return
        val service = Intent(context, NcVpnService::class.java).setAction(NcVpnService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, service) }
    }
}

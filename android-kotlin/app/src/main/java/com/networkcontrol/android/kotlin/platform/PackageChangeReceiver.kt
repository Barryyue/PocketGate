package com.networkcontrol.android.kotlin.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkcontrol.android.kotlin.App

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        app.container.appCatalog.invalidate()
    }
}

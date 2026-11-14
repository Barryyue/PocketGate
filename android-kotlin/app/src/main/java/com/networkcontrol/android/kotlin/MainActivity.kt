package com.networkcontrol.android.kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.networkcontrol.android.kotlin.ui.AppRoot
import com.networkcontrol.android.kotlin.ui.theme.NetworkControlTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            NetworkControlTheme {
                AppRoot(App.container(this))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        App.container(this).vpnEngine.requestReplan()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val container = App.container(this)
        if (container.preferences.notificationPermissionAsked) return
        container.preferences.notificationPermissionAsked = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

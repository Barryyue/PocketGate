package com.networkcontrol.android.kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.ui.AppRoot
import com.networkcontrol.android.kotlin.ui.theme.NetworkControlTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            val container = App.container(this)
            val themeColor by container.theme.color.collectAsStateWithLifecycle()
            NetworkControlTheme(seedColor = themeColor) {
                AppRoot(container)
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
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        } else {
            container.preferences.notificationPermissionAsked = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            App.container(this).preferences.notificationPermissionAsked = true
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                com.networkcontrol.android.kotlin.platform.AppLog.w("notifications", "POST_NOTIFICATIONS denied")
            }
        }
    }
}

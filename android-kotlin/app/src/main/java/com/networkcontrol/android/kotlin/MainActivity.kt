package com.networkcontrol.android.kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.networkcontrol.android.kotlin.ui.AppRoot
import com.networkcontrol.android.kotlin.ui.security.AppLockGate
import com.networkcontrol.android.kotlin.ui.theme.LocalAppVisualTokens
import com.networkcontrol.android.kotlin.ui.theme.NetworkControlTheme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 追蹤視窗重獲焦點的事件（包含從系統透明彈窗、分屏或小窗模式切回，此時不一定觸發 ON_RESUME）。
 * 權限體檢與引導頁訂閱此計數即可即時刷新授權狀態。
 */
object WindowFocusBus {
    private val _ticks = MutableStateFlow(0)
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    fun notifyFocusGained() {
        _ticks.value = _ticks.value + 1
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 若已完成首次引導再補請求通知權限，避免首次啟動時與 Onboarding 彈窗重疊。
        if (App.container(this).preferences.permissionGuideShown) {
            requestNotificationPermissionIfNeeded()
        }
        setContent {
            val container = App.container(this)
            val themeColor by container.theme.color.collectAsStateWithLifecycle()
            val themeStyle by container.theme.themeStyle.collectAsStateWithLifecycle()
            NetworkControlTheme(seedColor = themeColor, themeStyle = themeStyle) {
                val isDarkCanvas = LocalAppVisualTokens.current.isDarkCanvas
                SideEffect {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDarkCanvas
                    controller.isAppearanceLightNavigationBars = !isDarkCanvas
                }
                AppLockGate(container) {
                    AppRoot(container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowFocusBus.notifyFocusGained()
        App.container(this).vpnEngine.requestReplan()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowFocusBus.notifyFocusGained()
        }
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

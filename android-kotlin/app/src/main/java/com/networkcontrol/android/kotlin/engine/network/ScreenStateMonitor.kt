package com.networkcontrol.android.kotlin.engine.network

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 追蹤螢幕關閉 / 鎖屏狀態，供「鎖屏時禁止聯網」使用。 */
class ScreenStateMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val _locked = MutableStateFlow(computeLocked())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val _interactive = MutableStateFlow(power?.isInteractive ?: true)

    /** 螢幕是否亮著（不管是否鎖屏）；用於關屏時暫停只為顯示服務的取樣。 */
    val interactive: StateFlow<Boolean> = _interactive.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _locked.value = computeLocked()
            _interactive.value = power?.isInteractive ?: true
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { appContext.registerReceiver(receiver, filter) }
        _locked.value = computeLocked()
        _interactive.value = power?.isInteractive ?: true
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun computeLocked(): Boolean {
        val interactive = power?.isInteractive ?: true
        val keyguardLocked = keyguard?.isKeyguardLocked ?: false
        return !interactive || keyguardLocked
    }
}

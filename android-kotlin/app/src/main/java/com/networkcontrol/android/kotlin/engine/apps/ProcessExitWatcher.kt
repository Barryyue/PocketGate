package com.networkcontrol.android.kotlin.engine.apps

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 讀取其他應用的進程結束記錄（Android 11+ `getHistoricalProcessExitReasons`）。
 *
 * 查詢別的套件需要 `android.permission.DUMP`；它是 development 權限，只能由使用者用
 * `adb shell pm grant <本應用> android.permission.DUMP` 授予。紅魔等系統應用直接監聽進程死亡，
 * 第三方應用沒有這個能力，這是最接近的公開做法（輪詢，延遲約一個輪詢週期）。
 */
class ProcessExitWatcher(private val context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /**
     * 目標套件「主進程」最近一次結束的時間（牆鐘毫秒）。只看主進程：
     * Chrome 等應用的渲染進程隨時會重建，不代表應用被關閉。無權限或沒有記錄時為 null。
     */
    fun lastMainProcessExit(packageName: String): Long? {
        if (!available || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = activityManager ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(packageName, 0, MAX_RECORDS)
                .filter { it.processName == packageName }
                .maxOfOrNull { it.timestamp }
        }.getOrNull()
    }

    private companion object {
        const val MAX_RECORDS = 16
    }
}

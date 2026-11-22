package com.networkcontrol.android.kotlin.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** 應用桌面圖示風格。 */
enum class AppLauncherIcon(val key: String, val aliasClassName: String) {
    Dark("dark", "com.networkcontrol.android.kotlin.LauncherDark"),
    Light("light", "com.networkcontrol.android.kotlin.LauncherLight"),
    ;

    companion object {
        fun fromKey(key: String?): AppLauncherIcon =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Dark
    }
}

/**
 * 透過互斥切換 `<activity-alias>` 動態更換桌面圖示；
 * `.MainActivity` 本身始終保持啟用，不影響通知、掛件、磁貼與內部跳轉。
 */
object AppIconSwitcher {
    fun apply(context: Context, target: AppLauncherIcon) {
        val pm = context.packageManager
        val pkg = context.packageName
        // 先啟用目標 alias，再停用其餘 alias，避免短暫失去啟動入口。
        pm.setComponentEnabledSetting(
            ComponentName(pkg, target.aliasClassName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppLauncherIcon.entries
            .filter { it != target }
            .forEach { other ->
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, other.aliasClassName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    fun current(context: Context, fallback: AppLauncherIcon = AppLauncherIcon.Dark): AppLauncherIcon {
        val pm = context.packageManager
        val pkg = context.packageName
        for (icon in AppLauncherIcon.entries) {
            val state = runCatching {
                pm.getComponentEnabledSetting(ComponentName(pkg, icon.aliasClassName))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return icon
            }
        }
        return fallback
    }
}

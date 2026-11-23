package com.networkcontrol.android.kotlin

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.networkcontrol.android.kotlin.platform.NotificationChannels

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 獨立無障礙進程（:automation）絕不初始化主進程的 VPN / AppContainer / config.db，
        // 避免記憶體翻倍與多進程 SQLite 鎖衝突。
        if (isAutomationProcess()) return
        container = AppContainer(this)
        com.networkcontrol.android.kotlin.platform.AppLog.init(this)
        NotificationChannels.ensure(this)
        applyThemePreference()
        applyLanguagePreference()
    }

    private fun isAutomationProcess(): Boolean {
        val processName = runCatching { getProcessName() }.getOrNull().orEmpty()
        return processName.endsWith(":automation")
    }

    private fun applyThemePreference() {
        val mode = when (container.preferences.themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyLanguagePreference() {
        val tag = container.preferences.languageTag ?: return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    companion object {
        fun container(context: android.content.Context): AppContainer =
            (context.applicationContext as App).container
    }
}

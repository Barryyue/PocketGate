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
        container = AppContainer(this)
        com.networkcontrol.android.kotlin.platform.AppLog.init(this)
        NotificationChannels.ensure(this)
        applyThemePreference()
        applyLanguagePreference()
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

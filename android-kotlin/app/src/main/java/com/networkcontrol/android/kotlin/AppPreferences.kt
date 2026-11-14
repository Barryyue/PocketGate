package com.networkcontrol.android.kotlin

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("networkcontrol-kotlin", Context.MODE_PRIVATE)

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var protectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTION, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    var themeColor: Int
        get() = prefs.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR)
        set(value) = prefs.edit().putInt(KEY_THEME_COLOR, value).apply()

    /** 詢問顯示方式：auto（可用時懸浮面板）/ notification（只用通知）。 */
    var promptPresentationMode: String
        get() = prefs.getString(KEY_PROMPT_PRESENTATION, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_PROMPT_PRESENTATION, value).apply()

    /** 懸浮面板位置：top / bottom。 */
    var promptOverlayPosition: String
        get() = prefs.getString(KEY_PROMPT_OVERLAY_POSITION, "top") ?: "top"
        set(value) = prefs.edit().putString(KEY_PROMPT_OVERLAY_POSITION, value).apply()

    var promptShowDetails: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_SHOW_DETAILS, true)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_SHOW_DETAILS, value).apply()

    var promptShowExplanation: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_SHOW_EXPLANATION, true)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_SHOW_EXPLANATION, value).apply()

    var promptHideDetailsOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_HIDE_DETAILS_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_HIDE_DETAILS_LOCK, value).apply()

    var blockOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ON_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ON_LOCK, value).apply()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROTECTION = "protection"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_NOTIFICATION_ASKED = "notification_asked"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_PROMPT_PRESENTATION = "prompt_presentation"
        const val KEY_PROMPT_OVERLAY_POSITION = "prompt_overlay_position"
        const val KEY_PROMPT_SHOW_DETAILS = "prompt_show_details"
        const val KEY_PROMPT_SHOW_EXPLANATION = "prompt_show_explanation"
        const val KEY_PROMPT_HIDE_DETAILS_LOCK = "prompt_hide_details_lock"
        const val KEY_BLOCK_ON_LOCK = "block_on_lock"
        const val DEFAULT_THEME_COLOR = 0xFF2E7D32.toInt()
    }
}

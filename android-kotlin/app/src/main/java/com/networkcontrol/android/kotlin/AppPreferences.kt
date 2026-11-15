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

    /** 自訂垂直位置：0=頂部，1=底部。 */
    var promptOverlayCustomRatio: Float
        get() = prefs.getFloat(KEY_PROMPT_OVERLAY_RATIO, 0.15f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_PROMPT_OVERLAY_RATIO, value.coerceIn(0f, 1f)).apply()

    /** 服務啟動後在螢幕頂部顯示速度島。 */
    var speedIslandEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ISLAND, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_ISLAND, value).apply()

    /** vivo / iQOO 上把靈動島放在系統島下方，避免重疊。 */
    var vivoIslandCompatible: Boolean
        get() = prefs.getBoolean(KEY_VIVO_ISLAND_COMPAT, isVivoDevice())
        set(value) = prefs.edit().putBoolean(KEY_VIVO_ISLAND_COMPAT, value).apply()

    /** 靈動島水平偏移（dp），負數向左、正數向右。 */
    var speedIslandOffsetXDp: Int
        get() = prefs.getInt(KEY_SPEED_ISLAND_OFFSET_X, 0).coerceIn(-60, 60)
        set(value) = prefs.edit().putInt(KEY_SPEED_ISLAND_OFFSET_X, value.coerceIn(-60, 60)).apply()

    /** 靈動島垂直偏移（dp），負數向上、正數向下。 */
    var speedIslandOffsetYDp: Int
        get() = prefs.getInt(
            KEY_SPEED_ISLAND_OFFSET_Y,
            prefs.getInt(KEY_SPEED_ISLAND_OFFSET, 0),
        ).coerceIn(-60, 60)
        set(value) = prefs.edit().putInt(KEY_SPEED_ISLAND_OFFSET_Y, value.coerceIn(-60, 60)).apply()

    /** 靈動島背景透明度（0–1.0）。 */
    var speedIslandOpacity: Float
        get() = prefs.getFloat(KEY_SPEED_ISLAND_OPACITY, 0.9f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_SPEED_ISLAND_OPACITY, value.coerceIn(0f, 1f)).apply()

    /** 靈動島文字顏色：theme（跟隨主題）或 custom。 */
    var speedIslandTextColorMode: String
        get() = prefs.getString(KEY_SPEED_ISLAND_TEXT_MODE, "theme") ?: "theme"
        set(value) = prefs.edit().putString(KEY_SPEED_ISLAND_TEXT_MODE, value).apply()

    var speedIslandTextColor: Int
        get() = prefs.getInt(KEY_SPEED_ISLAND_TEXT_COLOR, 0xFFFFFFFF.toInt())
        set(value) = prefs.edit().putInt(KEY_SPEED_ISLAND_TEXT_COLOR, value).apply()

    var speedIslandShowDot: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ISLAND_SHOW_DOT, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_ISLAND_SHOW_DOT, value).apply()

    var speedIslandSwapOrder: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ISLAND_SWAP_ORDER, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_ISLAND_SWAP_ORDER, value).apply()

    var speedIslandDotColor: Int
        get() = prefs.getInt(KEY_SPEED_ISLAND_DOT_COLOR, 0xFF4CAF50.toInt())
        set(value) = prefs.edit().putInt(KEY_SPEED_ISLAND_DOT_COLOR, value).apply()

    /** 詢問懸浮面板是否顯示「限時允許」選項。 */
    var promptShowTimedOption: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_SHOW_TIMED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_SHOW_TIMED, value).apply()

    var speedIslandMode: com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.model.SpeedIslandMode>(
                prefs.getString(KEY_SPEED_ISLAND_MODE, "Full").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.model.SpeedIslandMode.Full)
        set(value) = prefs.edit().putString(KEY_SPEED_ISLAND_MODE, value.name).apply()

    /** 靈動島顯示內容。 */
    var speedIslandContent: com.networkcontrol.android.kotlin.core.model.SpeedIslandContent
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.model.SpeedIslandContent>(
                prefs.getString(KEY_SPEED_ISLAND_CONTENT, "Speed").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.model.SpeedIslandContent.Speed)
        set(value) = prefs.edit().putString(KEY_SPEED_ISLAND_CONTENT, value.name).apply()

    /** 字體縮放百分比（80–160）。 */
    var speedIslandFontScalePercent: Int
        get() = prefs.getInt(KEY_SPEED_ISLAND_FONT_SCALE, 100).coerceIn(80, 160)
        set(value) = prefs.edit()
            .putInt(KEY_SPEED_ISLAND_FONT_SCALE, value.coerceIn(80, 160))
            .apply()

    /** 字體粗細。 */
    var speedIslandFontWeight: com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight>(
                prefs.getString(KEY_SPEED_ISLAND_FONT_WEIGHT, "Normal").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight.Normal)
        set(value) = prefs.edit().putString(KEY_SPEED_ISLAND_FONT_WEIGHT, value.name).apply()

    /** 嘗試把靈動島放進狀態欄區域；部分 ROM 會用狀態欄蓋住第三方懸浮窗。 */
    var speedIslandStatusBarMode: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ISLAND_STATUSBAR, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_ISLAND_STATUSBAR, value).apply()

    fun isVivoDevice(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val brand = android.os.Build.BRAND.orEmpty()
        return manufacturer.contains("vivo", ignoreCase = true) ||
            manufacturer.contains("iqoo", ignoreCase = true) ||
            brand.contains("vivo", ignoreCase = true) ||
            brand.contains("iqoo", ignoreCase = true)
    }

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

    var focusSessionJson: String?
        get() = prefs.getString(KEY_FOCUS_SESSION, null)
        set(value) = prefs.edit().putString(KEY_FOCUS_SESSION, value).apply()

    var focusSchedulesJson: String?
        get() = prefs.getString(KEY_FOCUS_SCHEDULES, null)
        set(value) = prefs.edit().putString(KEY_FOCUS_SCHEDULES, value).apply()

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    var forwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARDING_ENABLED, value).apply()

    var socks5Host: String
        get() = prefs.getString(KEY_SOCKS5_HOST, "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString(KEY_SOCKS5_HOST, value).apply()

    var socks5Port: Int
        get() = prefs.getInt(KEY_SOCKS5_PORT, 7890)
        set(value) = prefs.edit().putInt(KEY_SOCKS5_PORT, value).apply()

    var socks5Udp: Boolean
        get() = prefs.getBoolean(KEY_SOCKS5_UDP, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCKS5_UDP, value).apply()

    /** 代理應用包名：轉發模式下排除此應用，避免代理自身的流量回環進 VPN。 */
    /** 轉發模式下直接排除在 VPN 之外的應用：不經代理，也不受防火牆管控。 */
    var forwardingDirectPackages: Set<String>
        get() = prefs.getStringSet(KEY_FORWARDING_DIRECT_PACKAGES, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_FORWARDING_DIRECT_PACKAGES, value.toSet()).apply()

    var socks5BypassPackage: String?
        get() = prefs.getString(KEY_SOCKS5_BYPASS_PACKAGE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SOCKS5_BYPASS_PACKAGE, value).apply()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROTECTION = "protection"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_NOTIFICATION_ASKED = "notification_asked"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_PROMPT_PRESENTATION = "prompt_presentation"
        const val KEY_PROMPT_OVERLAY_POSITION = "prompt_overlay_position"
        const val KEY_PROMPT_OVERLAY_RATIO = "prompt_overlay_ratio"
        const val KEY_SPEED_ISLAND = "speed_island"
        const val KEY_VIVO_ISLAND_COMPAT = "vivo_island_compat"
        const val KEY_SPEED_ISLAND_OFFSET = "speed_island_offset"
        const val KEY_SPEED_ISLAND_OFFSET_X = "speed_island_offset_x"
        const val KEY_SPEED_ISLAND_OFFSET_Y = "speed_island_offset_y"
        const val KEY_SPEED_ISLAND_OPACITY = "speed_island_opacity"
        const val KEY_SPEED_ISLAND_TEXT_MODE = "speed_island_text_mode"
        const val KEY_SPEED_ISLAND_TEXT_COLOR = "speed_island_text_color"
        const val KEY_SPEED_ISLAND_SHOW_DOT = "speed_island_show_dot"
        const val KEY_SPEED_ISLAND_SWAP_ORDER = "speed_island_swap_order"
        const val KEY_SPEED_ISLAND_DOT_COLOR = "speed_island_dot_color"
        const val KEY_PROMPT_SHOW_TIMED = "prompt_show_timed"
        const val KEY_SPEED_ISLAND_MODE = "speed_island_mode"
        const val KEY_SPEED_ISLAND_CONTENT = "speed_island_content"
        const val KEY_SPEED_ISLAND_FONT_SCALE = "speed_island_font_scale"
        const val KEY_SPEED_ISLAND_FONT_WEIGHT = "speed_island_font_weight"
        const val KEY_SPEED_ISLAND_STATUSBAR = "speed_island_statusbar"
        const val KEY_PROMPT_SHOW_DETAILS = "prompt_show_details"
        const val KEY_PROMPT_SHOW_EXPLANATION = "prompt_show_explanation"
        const val KEY_PROMPT_HIDE_DETAILS_LOCK = "prompt_hide_details_lock"
        const val KEY_BLOCK_ON_LOCK = "block_on_lock"
        const val KEY_FOCUS_SESSION = "focus_session"
        const val KEY_FOCUS_SCHEDULES = "focus_schedules"
        const val KEY_APP_LOCK = "app_lock"
        const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
        const val KEY_SOCKS5_HOST = "socks5_host"
        const val KEY_SOCKS5_PORT = "socks5_port"
        const val KEY_SOCKS5_UDP = "socks5_udp"
        const val KEY_SOCKS5_BYPASS_PACKAGE = "socks5_bypass_package"
        const val KEY_FORWARDING_DIRECT_PACKAGES = "forwarding_direct_packages"
        const val DEFAULT_THEME_COLOR = 0xFF2E7D32.toInt()
    }
}

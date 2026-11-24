package com.networkcontrol.android.kotlin

import android.content.Context
import android.content.SharedPreferences

/** 逐連線（轉發）模式的出口類型。 */
object ForwardingUpstream {
    /** 內建直連出口：不需要其他應用。 */
    const val DIRECT = "direct"

    /** 外部 SOCKS5 代理（例如關閉 VPN 的 FlClash）。 */
    const val SOCKS5 = "socks5"
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("networkcontrol-kotlin", Context.MODE_PRIVATE)

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** 更新通道：Stable（正式版，預設）/ Beta（測試版 / 日更預發佈）。 */
    var updateChannel: com.networkcontrol.android.kotlin.core.update.UpdateChannel
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.update.UpdateChannel>(
                prefs.getString(KEY_UPDATE_CHANNEL, "Stable").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.update.UpdateChannel.Stable)
        set(value) = prefs.edit().putString(KEY_UPDATE_CHANNEL, value.name).apply()

    var protectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTION, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    /** 常駐通知標題；空字串表示使用系統語言的預設「保護已開啟」。 */
    var protectionNotificationTitle: String
        get() = prefs.getString(KEY_PROTECTION_NOTIFICATION_TITLE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROTECTION_NOTIFICATION_TITLE, value.trim()).apply()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    var themeColor: Int
        get() = prefs.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR)
        set(value) = prefs.edit().putInt(KEY_THEME_COLOR, value).apply()

    /** 底部工具欄樣式。 */
    var toolbarStyle: com.networkcontrol.android.kotlin.core.model.ToolbarStyle
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.model.ToolbarStyle>(
                prefs.getString(KEY_TOOLBAR_STYLE, "ClassicSolid").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.model.ToolbarStyle.ClassicSolid)
        set(value) = prefs.edit().putString(KEY_TOOLBAR_STYLE, value.name).apply()

    /** 應用桌面圖示風格：dark（預設黑曜）/ light（淺色晨霧）。 */
    var appIconStyle: String
        get() = prefs.getString(KEY_APP_ICON_STYLE, "dark") ?: "dark"
        set(value) = prefs.edit().putString(KEY_APP_ICON_STYLE, value).apply()

    /** 視覺主題風格。 */
    var themeStyle: com.networkcontrol.android.kotlin.core.model.AppThemeStyle
        get() = runCatching {
            enumValueOf<com.networkcontrol.android.kotlin.core.model.AppThemeStyle>(
                prefs.getString(KEY_THEME_STYLE, "Classic").orEmpty(),
            )
        }.getOrDefault(com.networkcontrol.android.kotlin.core.model.AppThemeStyle.Classic)
        set(value) = prefs.edit().putString(KEY_THEME_STYLE, value.name).apply()

    /** 詢問顯示方式：auto（可用時懸浮面板）/ notification（只用通知）。 */
    var promptPresentationMode: String
        get() = prefs.getString(KEY_PROMPT_PRESENTATION, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_PROMPT_PRESENTATION, value).apply()

    /** 懸浮面板位置：top / bottom。 */
    var promptOverlayPosition: String
        get() = prefs.getString(KEY_PROMPT_OVERLAY_POSITION, "bottom") ?: "bottom"
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

    /** 「僅一次」「禁止（到應用關閉）」在應用切到背景後保留的分鐘數；0 = 一離開前台就失效。 */
    var onceBackgroundMinutes: Int
        get() = prefs.getInt(KEY_ONCE_BACKGROUND_MINUTES, 10)
        set(value) = prefs.edit().putInt(KEY_ONCE_BACKGROUND_MINUTES, value.coerceIn(0, 24 * 60)).apply()

    /** 詢問面板的「拒絕」是否一律永久；預設 false：只拒絕到應用關閉，勾選「不再詢問」才永久。 */
    var promptDenyPermanent: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_DENY_PERMANENT, false)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_DENY_PERMANENT, value).apply()

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

    var promptHideDetailsOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_HIDE_DETAILS_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_HIDE_DETAILS_LOCK, value).apply()

    var blockOnLockScreen: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ON_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ON_LOCK, value).apply()

    /** 鎖屏斷網時仍允許聯網的應用（包名）。 */
    var lockScreenWhitelist: Set<String>
        get() = prefs.getStringSet(KEY_LOCK_SCREEN_WHITELIST, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_LOCK_SCREEN_WHITELIST, value.toSet()).apply()

    var focusSessionJson: String?
        get() = prefs.getString(KEY_FOCUS_SESSION, null)
        set(value) = prefs.edit().putString(KEY_FOCUS_SESSION, value).apply()

    var focusSchedulesJson: String?
        get() = prefs.getString(KEY_FOCUS_SCHEDULES, null)
        set(value) = prefs.edit().putString(KEY_FOCUS_SCHEDULES, value).apply()

    /** 上次專注允許的應用；桌面掛件 / 磁貼的「一鍵專注」沿用。 */
    var focusLastAllowed: Set<String>
        get() = prefs.getStringSet(KEY_FOCUS_LAST_ALLOWED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_FOCUS_LAST_ALLOWED, value).apply()

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /** 是否以系統原生通知小圖示顯示即時網速（由 SystemUI 自動跟隨任意 App 變黑/變白）。 */
    var statusBarSpeedIcon: Boolean
        get() = prefs.getBoolean(KEY_STATUS_BAR_SPEED_ICON, false)
        set(value) = prefs.edit().putBoolean(KEY_STATUS_BAR_SPEED_ICON, value).apply()

    /** 純離線模式：禁止 PocketGate 自身主動發起外網請求（更新檢查、攔截清單線上訂閱拉取）；不影響防火牆與轉發。 */
    var offlineMode: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()

    var forwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARDING_ENABLED, value).apply()

    var socks5Host: String
        get() = prefs.getString(KEY_SOCKS5_HOST, "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString(KEY_SOCKS5_HOST, value).apply()

    var socks5Port: Int
        get() = prefs.getInt(KEY_SOCKS5_PORT, 7890)
        set(value) = prefs.edit().putInt(KEY_SOCKS5_PORT, value).apply()

    /** 外部 SOCKS5 的帳號密碼（可選）；存於應用私有的偏好設定。 */
    var socks5Username: String?
        get() = prefs.getString(KEY_SOCKS5_USERNAME, null)?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_SOCKS5_USERNAME, value).apply()

    var socks5Password: String?
        get() = com.networkcontrol.android.kotlin.platform.SecretPrefsCipher
            .decrypt(prefs.getString(KEY_SOCKS5_PASSWORD, null))
            ?.takeIf { it.isNotEmpty() }
        set(value) {
            val stored = value?.takeIf { it.isNotEmpty() }
                ?.let(com.networkcontrol.android.kotlin.platform.SecretPrefsCipher::encrypt)
            prefs.edit().putString(KEY_SOCKS5_PASSWORD, stored).apply()
        }

    var socks5Udp: Boolean
        get() = prefs.getBoolean(KEY_SOCKS5_UDP, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCKS5_UDP, value).apply()

    /** 逐連線模式：獨立阻擋 QUIC (UDP 443)，強制瀏覽器與應用回退 TCP/HTTP2，不影響其他 UDP 服務。 */
    var blockQuic: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_QUIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_QUIC, value).apply()

    /** 允許局域網 (LAN)：排除私有網段不經 VPN，讓投屏、NAS、印表機不受攔截。 */
    var allowLan: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_LAN, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_LAN, value).apply()

    /** 行動網路漫遊時自動進入全部阻止模式，防止高額漫遊流量費。 */
    var blockOnRoaming: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ON_ROAMING, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ON_ROAMING, value).apply()

    /** v0.2 共享應用規則遷移是否已經完成；完成後不再每次啟動都執行。 */
    var legacyRulesMigrated: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_RULES_MIGRATED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEGACY_RULES_MIGRATED, value).apply()

    /**
     * 逐連線模式的出口：[ForwardingUpstream.DIRECT] 為內建直連（不需要其他應用），[ForwardingUpstream.SOCKS5] 為外部 SOCKS5 代理。
     * 舊版只有外部代理：升級前已經開啟轉發的使用者保留外部代理，其他人預設直連。
     */
    var forwardingUpstream: String
        get() = prefs.getString(KEY_FORWARDING_UPSTREAM, null)
            ?: (if (forwardingEnabled) UPSTREAM_SOCKS5 else UPSTREAM_DIRECT).also { forwardingUpstream = it }
        set(value) = prefs.edit().putString(KEY_FORWARDING_UPSTREAM, value).apply()

    val usesDirectUpstream: Boolean get() = forwardingUpstream != UPSTREAM_SOCKS5

    /** 逐連線模式：改寫應用自己發往其他伺服器的 DNS、阻擋 DoT 與知名 DoH，避免繞過本機 DNS。 */
    var dnsBypassProtection: Boolean
        get() = prefs.getBoolean(KEY_DNS_BYPASS_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_DNS_BYPASS_PROTECTION, value).apply()

    /** 內建出口的 DNS：`system`、內建 DoH 服務 id（見 DohProviders）或 `custom`。 */
    var exitDnsProvider: String
        get() = prefs.getString(KEY_EXIT_DNS_PROVIDER, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_EXIT_DNS_PROVIDER, value).apply()

    var exitDnsCustomUrl: String?
        get() = prefs.getString(KEY_EXIT_DNS_CUSTOM_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_EXIT_DNS_CUSTOM_URL, value).apply()

    /** 加密 DNS 連不上時退回系統 DNS（明文）；關閉後解析失敗就是失敗。 */
    var exitDnsFallback: Boolean
        get() = prefs.getBoolean(KEY_EXIT_DNS_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_EXIT_DNS_FALLBACK, value).apply()

    /** 轉發模式下直接排除在 VPN 之外的應用：不經代理，也不受防火牆管控。 */
    var forwardingDirectPackages: Set<String>
        get() = prefs.getStringSet(KEY_FORWARDING_DIRECT_PACKAGES, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_FORWARDING_DIRECT_PACKAGES, value.toSet()).apply()

    /** 代理應用包名：使用外部代理時排除此應用，避免代理自身的流量回環進 VPN。 */
    var socks5BypassPackage: String?
        get() = prefs.getString(KEY_SOCKS5_BYPASS_PACKAGE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SOCKS5_BYPASS_PACKAGE, value).apply()

    /** 首次啟動的權限引導彈窗是否已經展示或略過。 */
    var permissionGuideShown: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_GUIDE_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_GUIDE_SHOWN, value).apply()

    /** 總覽頁權限待辦橫幅是否已被使用者手動關閉。 */
    var permissionBannerDismissed: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_BANNER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_BANNER_DISMISSED, value).apply()

    /** 列表向下滾動時自動隱藏底部導覽列、向上滾動時恢復顯示（預設開啟）。 */
    var autoHideBottomBar: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_BOTTOM_BAR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE_BOTTOM_BAR, value).apply()

    /** 多使用者 / 應用分身是否繼承主應用規則（預設繼承；關閉後在逐連線模式下獨立詢問或攔截）。 */
    var cloneAppsInheritPrimary: Boolean
        get() = prefs.getBoolean(KEY_CLONE_APPS_INHERIT, true)
        set(value) = prefs.edit().putBoolean(KEY_CLONE_APPS_INHERIT, value).apply()

    var webdavUrl: String
        get() = prefs.getString(KEY_WEBDAV_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WEBDAV_URL, value.trim()).apply()

    var webdavUsername: String
        get() = prefs.getString(KEY_WEBDAV_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WEBDAV_USERNAME, value.trim()).apply()

    var webdavPassword: String
        get() = com.networkcontrol.android.kotlin.platform.SecretPrefsCipher
            .decrypt(prefs.getString(KEY_WEBDAV_PASSWORD, ""))
            .orEmpty()
        set(value) {
            val stored = if (value.isEmpty()) "" else com.networkcontrol.android.kotlin.platform.SecretPrefsCipher.encrypt(value)
            prefs.edit().putString(KEY_WEBDAV_PASSWORD, stored).apply()
        }

    var webdavPassphrase: String
        get() = com.networkcontrol.android.kotlin.platform.SecretPrefsCipher
            .decrypt(prefs.getString(KEY_WEBDAV_PASSPHRASE, ""))
            .orEmpty()
        set(value) {
            val stored = if (value.isEmpty()) "" else com.networkcontrol.android.kotlin.platform.SecretPrefsCipher.encrypt(value)
            prefs.edit().putString(KEY_WEBDAV_PASSPHRASE, stored).apply()
        }

    var webdavLastSyncAt: Long
        get() = prefs.getLong(KEY_WEBDAV_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_WEBDAV_LAST_SYNC, value).apply()

    var webdavLastEtag: String?
        get() = prefs.getString(KEY_WEBDAV_LAST_ETAG, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_WEBDAV_LAST_ETAG, value).apply()

    private companion object {
        const val UPSTREAM_DIRECT = ForwardingUpstream.DIRECT
        const val UPSTREAM_SOCKS5 = ForwardingUpstream.SOCKS5
        const val KEY_FORWARDING_UPSTREAM = "forwarding_upstream"
        const val KEY_DNS_BYPASS_PROTECTION = "dns_bypass_protection"
        const val KEY_EXIT_DNS_PROVIDER = "exit_dns_provider"
        const val KEY_EXIT_DNS_CUSTOM_URL = "exit_dns_custom_url"
        const val KEY_EXIT_DNS_FALLBACK = "exit_dns_fallback"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROTECTION = "protection"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_NOTIFICATION_ASKED = "notification_asked"
        const val KEY_PROTECTION_NOTIFICATION_TITLE = "protection_notification_title"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_APP_ICON_STYLE = "app_icon_style"
        const val KEY_TOOLBAR_STYLE = "toolbar_style"
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
        const val KEY_PROMPT_DENY_PERMANENT = "prompt_deny_permanent"
        const val KEY_ONCE_BACKGROUND_MINUTES = "once_background_minutes"
        const val KEY_FOCUS_LAST_ALLOWED = "focus_last_allowed"
        const val KEY_SPEED_ISLAND_MODE = "speed_island_mode"
        const val KEY_SPEED_ISLAND_CONTENT = "speed_island_content"
        const val KEY_SPEED_ISLAND_FONT_SCALE = "speed_island_font_scale"
        const val KEY_SPEED_ISLAND_FONT_WEIGHT = "speed_island_font_weight"
        const val KEY_SPEED_ISLAND_STATUSBAR = "speed_island_statusbar"
        const val KEY_PROMPT_SHOW_DETAILS = "prompt_show_details"
        const val KEY_PROMPT_SHOW_EXPLANATION = "prompt_show_explanation"
        const val KEY_PROMPT_HIDE_DETAILS_LOCK = "prompt_hide_details_lock"
        const val KEY_BLOCK_ON_LOCK = "block_on_lock"
        const val KEY_LOCK_SCREEN_WHITELIST = "lock_screen_whitelist"
        const val KEY_FOCUS_SESSION = "focus_session"
        const val KEY_FOCUS_SCHEDULES = "focus_schedules"
        const val KEY_APP_LOCK = "app_lock"
        const val KEY_STATUS_BAR_SPEED_ICON = "status_bar_speed_icon"
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val KEY_PERMISSION_GUIDE_SHOWN = "permission_guide_shown"
        const val KEY_PERMISSION_BANNER_DISMISSED = "permission_banner_dismissed"
        const val KEY_AUTO_HIDE_BOTTOM_BAR = "auto_hide_bottom_bar"
        const val KEY_CLONE_APPS_INHERIT = "clone_apps_inherit_primary"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USERNAME = "webdav_username"
        const val KEY_WEBDAV_PASSWORD = "webdav_password"
        const val KEY_WEBDAV_PASSPHRASE = "webdav_passphrase"
        const val KEY_WEBDAV_LAST_SYNC = "webdav_last_sync"
        const val KEY_WEBDAV_LAST_ETAG = "webdav_last_etag"
        const val KEY_BLOCK_QUIC = "block_quic"
        const val KEY_ALLOW_LAN = "allow_lan"
        const val KEY_BLOCK_ON_ROAMING = "block_on_roaming"
        const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
        const val KEY_SOCKS5_HOST = "socks5_host"
        const val KEY_SOCKS5_PORT = "socks5_port"
        const val KEY_SOCKS5_UDP = "socks5_udp"
        const val KEY_SOCKS5_USERNAME = "socks5_username"
        const val KEY_SOCKS5_PASSWORD = "socks5_password"
        const val KEY_SOCKS5_BYPASS_PACKAGE = "socks5_bypass_package"
        const val KEY_FORWARDING_DIRECT_PACKAGES = "forwarding_direct_packages"
        const val KEY_UPDATE_SOURCE = "update_source"
        const val KEY_UPDATE_REPO = "update_repo"
        const val KEY_UPDATE_CUSTOM_URL = "update_custom_url"
        const val KEY_UPDATE_CHANNEL = "update_channel"
        const val KEY_LEGACY_RULES_MIGRATED = "legacy_rules_migrated"
        const val DEFAULT_THEME_COLOR = 0xFF2E7D32.toInt()
    }
}

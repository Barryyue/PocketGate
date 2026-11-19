package com.networkcontrol.android.kotlin.platform.overlay

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.island.SpeedIslandFormatter
import com.networkcontrol.android.kotlin.core.island.SpeedIslandLabels
import com.networkcontrol.android.kotlin.core.island.SpeedIslandStats
import com.networkcontrol.android.kotlin.core.model.SpeedIslandFontWeight
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.core.prompts.PromptBroker
import com.networkcontrol.android.kotlin.core.storage.ActivityStore
import com.networkcontrol.android.kotlin.core.storage.TrafficStore
import com.networkcontrol.android.kotlin.engine.network.NetworkMonitor
import com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import com.networkcontrol.android.kotlin.engine.traffic.SpeedMonitor
import com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot
import com.networkcontrol.android.kotlin.platform.AppLog
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 服務啟動後的頂部「速度島」：可顯示上下行速度、今日流量、攔截數、待處理詢問或目前網路。
 * 只在保護開啟、螢幕解鎖、使用者開啟設定且有懸浮窗權限時顯示。
 */
class SpeedIslandController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val protection: ProtectionController,
    private val speedMonitor: SpeedMonitor,
    private val screenState: ScreenStateMonitor,
    private val themeColor: StateFlow<ComposeColor>,
    private val trafficStore: TrafficStore,
    private val activityStore: ActivityStore,
    private val prompts: PromptBroker,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val strings = UiStrings(
        context.resources.configuration.locales[0]?.toLanguageTag().orEmpty(),
    )
    private val labels: SpeedIslandLabels
        get() = SpeedIslandLabels(
            blocked = strings[S.BlockedToday],
            pending = strings[S.PendingPrompts],
            networkFallback = strings[S.IslandContentNetwork],
        )

    private var view: View? = null
    private var pillRoot: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var speedText: TextView? = null
    private var dotView: TextView? = null
    private var lastText: String? = null
    private var currentMode: SpeedIslandMode? = null
    private var currentShowDot: Boolean? = null
    private var cycleDownload = true
    private var typefaceWeight: SpeedIslandFontWeight? = null
    private var typefaceCache: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    @Volatile
    private var todayBytesIn = 0L

    @Volatile
    private var todayBytesOut = 0L

    @Volatile
    private var blockedToday = 0L

    @Volatile
    private var pendingCount = 0

    @Volatile
    private var networkName: String? = null

    @Volatile
    private var statusBarTextColor = Color.BLACK
    private var statusBarColorAt = 0L

    fun start() {
        refreshStatusBarColor(force = true)
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                refreshStatusBarColor(force = true)
                refresh()
            }

            // ComponentCallbacks.onLowMemory 已由 onTrimMemory 取代；介面仍要求實作，這裡不需要處理。
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        })
        // 桌布改變（顏色不同）時重新取色：用 API 27+ 的 OnColorsChangedListener，取代已棄用的 ACTION_WALLPAPER_CHANGED 廣播。
        runCatching {
            context.getSystemService(android.app.WallpaperManager::class.java)?.addOnColorsChangedListener(
                { _, _ ->
                    refreshStatusBarColor(force = true)
                    refresh()
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
        scope.launch {
            combine(
                protection.enabled,
                speedMonitor.speed,
                screenState.locked,
            ) { enabled, speed, locked -> Triple(enabled, speed, locked) }
                .collect { (enabled, speed, locked) ->
                    val visible = enabled && !locked && preferences.speedIslandEnabled &&
                        Settings.canDrawOverlays(context)
                    if (visible) {
                        show(speed)
                    } else {
                        hide()
                    }
                }
        }
        scope.launch {
            themeColor.collect { refresh() }
        }
        scope.launch {
            prompts.pending.collect { pending ->
                pendingCount = pending.size
                refresh()
            }
        }
        scope.launch {
            network.state.collect { state ->
                networkName = state.displayName
                refresh()
            }
        }
        scope.launch {
            while (scope.isActive) {
                runCatching { refreshStats() }
                    .onFailure { AppLog.w("speed-island", "stats failed: ${it.message}") }
                delay(60_000)
            }
        }
    }

    fun refresh() {
        refreshStatusBarColor()
        val visible = protection.enabled.value && !screenState.locked.value &&
            preferences.speedIslandEnabled && Settings.canDrawOverlays(context)
        if (visible) show(speedMonitor.speed.value) else hide()
    }

    private fun refreshStatusBarColor(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - statusBarColorAt < 60_000) return
        statusBarColorAt = now
        statusBarTextColor = computeStatusBarTextColor()
    }

    private suspend fun refreshStats() {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val summary = trafficStore.summarize(start, Time.now(), 24 * 60 * 60 * 1000L, 0)
        todayBytesIn = summary.bytesIn
        todayBytesOut = summary.bytesOut
        blockedToday = activityStore.countBlockedSince(start)
        refresh()
    }

    private fun show(speed: SpeedSnapshot) {
        handler.post {
            val mode = preferences.speedIslandMode
            val showDot = preferences.speedIslandShowDot
            if (view != null && (currentMode != mode || currentShowDot != showDot)) {
                view?.let { runCatching { windowManager?.removeView(it) } }
                view = null
                pillRoot = null
                dotView = null
                speedText = null
                lastText = null
            }
            if (view == null) {
                val pill = buildPill(mode, showDot) ?: return@post
                val statusBarMode = preferences.speedIslandStatusBarMode
                val layout = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    layoutFlags(statusBarMode),
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = overlayX()
                    y = overlayY(statusBarMode)
                    title = "NetworkControl speed"
                    packageName = context.packageName
                    if (Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode = if (statusBarMode) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30 && statusBarMode) {
                        fitInsetsTypes = 0
                    }
                }
                val added = runCatching {
                    windowManager?.addView(pill, layout)
                    true
                }.getOrElse {
                    AppLog.w("speed-island", "add failed: ${it.message}")
                    false
                }
                if (!added) return@post
                view = pill
                pillRoot = pill as? LinearLayout
                params = layout
                currentMode = mode
                currentShowDot = showDot
            }
            updateAppearance()
            applyPosition()
            updateText(speed, mode)
        }
    }

    private fun hide() {
        handler.post {
            view?.let { runCatching { windowManager?.removeView(it) } }
            view = null
            pillRoot = null
            dotView = null
            params = null
            speedText = null
            lastText = null
            currentMode = null
            currentShowDot = null
        }
    }

    private fun updateText(speed: SpeedSnapshot, mode: SpeedIslandMode) {
        val stats = SpeedIslandStats(
            downloadBytesPerSecond = speed.downloadBytesPerSecond,
            uploadBytesPerSecond = speed.uploadBytesPerSecond,
            todayBytesIn = todayBytesIn,
            todayBytesOut = todayBytesOut,
            blockedToday = blockedToday,
            pendingPrompts = pendingCount,
            networkName = networkName,
        )
        val text = SpeedIslandFormatter.format(
            content = preferences.speedIslandContent,
            mode = mode,
            stats = stats,
            swapOrder = preferences.speedIslandSwapOrder,
            showDownload = cycleDownload,
            labels = labels,
        )
        if (mode == SpeedIslandMode.Cycle) cycleDownload = !cycleDownload
        if (text == lastText) return
        lastText = text
        speedText?.text = text
    }

    private fun applyPosition() {
        val root = view ?: return
        val layout = params ?: return
        val statusBarMode = preferences.speedIslandStatusBarMode
        layout.flags = layoutFlags(statusBarMode)
        layout.x = overlayX()
        layout.y = overlayY(statusBarMode)
        if (Build.VERSION.SDK_INT >= 30) {
            layout.fitInsetsTypes = if (statusBarMode) {
                0
            } else {
                android.view.WindowInsets.Type.systemBars()
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            layout.layoutInDisplayCutoutMode = if (statusBarMode) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        runCatching { windowManager?.updateViewLayout(root, layout) }
    }

    private fun layoutFlags(statusBarMode: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (preferences.speedIslandOpacity <= 0.001f) {
            // 全透明時不攔截觸控，避免擋住狀態欄下拉與桌面手勢。
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (statusBarMode) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return flags
    }

    private fun overlayX(): Int = dp(preferences.speedIslandOffsetXDp)

    private fun overlayY(statusBarMode: Boolean): Int = if (statusBarMode) {
        dp(preferences.speedIslandOffsetYDp)
    } else {
        dp(
            8 + preferences.speedIslandOffsetYDp +
                if (preferences.vivoIslandCompatible) 16 else 0,
        ).coerceAtLeast(0)
    }

    private fun updateAppearance() {
        val root = pillRoot ?: return
        val opacity = preferences.speedIslandOpacity.coerceIn(0f, 1f)
        root.background = GradientDrawable().apply {
            setColor(islandBackgroundColor())
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), if (opacity <= 0.001f) Color.TRANSPARENT else 0x40FFFFFF)
        }
        root.elevation = if (opacity <= 0.001f) 0f else dp(8).toFloat()
        val color = textColor()
        speedText?.apply {
            setTextColor(color)
            if (opacity <= 0.15f) {
                setShadowLayer(1.5f, 0f, 0.5f, contrastShadow(color))
            } else {
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                scaledTextSize(if (currentMode == SpeedIslandMode.Mini) 10f else 12f),
            )
            typeface = labelTypeface()
        }
        dotView?.setTextColor(preferences.speedIslandDotColor)
    }

    private fun scaledTextSize(base: Float): Float =
        base * preferences.speedIslandFontScalePercent / 100f

    private fun labelTypeface(): Typeface {
        val weight = preferences.speedIslandFontWeight
        if (typefaceWeight == weight) return typefaceCache
        typefaceCache = when (weight) {
            SpeedIslandFontWeight.Normal -> Typeface.create("sans-serif", Typeface.NORMAL)
            SpeedIslandFontWeight.Medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            SpeedIslandFontWeight.Bold -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        typefaceWeight = weight
        return typefaceCache
    }

    /** 背景全透明時靠極輕微陰影拉開與桌布的對比：大多數顏色配半透明黑影，極暗字才配淡白影。 */
    private fun contrastShadow(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luma = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luma > 55) 0x80000000.toInt() else 0x80FFFFFF.toInt()
    }

    private fun textColor(): Int = when (preferences.speedIslandTextColorMode) {
        "custom" -> preferences.speedIslandTextColor
        "statusbar" -> statusBarTextColor
        else -> themeColor.value.toArgb()
    }

    /**
     * Android 不提供查詢狀態欄圖示顏色的 API；用系統深色模式 + 桌布色調做近似。
     * 深色桌布通常配白色狀態欄圖示，淺色桌布配黑色。
     */
    private fun computeStatusBarTextColor(): Int {
        if (isNightMode()) return Color.WHITE
        val hints = runCatching {
            context.getSystemService(WallpaperManager::class.java)
                ?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.colorHints
        }.getOrNull() ?: 0
        return when {
            (hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0 -> Color.BLACK
            (hints and WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0 -> Color.WHITE
            else -> Color.BLACK
        }
    }

    private fun isNightMode(): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun islandBackgroundColor(): Int {
        val alpha = (preferences.speedIslandOpacity.coerceIn(0f, 1f) * 255).toInt()
        return (alpha shl 24) or 0x00101010
    }

    private fun buildPill(mode: SpeedIslandMode, showDot: Boolean): View? {
        val mini = mode == SpeedIslandMode.Mini
        val horizontal = if (mini) 8 else 14
        val vertical = if (mini) 4 else 8
        val radius = if (mini) 12 else 20
        val textSize = scaledTextSize(if (mini) 10f else 12f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
            background = GradientDrawable().apply {
                setColor(islandBackgroundColor())
                cornerRadius = dp(radius).toFloat()
                setStroke(
                    dp(1),
                    if (preferences.speedIslandOpacity <= 0.001f) {
                        Color.TRANSPARENT
                    } else {
                        0x40FFFFFF
                    },
                )
            }
            elevation = if (preferences.speedIslandOpacity <= 0.001f) {
                0f
            } else {
                dp(if (mini) 4 else 8).toFloat()
            }
            setOnClickListener {
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }.onFailure { AppLog.w("speed-island", "open app failed: ${it.message}") }
            }
        }
        val dot = TextView(context).apply {
            text = "●"
            setTextColor(preferences.speedIslandDotColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mini) 7f else 10f)
            setPadding(0, 0, dp(if (mini) 3 else 6), 0)
        }
        dotView = dot
        val label = TextView(context).apply {
            setTextColor(textColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            typeface = labelTypeface()
            includeFontPadding = false
            paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(320)
            text = SpeedIslandFormatter.format(
                content = preferences.speedIslandContent,
                mode = mode,
                stats = SpeedIslandStats(),
                swapOrder = preferences.speedIslandSwapOrder,
                showDownload = true,
                labels = labels,
            )
        }
        speedText = label
        if (showDot) root.addView(dot)
        root.addView(label)
        return root
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

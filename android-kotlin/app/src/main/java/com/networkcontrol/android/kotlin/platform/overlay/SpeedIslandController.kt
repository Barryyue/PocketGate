package com.networkcontrol.android.kotlin.platform.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import com.networkcontrol.android.kotlin.engine.traffic.SpeedMonitor
import com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot
import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 服務啟動後的頂部「速度島」：預設顯示上傳 / 下載速度，點擊開啟主界面。
 * 只在保護開啟、螢幕解鎖、使用者開啟設定且有懸浮窗權限時顯示。
 */
class SpeedIslandController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val protection: ProtectionController,
    private val speedMonitor: SpeedMonitor,
    private val screenState: ScreenStateMonitor,
    private val themeColor: StateFlow<ComposeColor>,
    private val scope: CoroutineScope,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var pillRoot: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var speedText: TextView? = null
    private var dotView: TextView? = null
    private var lastText: String? = null
    private var currentMode: SpeedIslandMode? = null
    private var currentShowDot: Boolean? = null
    private var cycleDownload = true

    fun start() {
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
    }

    fun refresh() {
        val visible = protection.enabled.value && !screenState.locked.value &&
            preferences.speedIslandEnabled && Settings.canDrawOverlays(context)
        if (visible) show(speedMonitor.speed.value) else hide()
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
        val swap = preferences.speedIslandSwapOrder
        val text = when (mode) {
            SpeedIslandMode.Full -> {
                if (swap) {
                    "↑ ${formatRate(speed.uploadBytesPerSecond)}/s   ↓ ${formatRate(speed.downloadBytesPerSecond)}/s"
                } else {
                    "↓ ${formatRate(speed.downloadBytesPerSecond)}/s   ↑ ${formatRate(speed.uploadBytesPerSecond)}/s"
                }
            }
            SpeedIslandMode.Cycle -> {
                val showDownload = if (swap) !cycleDownload else cycleDownload
                cycleDownload = !cycleDownload
                if (showDownload) {
                    "↓ ${formatRate(speed.downloadBytesPerSecond)}/s"
                } else {
                    "↑ ${formatRate(speed.uploadBytesPerSecond)}/s"
                }
            }
            SpeedIslandMode.Mini -> {
                if (swap) {
                    "↑${formatRateShort(speed.uploadBytesPerSecond)} ↓${formatRateShort(speed.downloadBytesPerSecond)}"
                } else {
                    "↓${formatRateShort(speed.downloadBytesPerSecond)} ↑${formatRateShort(speed.uploadBytesPerSecond)}"
                }
            }
        }
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
        speedText?.setTextColor(color)
        speedText?.setShadowLayer(4f, 0f, 1f, contrastShadow(color))
        dotView?.setTextColor(preferences.speedIslandDotColor)
    }

    /** 背景全透明時靠陰影拉開與桌布的對比：亮字配黑陰影，暗字配白陰影。 */
    private fun contrastShadow(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luma = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luma > 140) Color.BLACK else Color.WHITE
    }

    private fun textColor(): Int = when (preferences.speedIslandTextColorMode) {
        "custom" -> preferences.speedIslandTextColor
        "statusbar" -> if (isNightMode()) Color.WHITE else Color.BLACK
        else -> themeColor.value.toArgb()
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
        val textSize = if (mini) 10f else 12f
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
            maxLines = 1
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            text = when (mode) {
                SpeedIslandMode.Full -> "↓ 0 B/s   ↑ 0 B/s"
                SpeedIslandMode.Cycle -> "↓ 0 B/s"
                SpeedIslandMode.Mini -> "↓0B ↑0B"
            }
        }
        speedText = label
        if (showDot) root.addView(dot)
        root.addView(label)
        return root
    }

    private fun formatRate(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(value, units[index])
    }

    private fun formatRateShort(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("K", "M", "G")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (value >= 10) {
            "${value.toInt()}${units[index]}"
        } else {
            "%.1f%s".format(value, units[index])
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

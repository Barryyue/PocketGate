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
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.core.model.SpeedIslandMode
import com.networkcontrol.android.kotlin.engine.network.ScreenStateMonitor
import com.networkcontrol.android.kotlin.engine.protection.ProtectionController
import com.networkcontrol.android.kotlin.engine.traffic.SpeedMonitor
import com.networkcontrol.android.kotlin.engine.traffic.SpeedSnapshot
import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.CoroutineScope
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
    private val scope: CoroutineScope,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var pillRoot: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var speedText: TextView? = null
    private var lastText: String? = null
    private var currentMode: SpeedIslandMode? = null
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
    }

    fun refresh() {
        val visible = protection.enabled.value && !screenState.locked.value &&
            preferences.speedIslandEnabled && Settings.canDrawOverlays(context)
        if (visible) show(speedMonitor.speed.value) else hide()
    }

    private fun show(speed: SpeedSnapshot) {
        handler.post {
            val mode = preferences.speedIslandMode
            if (view != null && currentMode != mode) {
                view?.let { runCatching { windowManager?.removeView(it) } }
                view = null
                pillRoot = null
                speedText = null
                lastText = null
            }
            if (view == null) {
                val pill = buildPill(mode) ?: return@post
                val statusBarMode = preferences.speedIslandStatusBarMode
                var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                if (statusBarMode) {
                    flags = flags or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                }
                val layout = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = dp(preferences.speedIslandOffsetXDp)
                    y = if (statusBarMode) {
                        dp(preferences.speedIslandOffsetYDp)
                    } else {
                        dp(
                            8 + preferences.speedIslandOffsetYDp +
                                if (preferences.vivoIslandCompatible) 16 else 0,
                        ).coerceAtLeast(0)
                    }
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
            params = null
            speedText = null
            lastText = null
            currentMode = null
        }
    }

    private fun updateText(speed: SpeedSnapshot, mode: SpeedIslandMode) {
        val text = when (mode) {
            SpeedIslandMode.Full ->
                "↓ ${formatRate(speed.downloadBytesPerSecond)}/s   ↑ ${formatRate(speed.uploadBytesPerSecond)}/s"
            SpeedIslandMode.Cycle -> {
                cycleDownload = !cycleDownload
                if (cycleDownload) {
                    "↓ ${formatRate(speed.downloadBytesPerSecond)}/s"
                } else {
                    "↑ ${formatRate(speed.uploadBytesPerSecond)}/s"
                }
            }
            SpeedIslandMode.Mini ->
                "↓${formatRateShort(speed.downloadBytesPerSecond)} ↑${formatRateShort(speed.uploadBytesPerSecond)}"
        }
        if (text == lastText) return
        lastText = text
        speedText?.text = text
    }

    private fun applyPosition() {
        val root = view ?: return
        val layout = params ?: return
        val statusBarMode = preferences.speedIslandStatusBarMode
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (statusBarMode) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        layout.flags = flags
        layout.x = dp(preferences.speedIslandOffsetXDp)
        layout.y = if (statusBarMode) {
            dp(preferences.speedIslandOffsetYDp)
        } else {
            dp(
                8 + preferences.speedIslandOffsetYDp +
                    if (preferences.vivoIslandCompatible) 16 else 0,
            ).coerceAtLeast(0)
        }
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

    private fun updateAppearance() {
        val root = pillRoot ?: return
        root.background = GradientDrawable().apply {
            setColor(islandBackgroundColor())
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0x40FFFFFF)
        }
    }

    private fun islandBackgroundColor(): Int {
        val alpha = (preferences.speedIslandOpacity.coerceIn(0.1f, 1f) * 255).toInt()
        return (alpha shl 24) or 0x00101010
    }

    private fun buildPill(mode: SpeedIslandMode): View? {
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
                setStroke(dp(1), 0x40FFFFFF)
            }
            elevation = dp(if (mini) 4 else 8).toFloat()
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
            setTextColor(0xFF4CAF50.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mini) 7f else 10f)
            setPadding(0, 0, dp(if (mini) 3 else 6), 0)
        }
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
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
        root.addView(dot)
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

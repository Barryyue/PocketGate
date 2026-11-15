package com.networkcontrol.android.kotlin.platform.prompt

import android.content.Context
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.platform.AppIconLoader
import com.networkcontrol.android.kotlin.platform.AppLog
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings

/**
 * 系統級懸浮詢問面板。只用原生 View，避免 ComposeView 在 WindowManager 中的生命週期問題。
 * 所有 WindowManager 操作都在主執行緒。
 */
class PromptOverlayController(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var currentId: String? = null
    private var currentPrompt: PromptSnapshot? = null
    private var dismissedId: String? = null
    private var holdRunnable: Runnable? = null
    private var unavailableUntil = 0L

    var onAnswer: ((String, VerdictAction, RuleLifetime) -> Unit)? = null
    var onDismiss: ((String) -> Unit)? = null
    var onHold: ((String) -> Unit)? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun show(prompt: PromptSnapshot) {
        if (!canDrawOverlays()) {
            hide()
            return
        }
        if (System.currentTimeMillis() < unavailableUntil) return
        handler.post {
            if (prompt.id == dismissedId) return@post
            if (currentId == prompt.id && view != null) {
                currentPrompt = prompt
                return@post
            }
            removeView()
            val panel = buildPanel(prompt) ?: return@post
            val params = buildParams()
            val added = runCatching {
                windowManager?.addView(panel, params)
                true
            }.getOrElse {
                AppLog.w("overlay", "add failed: ${it.message}")
                false
            }
            if (!added) {
                unavailableUntil = System.currentTimeMillis() + 30_000
                return@post
            }
            view = panel
            windowParams = params
            currentId = prompt.id
            currentPrompt = prompt
            applyCustomPosition(panel, params)
            startHold(prompt.id)
            AppLog.i("overlay", "shown prompt=${prompt.id} package=${prompt.app?.path}")
        }
    }

    fun hide() {
        handler.post {
            removeView()
        }
    }

    fun refresh() {
        handler.post {
            val prompt = currentPrompt ?: return@post
            removeView()
            show(prompt)
        }
    }

    fun dispose() {
        handler.post {
            removeView()
        }
        onAnswer = null
        onDismiss = null
        onHold = null
    }

    private fun removeView() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        view?.let { runCatching { windowManager?.removeView(it) } }
        view = null
        windowParams = null
        currentId = null
    }

    private fun startHold(promptId: String) {
        holdRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (currentId != promptId) return
                onHold?.invoke(promptId)
                handler.postDelayed(this, 10_000)
            }
        }
        holdRunnable = runnable
        handler.postDelayed(runnable, 10_000)
    }

    private fun applyCustomPosition(panel: View, params: WindowManager.LayoutParams) {
        if (preferences.promptOverlayPosition != "custom") return
        panel.post {
            val screenHeight = if (Build.VERSION.SDK_INT >= 30) {
                windowManager?.currentWindowMetrics?.bounds?.height()
            } else {
                context.resources.displayMetrics.heightPixels
            } ?: context.resources.displayMetrics.heightPixels
            val available = (screenHeight - panel.height).coerceAtLeast(1)
            val y = (available * preferences.promptOverlayCustomRatio).toInt().coerceIn(0, available)
            params.y = y
            runCatching { windowManager?.updateViewLayout(panel, params) }
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager?.currentWindowMetrics?.bounds
        } else {
            null
        }
        val screenWidth = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val width = minOf(screenWidth - dp(32), dp(560))
        val position = preferences.promptOverlayPosition
        val gravity = if (position == "bottom") {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            y = if (position == "custom") 0 else dp(8)
            title = "NetworkControl prompt"
            packageName = context.packageName
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
    }

    private fun buildPanel(prompt: PromptSnapshot): View? {
        val strings = UiStrings(context.resources.configuration.locales[0]?.toLanguageTag().orEmpty())
        val night = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val primary = preferences.themeColor
        val surface = if (night) 0xFF1C1B1F.toInt() else 0xFFF7F2FA.toInt()
        val onSurface = if (night) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
        val onSurfaceVariant = if (night) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
        val onPrimary = if (luminance(primary) > 0.5) Color.BLACK else Color.WHITE
        val outline = if (night) 0xFF49454F.toInt() else 0xFFCAC4D0.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), outline)
            }
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val packageName = prompt.app?.path?.removePrefix("android-app://")
            ?: prompt.executablePath?.removePrefix("android-app://")
            ?: ""
        AppIconLoader.loadBitmap(context, packageName)?.let { bitmap ->
            header.addView(
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                },
            )
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val appLabel = prompt.app?.displayName
            ?: prompt.app?.name
            ?: prompt.executablePath
            ?: strings[S.UnknownApp]
        titleColumn.addView(
            textView(
                strings.format(S.PromptTitle, appLabel),
                16f,
                onSurface,
                bold = true,
            ),
        )
        val first = prompt.flows.firstOrNull()
        val target = first?.let {
            it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
        }.orEmpty()
        if (preferences.promptShowDetails) {
            titleColumn.addView(
                textView(
                    "${strings[S.PromptTarget]}：$target · ${first?.protocol?.name.orEmpty()}",
                    12f,
                    onSurfaceVariant,
                ),
            )
            if (prompt.flows.size > 1) {
                titleColumn.addView(
                    textView(
                        strings.format(S.PromptFlows, prompt.flows.size),
                        12f,
                        onSurfaceVariant,
                    ),
                )
            }
        }
        header.addView(titleColumn)
        root.addView(header)

        if (preferences.promptShowExplanation) {
            root.addView(
                textView(
                    strings[S.PromptBlockedHint],
                    12f,
                    onSurfaceVariant,
                ).apply {
                    setPadding(0, dp(8), 0, dp(6))
                },
            )
        }

        var lifetime = RuleLifetime.Permanent
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chipViews = mutableListOf<Triple<TextView, RuleLifetime, String>>()
        fun addChip(label: String, value: RuleLifetime) {
            val chip = textView(label, 12f, onSurfaceVariant).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    lifetime = value
                    updateChips(chipViews, lifetime, primary, onPrimary, onSurfaceVariant, outline)
                }
            }
            chipViews += Triple(chip, value, label)
            chips.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
        }
        addChip(strings[S.PromptOnce], RuleLifetime.UntilAppExit)
        addChip(strings[S.PromptPermanent], RuleLifetime.Permanent)
        addChip(strings[S.PromptOneHour], RuleLifetime.Timed)
        addChip(strings[S.PromptUntilRestart], RuleLifetime.UntilRestart)
        root.addView(chips)
        updateChips(chipViews, lifetime, primary, onPrimary, onSurfaceVariant, outline)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val later = textView(strings[S.PromptLater], 13f, onSurfaceVariant).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                dismissedId = prompt.id
                onDismiss?.invoke(prompt.id)
                hide()
            }
        }
        buttons.addView(later, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val deny = textView(strings[S.Deny], 13f, onSurface).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), outline)
            }
            setOnClickListener {
                dismissedId = prompt.id
                onAnswer?.invoke(prompt.id, VerdictAction.Deny, lifetime)
                hide()
            }
        }
        buttons.addView(deny)
        val allow = textView(strings[S.Allow], 13f, onPrimary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(primary)
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener {
                dismissedId = prompt.id
                onAnswer?.invoke(prompt.id, VerdictAction.Allow, lifetime)
                hide()
            }
        }
        buttons.addView(
            allow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        root.addView(buttons)
        return root
    }

    private fun updateChips(
        chips: List<Triple<TextView, RuleLifetime, String>>,
        selected: RuleLifetime,
        primary: Int,
        onPrimary: Int,
        idle: Int,
        outline: Int,
    ) {
        chips.forEach { (view, value, _) ->
            val active = value == selected
            view.tag = active
            view.setTextColor(if (active) onPrimary else idle)
            view.background = GradientDrawable().apply {
                setColor(if (active) primary else Color.TRANSPARENT)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (active) primary else outline)
            }
        }
    }

    private fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun luminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

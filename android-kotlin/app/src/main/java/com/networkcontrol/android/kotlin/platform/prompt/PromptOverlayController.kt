package com.networkcontrol.android.kotlin.platform.prompt

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.RadioButton
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
import com.networkcontrol.android.kotlin.ui.prompt.promptDisplayName

/**
 * 系統級懸浮詢問面板。只用原生 View，避免 ComposeView 在 WindowManager 中的生命週期問題。
 * 所有 WindowManager 操作都在主執行緒。
 */
class PromptOverlayController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val timedMinutesProvider: () -> Int = { 60 },
    private val networkKindProvider: () -> com.networkcontrol.android.kotlin.core.model.NetworkKind? = { null },
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

    private fun buildParams(): WindowManager.LayoutParams {
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager?.currentWindowMetrics?.bounds
        } else {
            null
        }
        val screenWidth = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val position = preferences.promptOverlayPosition
        val sheet = position == "bottom"
        val width = if (sheet) WindowManager.LayoutParams.MATCH_PARENT else minOf(screenWidth - dp(32), dp(560))
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
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (sheet) WindowManager.LayoutParams.FLAG_DIM_BEHIND else 0),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            if (sheet) dimAmount = 0.32f
            y = when {
                position == "custom" -> 0
                position == "top" && preferences.speedIslandEnabled && Settings.canDrawOverlays(context) ->
                    dp(
                        (if (preferences.speedIslandMode == com.networkcontrol.android.kotlin.core.model.SpeedIslandMode.Mini) 46 else 58) +
                            maxOf(0, preferences.speedIslandOffsetYDp) +
                            if (preferences.vivoIslandCompatible) 16 else 0,
                    )
                sheet -> 0
                else -> dp(8)
            }
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
        val accent = preferences.themeColor
        val surface = if (night) 0xFF2B2930.toInt() else Color.WHITE
        val onSurface = if (night) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
        val onSurfaceVariant = if (night) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
        val divider = if (night) 0xFF49454F.toInt() else 0xFFE0E0E0.toInt()
        val error = if (night) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        val sheet = preferences.promptOverlayPosition == "bottom"

        // 仿紅魔：貼底白色面板、上方圓角；置頂 / 自訂位置時四角圓角並加細框。
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(6))
            background = GradientDrawable().apply {
                setColor(surface)
                val r = dp(20).toFloat()
                cornerRadii = if (sheet) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
                if (!sheet) setStroke(dp(1), divider)
            }
            elevation = dp(12).toFloat()
        }

        val appLabel = prompt.app?.displayName
            ?: prompt.app?.name
            ?: promptDisplayName(prompt.executablePath)
            ?: strings[S.UnknownApp]
        val ownName = context.applicationInfo.loadLabel(context.packageManager).toString()
        root.addView(
            textView(
                PromptSheetText.message(strings, ownName, appLabel, networkKindProvider()),
                18f,
                onSurface,
            ).apply {
                maxLines = 4
                setLineSpacing(dp(4).toFloat(), 1f)
            },
        )

        if (preferences.promptShowDetails) {
            val first = prompt.flows.firstOrNull()
            val target = first?.let {
                it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
            }.orEmpty()
            val more = if (prompt.flows.size > 1) " · " + strings.format(S.PromptFlows, prompt.flows.size) else ""
            root.addView(
                textView(
                    "${strings[S.PromptTarget]}：$target · ${first?.protocol?.name.orEmpty()}$more",
                    13f,
                    onSurfaceVariant,
                ).apply { setPadding(0, dp(6), 0, 0) },
            )
        }
        if (prompt.isTamperAlert) {
            root.addView(
                textView(strings[S.PromptTamper], 13f, error).apply { setPadding(0, dp(6), 0, 0) },
            )
        }

        val denyPermanent = preferences.promptDenyPermanent
        var dontAskAgain = false
        if (!denyPermanent) {
            // 勾選後「禁止」改為永久禁止；不勾只禁止到應用關閉。圓形勾選與紅魔一致。
            val dontAsk = RadioButton(context).apply {
                text = strings[S.PromptDontAskAgain]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(onSurface)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, onSurfaceVariant),
                )
                setOnClickListener {
                    dontAskAgain = !dontAskAgain
                    isChecked = dontAskAgain
                }
            }
            root.addView(
                dontAsk,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(8)
                    marginStart = -dp(6)
                },
            )
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        fun addAction(label: String, onClick: () -> Unit) {
            if (actions.childCount > 0) {
                actions.addView(
                    View(context).apply { setBackgroundColor(divider) },
                    LinearLayout.LayoutParams(dp(1), dp(22)),
                )
            }
            val button = textView(label, 17f, accent).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(14))
                background = RippleDrawable(
                    ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000),
                    null,
                    ColorDrawable(Color.WHITE),
                )
                setOnClickListener {
                    dismissedId = prompt.id
                    onClick()
                    hide()
                }
            }
            actions.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addAction(strings[S.PromptDeny]) {
            onAnswer?.invoke(prompt.id, VerdictAction.Deny, PromptDenyPolicy.lifetime(dontAskAgain, denyPermanent))
        }
        addAction(strings[S.Allow]) {
            onAnswer?.invoke(prompt.id, VerdictAction.Allow, RuleLifetime.Permanent)
        }
        addAction(strings[S.PromptOnce]) {
            onAnswer?.invoke(prompt.id, VerdictAction.Allow, RuleLifetime.UntilAppExit)
        }
        if (preferences.promptShowTimedOption) {
            addAction(strings.format(S.PromptTimedShort, timedMinutesProvider())) {
                onAnswer?.invoke(prompt.id, VerdictAction.Allow, RuleLifetime.Timed)
            }
        }
        root.addView(actions)
        return root
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

package com.networkcontrol.android.kotlin.platform.quick

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings

/**
 * 長按應用圖示的動態捷徑。標籤隨狀態改變（例如保護開啟時顯示「關閉保護」），
 * 所以用動態捷徑而不是 shortcuts.xml；也避免 debug 版套件名稱不同時靜態捷徑失效。
 * 只有標籤真的改變時才更新，避免觸發 ShortcutManager 的背景呼叫頻率限制。
 */
object QuickShortcuts {
    @Volatile
    private var lastSignature: String? = null

    fun publish(context: Context, state: QuickState, now: Long = System.currentTimeMillis()) {
        val strings = UiStrings(context.resources.configuration.locales[0]?.toLanguageTag().orEmpty())
        val items = listOf(
            Triple(
                QuickAction.ToggleProtection,
                if (state.protectionOn) strings[S.ShortcutProtectionOff] else strings[S.ShortcutProtectionOn],
                R.drawable.shortcut_shield,
            ),
            Triple(
                QuickAction.CycleMode,
                strings.format(S.ShortcutModeTo, modeLabel(strings, QuickLogic.nextMode(state.baseMode))),
                R.drawable.shortcut_tune,
            ),
            Triple(
                QuickAction.ToggleBlockAll,
                if (state.blockAll) strings[S.ShortcutBlockAllOff] else strings[S.ShortcutBlockAllOn],
                R.drawable.shortcut_block,
            ),
            Triple(
                QuickAction.ToggleDnd,
                if (state.dndActive(now)) strings[S.ShortcutDndOff] else strings[S.ShortcutDndOn],
                R.drawable.shortcut_dnd,
            ),
            Triple(
                QuickAction.ToggleFocus,
                if (state.focusActive(now)) strings[S.ShortcutFocusOff]
                else strings.format(S.ShortcutFocusOn, QuickLogic.FOCUS_MINUTES),
                R.drawable.shortcut_focus,
            ),
        )
        val signature = items.joinToString("|") { it.second }
        if (signature == lastSignature) return
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
        val shortcuts = items.take(max).mapIndexed { index, (action, label, icon) ->
            ShortcutInfoCompat.Builder(context, action.id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(QuickActions.activityIntent(context, action))
                .setRank(index)
                .build()
        }
        val ok = runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }.getOrDefault(false)
        if (ok) lastSignature = signature
    }
}

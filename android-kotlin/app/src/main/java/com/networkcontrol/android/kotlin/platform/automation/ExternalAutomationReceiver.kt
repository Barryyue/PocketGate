package com.networkcontrol.android.kotlin.platform.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.core.model.FilterMode
import com.networkcontrol.android.kotlin.platform.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 第三方自動化（Tasker / MacroDroid）廣播命令解析與驗證純函數。
 */
sealed class ExternalAutomationCommand {
    data class SetProtection(val enabled: Boolean) : ExternalAutomationCommand()
    data class SetMode(val mode: FilterMode) : ExternalAutomationCommand()
    data class SwitchProfile(val query: String) : ExternalAutomationCommand()
    data class FocusStart(val minutes: Int) : ExternalAutomationCommand()
    data object FocusStop : ExternalAutomationCommand()
    data class DndStart(val minutes: Int) : ExternalAutomationCommand()
    data object DndStop : ExternalAutomationCommand()

    companion object {
        const val ACTION = "com.networkcontrol.android.kotlin.action.AUTOMATION"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_MINUTES = "minutes"

        fun authorize(enabled: Boolean, expectedToken: String, providedToken: String?): Boolean {
            if (!enabled) return false
            val expected = expectedToken.trim()
            if (expected.isEmpty()) return true
            val provided = providedToken?.trim().orEmpty()
            if (provided.isEmpty()) return false
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                provided.toByteArray(Charsets.UTF_8),
            )
        }

        fun parse(
            command: String?,
            mode: String? = null,
            profile: String? = null,
            minutes: Int = -1,
        ): ExternalAutomationCommand? = when (command?.trim()?.lowercase()) {
            "protection_on" -> SetProtection(true)
            "protection_off" -> SetProtection(false)
            "set_mode" -> parseMode(mode)?.let { SetMode(it) }
            "switch_profile" -> profile?.trim()?.takeIf { it.isNotEmpty() }?.let { SwitchProfile(it) }
            "focus_start" -> FocusStart(if (minutes > 0) minutes.coerceIn(1, 24 * 60) else 30)
            "focus_stop" -> FocusStop
            "dnd_start" -> DndStart(if (minutes > 0) minutes.coerceIn(1, 24 * 60) else 60)
            "dnd_stop" -> DndStop
            else -> null
        }

        private fun parseMode(raw: String?): FilterMode? = when (raw?.trim()?.lowercase()) {
            "ask" -> FilterMode.Ask
            "whitelist" -> FilterMode.Whitelist
            "blacklist" -> FilterMode.Blacklist
            "block_all", "blockall" -> FilterMode.BlockAll
            "disabled", "off" -> FilterMode.Disabled
            else -> null
        }

        fun exampleSnippet(token: String): String = buildString {
            appendLine("Action: $ACTION")
            appendLine("Package: com.networkcontrol.android.kotlin")
            appendLine("Extras:")
            appendLine("  command: set_mode | protection_on | protection_off | switch_profile | focus_start | focus_stop | dnd_start | dnd_stop")
            appendLine("  mode: ask | whitelist | blacklist | block_all | disabled")
            appendLine("  profile: <Profile Name or ID>")
            appendLine("  minutes: 30")
            if (token.isNotBlank()) {
                appendLine("  token: $token")
            }
        }
    }
}

/**
 * 接收 Tasker / MacroDroid 等第三方自動化工具的廣播 Intent。
 * 預設關閉（fail-closed），且可搭配驗證 Token 防止未授權應用控制防火牆。
 */
class ExternalAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ExternalAutomationCommand.ACTION) return
        val app = context.applicationContext as? App ?: return
        val prefs = app.container.preferences
        val providedToken = intent.getStringExtra(ExternalAutomationCommand.EXTRA_TOKEN)
        if (!ExternalAutomationCommand.authorize(
                enabled = prefs.externalAutomationEnabled,
                expectedToken = prefs.externalAutomationToken,
                providedToken = providedToken,
            )
        ) {
            AppLog.w("automation-ext", "rejected external broadcast (enabled=${prefs.externalAutomationEnabled})")
            return
        }
        val cmd = ExternalAutomationCommand.parse(
            command = intent.getStringExtra(ExternalAutomationCommand.EXTRA_COMMAND),
            mode = intent.getStringExtra(ExternalAutomationCommand.EXTRA_MODE),
            profile = intent.getStringExtra(ExternalAutomationCommand.EXTRA_PROFILE),
            minutes = intent.getIntExtra(ExternalAutomationCommand.EXTRA_MINUTES, -1),
        ) ?: run {
            AppLog.w("automation-ext", "unknown external automation command")
            return
        }

        val pending = goAsync()
        app.container.scope.launch(Dispatchers.Default) {
            try {
                app.container.awaitStarted()
                val backend = app.container.backend
                when (cmd) {
                    is ExternalAutomationCommand.SetProtection -> backend.setProtection(cmd.enabled)
                    is ExternalAutomationCommand.SetMode -> backend.setMode(cmd.mode)
                    is ExternalAutomationCommand.SwitchProfile -> {
                        val match = app.container.profiles.all.firstOrNull {
                            it.id.equals(cmd.query, ignoreCase = true) ||
                                it.name.equals(cmd.query, ignoreCase = true)
                        }
                        if (match != null) {
                            backend.activateProfile(match.id)
                        } else {
                            AppLog.w("automation-ext", "profile not found: ${cmd.query}")
                        }
                    }
                    is ExternalAutomationCommand.FocusStart ->
                        backend.startFocus(cmd.minutes, app.container.preferences.focusLastAllowed)
                    ExternalAutomationCommand.FocusStop -> backend.stopFocus()
                    is ExternalAutomationCommand.DndStart ->
                        backend.setDoNotDisturb(System.currentTimeMillis() + cmd.minutes * 60_000L)
                    ExternalAutomationCommand.DndStop -> backend.setDoNotDisturb(null)
                }
                AppLog.i("automation-ext", "executed external command: $cmd")
            } finally {
                pending.finish()
            }
        }
    }
}

package com.networkcontrol.android.kotlin.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.Time
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.PromptDecision
import com.networkcontrol.android.kotlin.core.model.Rule
import com.networkcontrol.android.kotlin.core.model.RuleConditions
import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.core.model.RuleSource
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.rules.AppMatcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知動作。進程被回收後，詢問本身已經不存在，但通知仍可能留在通知欄。
 * 這時按「允許 / 拒絕」會直接用通知帶來的應用路徑建立規則，避免操作失效。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val appPath = intent.getStringExtra(EXTRA_APP_PATH)
        val pending = goAsync()
        val container = (context.applicationContext as App).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.awaitStarted()
                val action = if (allow) VerdictAction.Allow else VerdictAction.Deny
                val result = container.prompts.answer(
                    promptId,
                    PromptDecision(
                        action = action,
                        lifetime = RuleLifetime.Permanent,
                    ),
                )
                if (!result.found) {
                    val created = createFallbackRule(
                        container = container,
                        appPath = appPath,
                        action = action,
                    )
                    if (!created) {
                        // Toast 只能在有 Looper 的執行緒顯示；在 Dispatchers.Default 上呼叫會直接崩潰。
                        val appContext = context.applicationContext
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, R.string.prompt_expired, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (ex: Exception) {
                AppLog.e("prompt", "notification action failed", ex)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun createFallbackRule(
        container: com.networkcontrol.android.kotlin.AppContainer,
        appPath: String?,
        action: VerdictAction,
    ): Boolean {
        if (appPath.isNullOrBlank() || !appPath.startsWith("android-app://")) return false
        if (appPath.startsWith("android-app://uid:")) return false
        val packageName = appPath.removePrefix("android-app://")
        val app = container.appCatalog.find(packageName)
        if (app == null) return false
        val now = Time.now()
        container.rules.add(
            Rule(
                id = java.util.UUID.randomUUID().toString(),
                profileId = container.profiles.active.id,
                app = AppMatcherFactory.create(container.appCatalog.toIdentity(app)),
                conditions = RuleConditions(direction = Direction.Outbound),
                action = action,
                lifetime = RuleLifetime.Permanent,
                source = RuleSource.Prompt,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return true
    }

    companion object {
        const val ACTION_ANSWER = "com.networkcontrol.android.kotlin.action.ANSWER_PROMPT"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_APP_PATH = "app_path"
    }
}

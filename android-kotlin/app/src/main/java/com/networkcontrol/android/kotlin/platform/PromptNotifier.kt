package com.networkcontrol.android.kotlin.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.networkcontrol.android.kotlin.AppPreferences
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot

/**
 * 只負責「渲染 / 取消」通知，不訂閱 PromptBroker。
 * 由 PromptRouter 決定每個詢問要用高重要性還是安靜渠道。
 */
class PromptNotifier(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    private val shown = mutableMapOf<String, Int>()
    private val signatures = mutableMapOf<String, String>()

    fun render(prompt: PromptSnapshot, soft: Boolean) {
        if (!preferences.notificationsEnabled) {
            cancel(prompt.id)
            return
        }
        val signature = buildString {
            append(soft)
            append(':')
            append(prompt.flows.size)
            append(':')
            append(prompt.flows.firstOrNull()?.remote?.toString().orEmpty())
        }
        if (signatures[prompt.id] == signature) return
        signatures[prompt.id] = signature
        val notificationId = prompt.id.hashCode() and 0x7FFFFFFF
        shown[prompt.id] = notificationId
        val name = prompt.app?.displayName
            ?: prompt.app?.name
            ?: com.networkcontrol.android.kotlin.ui.prompt.promptDisplayName(prompt.executablePath)
            ?: context.getString(R.string.unknown_app)
        val targets = prompt.flows.mapNotNull {
            it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}"
        }
        val hideDetails = preferences.promptHideDetailsOnLockScreen &&
            context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        val detail = if (hideDetails || !preferences.promptShowDetails) {
            ""
        } else if (targets.isEmpty()) {
            ""
        } else {
            targets.distinct().take(3).joinToString("、")
        }
        val count = prompt.flows.size
        val content = buildString {
            if (hideDetails || !preferences.promptShowDetails) {
                append(context.getString(R.string.prompt_blocked_hint))
                return@buildString
            }
            if (detail.isNotEmpty()) append(detail)
            if (count > 1) {
                if (isNotEmpty()) append(" · ")
                append("共 $count 個連線")
            }
            if (isNotEmpty() && preferences.promptShowExplanation) append(" · ")
            if (preferences.promptShowExplanation) append(context.getString(R.string.prompt_blocked_hint))
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (soft) NotificationChannels.PROMPT_SOFT else NotificationChannels.PROMPT
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.prompt_title, name))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (soft) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter((prompt.deadline - System.currentTimeMillis()).coerceAtLeast(1_000))
            .addAction(0, context.getString(R.string.prompt_deny), actionIntent(prompt, false))
            .addAction(0, context.getString(R.string.prompt_allow), actionIntent(prompt, true))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    fun cancel(promptId: String) {
        signatures.remove(promptId)
        val id = shown.remove(promptId) ?: (promptId.hashCode() and 0x7FFFFFFF)
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    fun cancelAll() {
        shown.values.forEach {
            runCatching { NotificationManagerCompat.from(context).cancel(it) }
        }
        shown.clear()
        signatures.clear()
    }

    private fun actionIntent(prompt: PromptSnapshot, allow: Boolean): PendingIntent {
        val appPath = prompt.app?.path
            ?: prompt.executablePath?.takeIf { it.startsWith("android-app://") }
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ANSWER
            putExtra(NotificationActionReceiver.EXTRA_PROMPT_ID, prompt.id)
            putExtra(NotificationActionReceiver.EXTRA_ALLOW, allow)
            putExtra(NotificationActionReceiver.EXTRA_APP_PATH, appPath)
        }
        return PendingIntent.getBroadcast(
            context,
            prompt.id.hashCode() * 31 + if (allow) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

package com.networkcontrol.android.kotlin.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.PromptSnapshot
import com.networkcontrol.android.kotlin.core.prompts.PromptBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PromptNotifier(
    private val context: Context,
    private val broker: PromptBroker,
    private val scope: CoroutineScope,
    private val preferences: com.networkcontrol.android.kotlin.AppPreferences,
) {
    private var job: Job? = null
    private val shown = mutableMapOf<String, Int>()

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            broker.pending.collectLatest { prompts ->
                if (!preferences.notificationsEnabled) {
                    shown.values.forEach { runCatching { NotificationManagerCompat.from(context).cancel(it) } }
                    shown.clear()
                    return@collectLatest
                }
                prompts.forEach { show(it) }
                val activeIds = prompts.map { it.id }.toSet()
                val iterator = shown.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key !in activeIds) {
                        runCatching { NotificationManagerCompat.from(context).cancel(entry.value) }
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun show(prompt: PromptSnapshot) {
        val notificationId = prompt.id.hashCode() and 0x7FFFFFFF
        shown[prompt.id] = notificationId
        val name = prompt.app?.displayName ?: prompt.app?.name ?: prompt.executablePath ?: "未知應用"
        val targets = prompt.flows.mapNotNull { it.domains.firstOrNull() ?: "${it.remote.address.hostAddress}:${it.remote.port}" }
        val detail = if (targets.isEmpty()) "" else targets.distinct().take(3).joinToString("、")
        val count = prompt.flows.size
        val content = buildString {
            if (detail.isNotEmpty()) append(detail)
            if (count > 1) {
                if (isNotEmpty()) append(" · ")
                append("共 $count 個連線")
            }
            if (isNotEmpty()) append(" · ")
            append(context.getString(R.string.prompt_blocked_hint))
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.PROMPT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.prompt_title, name))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                0,
                context.getString(R.string.prompt_deny),
                actionIntent(prompt.id, false),
            )
            .addAction(
                0,
                context.getString(R.string.prompt_allow),
                actionIntent(prompt.id, true),
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    private fun actionIntent(promptId: String, allow: Boolean): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ANSWER
            putExtra(NotificationActionReceiver.EXTRA_PROMPT_ID, promptId)
            putExtra(NotificationActionReceiver.EXTRA_ALLOW, allow)
        }
        return PendingIntent.getBroadcast(
            context,
            promptId.hashCode() * 31 + if (allow) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun refreshPending() {
        // StateFlow 會自動推送；保留接口供語言切換時手動刷新。
        broker.getPending().forEach { show(it) }
    }
}

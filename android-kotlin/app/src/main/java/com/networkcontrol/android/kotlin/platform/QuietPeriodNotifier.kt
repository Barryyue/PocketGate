package com.networkcontrol.android.kotlin.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.prompts.QuietPeriodTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QuietPeriodNotifier(
    private val context: Context,
    private val tracker: QuietPeriodTracker,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            tracker.summary.collectLatest { summary ->
                if (summary == null || summary.blockedCount <= 0) {
                    runCatching { NotificationManagerCompat.from(context).cancel(2001) }
                    return@collectLatest
                }
                val intent = PendingIntent.getActivity(
                    context,
                    20,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val text = context.getString(
                    R.string.quiet_notification_text,
                    summary.blockedCount,
                    summary.topApps.firstOrNull()?.first.orEmpty(),
                )
                val notification = NotificationCompat.Builder(context, NotificationChannels.QUIET)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(context.getString(R.string.quiet_notification_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
                runCatching { NotificationManagerCompat.from(context).notify(2001, notification) }
            }
        }
    }
}

package com.networkcontrol.android.kotlin.platform.focus

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R
import com.networkcontrol.android.kotlin.core.model.FocusSession
import com.networkcontrol.android.kotlin.core.storage.ActivityStore
import com.networkcontrol.android.kotlin.platform.AppLog
import com.networkcontrol.android.kotlin.platform.NotificationChannels
import com.networkcontrol.android.kotlin.ui.S
import com.networkcontrol.android.kotlin.ui.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 透過系統 AlarmManager（setAndAllowWhileIdle）在 Doze 省電模式下也能準時喚醒專注排程。
 */
class FocusAlarmScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtMillis: Long?) {
        val manager = alarmManager ?: return
        val pending = pendingIntent()
        if (triggerAtMillis == null) {
            manager.cancel(pending)
            return
        }
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }.onFailure { err ->
            AppLog.w("focus", "alarm schedule failed: ${err.message}")
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(appContext, FocusAlarmReceiver::class.java).apply {
            action = ACTION_FOCUS_TICK
        }
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FOCUS_TICK = "com.networkcontrol.android.kotlin.action.FOCUS_TICK"
        private const val REQUEST_CODE = 4201
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        app.container.scope.launch(Dispatchers.Default) {
            try {
                app.container.awaitStarted()
                app.container.focusScheduler.tick()
                app.container.focusAlarmScheduler.schedule(app.container.focusScheduler.nextTriggerMillis())
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 專注模式結束後發送可劃除的攔截小結通知（持續時間 + 專注期間攔截次數）。
 */
class FocusSummaryNotifier(
    private val context: Context,
    private val activityStore: ActivityStore,
    private val scope: CoroutineScope,
    private val notificationsEnabled: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun notifyEnded(session: FocusSession, durationMinutes: Int) {
        if (!notificationsEnabled()) return
        scope.launch(Dispatchers.IO) {
            val blockedCount = runCatching {
                activityStore.countBlockedSince(session.startedAt).toInt()
            }.getOrDefault(0)
            NotificationChannels.ensure(appContext)
            val s = UiStrings.forContext(appContext)
            val openApp = PendingIntent.getActivity(
                appContext,
                SUMMARY_NOTIFICATION_ID,
                Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(appContext, NotificationChannels.QUIET)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(s[S.FocusSummaryTitle])
                .setContentText(s.format(S.FocusSummaryBody, durationMinutes, blockedCount))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            runCatching { manager?.notify(SUMMARY_NOTIFICATION_ID, notification) }
        }
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 4202
    }
}

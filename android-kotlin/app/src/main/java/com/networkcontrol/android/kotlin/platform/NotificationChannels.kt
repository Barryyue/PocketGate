package com.networkcontrol.android.kotlin.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.networkcontrol.android.kotlin.R

object NotificationChannels {
    const val PROMPT = "prompts"
    const val PROMPT_SOFT = "prompts_soft"
    const val PROTECTION = "protection"
    const val QUIET = "quiet"
    const val NEW_APPS = "new_apps"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PROMPT,
                context.getString(R.string.notification_channel_prompt),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_prompt)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROMPT_SOFT,
                context.getString(R.string.notification_channel_prompt_soft),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_prompt_soft)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PROTECTION,
                context.getString(R.string.notification_channel_protection),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                QUIET,
                context.getString(R.string.notification_channel_quiet),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NEW_APPS,
                context.getString(R.string.notification_channel_new_apps),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

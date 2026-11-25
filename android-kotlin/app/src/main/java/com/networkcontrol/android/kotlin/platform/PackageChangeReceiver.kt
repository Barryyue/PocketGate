package com.networkcontrol.android.kotlin.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.networkcontrol.android.kotlin.App
import com.networkcontrol.android.kotlin.MainActivity
import com.networkcontrol.android.kotlin.R

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        app.container.appCatalog.invalidate()
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName) return
        if (!app.container.preferences.notificationsEnabled) return
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appPath = com.networkcontrol.android.kotlin.engine.apps.AppCatalog.appPath(packageName)
        val allowIntent = PendingIntent.getBroadcast(
            context,
            (packageName.hashCode() * 31 + 1) and 0x7FFFFFFF,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ANSWER
                putExtra(NotificationActionReceiver.EXTRA_PROMPT_ID, "new-app:$packageName")
                putExtra(NotificationActionReceiver.EXTRA_APP_PATH, appPath)
                putExtra(NotificationActionReceiver.EXTRA_ALLOW, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            (packageName.hashCode() * 31 + 2) and 0x7FFFFFFF,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ANSWER
                putExtra(NotificationActionReceiver.EXTRA_PROMPT_ID, "new-app:$packageName")
                putExtra(NotificationActionReceiver.EXTRA_APP_PATH, appPath)
                putExtra(NotificationActionReceiver.EXTRA_ALLOW, false)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val strings = com.networkcontrol.android.kotlin.ui.UiStrings.forContext(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.NEW_APPS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.new_app_title, label))
            .setContentText(context.getString(R.string.new_app_text))
            .setContentIntent(contentIntent)
            .addAction(0, strings[com.networkcontrol.android.kotlin.ui.S.Allow], allowIntent)
            .addAction(0, strings[com.networkcontrol.android.kotlin.ui.S.Deny], denyIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                packageName.hashCode() and 0x7FFFFFFF,
                notification,
            )
        }
    }
}

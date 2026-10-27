package com.jarvis.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.agent.R
import com.jarvis.agent.service.JarvisService
import com.jarvis.agent.service.KillSwitchReceiver
import com.jarvis.agent.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "jarvis_service"
    const val CHANNEL_ALERTS = "jarvis_alerts"
    const val ID_SERVICE = 1001
    const val ID_BOOT = 1002
    const val ID_CONFIRM = 1003
    const val ID_HANDOFF = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun killSwitchIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 1,
            Intent(context, KillSwitchReceiver::class.java).setAction(KillSwitchReceiver.ACTION_KILL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun serviceNotification(context: Context, text: String): Notification {
        val talk = PendingIntent.getService(
            context, 3,
            Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_PUSH_TO_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .addAction(R.drawable.ic_mic, context.getString(R.string.notif_talk), talk)
            .addAction(R.drawable.ic_stop, context.getString(R.string.notif_stop), killSwitchIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun simpleAlert(context: Context, id: Int, title: String, text: String, contentIntent: PendingIntent? = null) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent ?: openAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }
}

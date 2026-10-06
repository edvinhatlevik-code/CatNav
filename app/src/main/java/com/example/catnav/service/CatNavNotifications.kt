package com.example.catnav.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.catnav.MainActivity

object CatNavNotifications {
    const val SERVICE_NOTIFICATION_ID = 100
    private const val MONITOR_CHANNEL = "catnav_monitor"
    private const val ALERT_CHANNEL = "catnav_alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL,
                "Gateway monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while CatNav listens for tracker alerts in the background."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Tracker alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Low-battery and tracker wake notifications."
            }
        )
    }

    fun monitor(context: Context): Notification =
        NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CatNav is monitoring")
            .setContentText("Listening for tracker battery alerts.")
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    fun chargeRequest(context: Context, trackerId: Long, millivolts: Int?, criticalMillivolts: Int?): Boolean {
        val voltage = millivolts?.let { " Battery: ${it} mV." }.orEmpty()
        val threshold = criticalMillivolts?.let { " Critical: ${it} mV." }.orEmpty()
        return notify(
            context,
            notificationId(trackerId, CHARGE_NOTIFICATION_BASE),
            "Tracker needs charging",
            "Tracker ${trackerId.toString(16).uppercase()} requested charging.$voltage$threshold"
        )
    }

    fun trackerAwake(context: Context, trackerId: Long): Boolean =
        notify(
            context,
            notificationId(trackerId, WAKE_NOTIFICATION_BASE),
            "Tracker is awake",
            "Tracker ${trackerId.toString(16).uppercase()} confirmed WAKE."
        )

    private fun notify(context: Context, id: Int, title: String, text: String): Boolean {
        createChannels(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        return true
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(trackerId: Long, base: Int): Int =
        base + (trackerId xor (trackerId ushr 32)).toInt().and(0x3FFF)

    private const val CHARGE_NOTIFICATION_BASE = 2_000
    private const val WAKE_NOTIFICATION_BASE = 20_000
}

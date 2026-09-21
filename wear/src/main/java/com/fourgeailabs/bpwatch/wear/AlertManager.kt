package com.fourgeailabs.bpwatch.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fourgeailabs.bpwatch.R

/**
 * Threshold alerts on the watch. Each alert type (high HR, high BP, low BP)
 * has a 15-minute cooldown so a persisting condition doesn't buzz on every
 * reading.
 *
 * An alert is a high-priority notification with a vibration pattern plus a
 * full-screen [AlertActivity] showing the offending value.
 */
object AlertManager {
    private const val CHANNEL_ID = "bpwatch_alerts"
    private const val COOLDOWN_MS = 15 * 60_000L
    private const val NOTIF_ID_HR = 3001
    private const val NOTIF_ID_BP = 3002

    fun checkHeartRate(context: Context, hr: Float) {
        val config = WatchSettings.getMonitorConfig(context)
        if (!config.hrHighEnabled) return
        if (hr <= config.hrHighThreshold) return
        if (!cooldownElapsed(context, WatchSettings.ALERT_HR_HIGH)) return
        WatchSettings.setLastAlert(
            context,
            WatchSettings.ALERT_HR_HIGH,
            System.currentTimeMillis(),
        )
        fire(
            context = context,
            notificationId = NOTIF_ID_HR,
            title = "High heart rate",
            message = "${hr.toInt()} bpm — over your ${config.hrHighThreshold} bpm alert limit.",
        )
    }

    fun checkBloodPressure(context: Context, sys: Int, dia: Int) {
        val config = WatchSettings.getMonitorConfig(context)
        if (config.bpHighEnabled &&
            (sys >= config.sysHigh || dia >= config.diaHigh)
        ) {
            if (!cooldownElapsed(context, WatchSettings.ALERT_BP_HIGH)) return
            WatchSettings.setLastAlert(
                context,
                WatchSettings.ALERT_BP_HIGH,
                System.currentTimeMillis(),
            )
            fire(
                context = context,
                notificationId = NOTIF_ID_BP,
                title = "High blood pressure",
                message = "$sys/$dia mmHg — at or over your " +
                    "${config.sysHigh}/${config.diaHigh} mmHg alert limit.",
            )
        } else if (config.bpLowEnabled &&
            (sys <= config.sysLow || dia <= config.diaLow)
        ) {
            if (!cooldownElapsed(context, WatchSettings.ALERT_BP_LOW)) return
            WatchSettings.setLastAlert(
                context,
                WatchSettings.ALERT_BP_LOW,
                System.currentTimeMillis(),
            )
            fire(
                context = context,
                notificationId = NOTIF_ID_BP,
                title = "Low blood pressure",
                message = "$sys/$dia mmHg — at or under your " +
                    "${config.sysLow}/${config.diaLow} mmHg alert limit.",
            )
        }
    }

    private fun cooldownElapsed(context: Context, alertType: String): Boolean {
        val last = WatchSettings.getLastAlert(context, alertType)
        return System.currentTimeMillis() - last >= COOLDOWN_MS
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Health alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Buzzes when your heart rate or blood pressure crosses your alert limits."
                enableVibration(true)
            }
        )
    }

    private fun fire(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
    ) {
        ensureChannel(context)

        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            putExtra(AlertActivity.EXTRA_TITLE, title)
            putExtra(AlertActivity.EXTRA_MESSAGE, message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            notificationId,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(
            context,
            notificationId + 1000,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 800))
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip silently.
        }
    }
}

package com.fourgeailabs.bpwatch.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fourgeailabs.bpwatch.Link
import com.fourgeailabs.bpwatch.mobile.MainActivity
import com.fourgeailabs.bpwatch.mobile.ui.PhoneAlertActivity
import com.fourgeailabs.bpwatch.mobile.wearable.WatchAlert
import com.fourgeailabs.bpwatch.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Posts a notification when a new BP estimate lands from the watch, with
 * every health detail available at scan time: BP estimate, heart rate,
 * stress score, SpO2 (from Health Connect / Samsung Health), and time.
 * Tapping it opens the app for review.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "bpwatch_results"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reading results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies you when a new blood-pressure estimate is ready for review."
            }
        )
    }

    data class ResultDetails(
        val sys: Int,
        val dia: Int,
        val heartRate: Float,
        val stress: Int?, // 0-100, null when unknown
        val spo2: Int?, // %, null when unknown
        val timestamp: Long,
    )

    fun notifyEstimateReady(context: Context, details: ResultDetails) {
        ensureChannel(context)

        val time = DateTimeFormatter.ofPattern("h:mm a")
            .format(Instant.ofEpochMilli(details.timestamp).atZone(ZoneId.systemDefault()))

        val bits = mutableListOf<String>()
        bits.add("${details.heartRate.toInt()} bpm")
        details.stress?.let { bits.add("stress $it/100") }
        details.spo2?.let { bits.add("SpO2 $it%") }
        bits.add(time)
        val line2 = bits.joinToString(" · ")

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("BP estimate ready: ${details.sys}/${details.dia} mmHg")
            .setContentText(line2)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line2))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip silently.
        }
    }

    // ------------------------------------------------------------------
    // Watch alert mirroring
    // ------------------------------------------------------------------

    private const val ALERT_CHANNEL_ID = "bpwatch_watch_alerts"
    private const val ALERT_NOTIFICATION_ID = 1002

    private fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Watch health alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Mirrors the health alerts that fire on your watch."
                enableVibration(true)
            }
        )
    }

    /**
     * Mirrors an alert that fired on the watch. Normal alerts arrive as a
     * heads-up notification; extreme readings take over the phone screen
     * with [PhoneAlertActivity], which must be swiped away.
     */
    fun notifyWatchAlert(context: Context, alert: WatchAlert) {
        ensureAlertChannel(context)
        val extreme = alert.severity == Link.Severity.EXTREME

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 800))
            .setContentIntent(open)
            .setAutoCancel(true)

        if (extreme) {
            // Full-screen takeover: launch the alert activity directly for a
            // guaranteed takeover, and attach it as the full-screen intent so
            // it also fires over the lock screen / from the notification.
            val alertIntent = Intent(context, PhoneAlertActivity::class.java).apply {
                putExtra(PhoneAlertActivity.EXTRA_TITLE, alert.title)
                putExtra(PhoneAlertActivity.EXTRA_MESSAGE, alert.message)
                putExtra(PhoneAlertActivity.EXTRA_TYPE, alert.type)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                context.startActivity(alertIntent)
            } catch (_: Exception) {
            }
            val fullScreen = PendingIntent.getActivity(
                context, 1, alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreen, true)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(ALERT_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — skip silently.
        }
    }
}

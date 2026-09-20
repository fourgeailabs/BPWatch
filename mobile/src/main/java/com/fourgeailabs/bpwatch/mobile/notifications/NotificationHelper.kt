package com.fourgeailabs.bpwatch.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fourgeailabs.bpwatch.mobile.MainActivity
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
}

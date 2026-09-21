package com.fourgeailabs.bpwatch.mobile.snore

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules the fixed overnight snore-detection window: 22:00 start, 07:00
 * stop, local time. Uses daily inexact repeating alarms anchored to the next
 * local 22:00/07:00 — re-anchored every time they are (re)scheduled rather
 * than a fixed 24 h repeat, so daylight-saving transitions stay correct.
 * [ensureScheduled] is idempotent and cheap: safe to call on every app start.
 */
object SnoreScheduler {
    const val ACTION_START = "com.fourgeailabs.bpwatch.mobile.SNORE_START"
    const val ACTION_STOP = "com.fourgeailabs.bpwatch.mobile.SNORE_STOP"

    const val WINDOW_START_HOUR = 22
    const val WINDOW_END_HOUR = 7
    const val WINDOW_HOURS = 9

    private const val REQ_START = 5101
    private const val REQ_STOP = 5102

    /** True when the current local time is inside the 22:00–07:00 window. */
    fun inWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).hour
        return hour >= WINDOW_START_HOUR || hour < WINDOW_END_HOUR
    }

    /**
     * The most recently completed (or in-progress) 22:00–07:00 window as a
     * (start, end) pair of epoch millis. If it is currently 02:00, that is
     * tonight's 22:00–07:00; if it is 09:00, last night's. Sessions are
     * attributed to the evening they start on.
     */
    fun lastNightWindow(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val evening = if (now.hour >= WINDOW_START_HOUR) now.toLocalDate() else now.toLocalDate().minusDays(1)
        val start = evening.atTime(WINDOW_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return start to start + WINDOW_HOURS * 3_600_000L
    }

    /** (Re)arms the 22:00 start and 07:00 stop alarms. Idempotent. */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startPi = alarmIntent(context, ACTION_START, REQ_START)
        val stopPi = alarmIntent(context, ACTION_STOP, REQ_STOP)
        am.cancel(startPi)
        am.cancel(stopPi)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var nextStart = now.withHour(WINDOW_START_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!nextStart.isAfter(now)) nextStart = nextStart.plusDays(1)
        var nextStop = now.withHour(WINDOW_END_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!nextStop.isAfter(now)) nextStop = nextStop.plusDays(1)
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextStart.toInstant().toEpochMilli(),
            AlarmManager.INTERVAL_DAY,
            startPi,
        )
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextStop.toInstant().toEpochMilli(),
            AlarmManager.INTERVAL_DAY,
            stopPi,
        )
    }

    /** Cancels both alarms. */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context, ACTION_START, REQ_START))
        am.cancel(alarmIntent(context, ACTION_STOP, REQ_STOP))
    }

    /**
     * Re-arms the alarms on app start and after boot: the OS clears
     * AlarmManager alarms on reboot, and they should always exist whenever
     * the feature is enabled (they simply no-op if the user turned it off,
     * because the receiver checks the persisted toggle before starting the
     * service).
     */
    fun ensureScheduled(context: Context) {
        try {
            schedule(context)
        } catch (_: Exception) {
            // Never throw out of app start; a missing alarm just means the
            // feature doesn't wake up on its own until the user opens the app.
        }
    }

    private fun alarmIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SnoreAlarmReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}

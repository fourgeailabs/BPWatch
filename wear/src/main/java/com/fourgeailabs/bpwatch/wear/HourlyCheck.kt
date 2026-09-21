package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Scheduled background BP checks. The phone's "Monitoring & alerts" settings
 * choose the interval; this generalises the old hourly-only alarm.
 *
 * Inexact alarms are deliberately used — they batch with Doze maintenance
 * windows, so frequent wellness checks don't chew the battery.
 *
 * intervalMinutes: 10 / 15 / 30 / 45 / 60 / 360 (every 6 hours) /
 * 1440 (once a day, at 08:00 local) / 0 = on demand (alarms cancelled).
 */
object CheckScheduler {
    private const val REQUEST_CODE = 4101

    /**
     * Kept stable across the v1.13 rename (was HourlyCheck.ACTION_CHECK) so
     * the manifest-registered receiver keeps working without changes.
     */
    const val ACTION_CHECK = "com.fourgeailabs.bpwatch.wear.HOURLY_CHECK"

    fun schedule(context: Context, intervalMinutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = operation(context)
        am.cancel(pi)
        if (intervalMinutes <= 0) return

        val now = System.currentTimeMillis()
        val (first, interval) = when (intervalMinutes) {
            1440 -> nextMorning8am(now) to AlarmManager.INTERVAL_DAY
            10 -> {
                val ten = 10 * 60_000L
                (now + ten) to ten
            }
            15 -> (now + AlarmManager.INTERVAL_FIFTEEN_MINUTES) to
                AlarmManager.INTERVAL_FIFTEEN_MINUTES
            30 -> (now + AlarmManager.INTERVAL_HALF_HOUR) to
                AlarmManager.INTERVAL_HALF_HOUR
            45 -> {
                val fortyFive = 45 * 60_000L
                (now + fortyFive) to fortyFive
            }
            360 -> {
                val sixHours = 6 * 60 * 60_000L
                (now + sixHours) to sixHours
            }
            else -> (now + AlarmManager.INTERVAL_HOUR) to AlarmManager.INTERVAL_HOUR
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, interval, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
    }

    /** (Re)applies whatever interval the current config asks for. */
    fun reschedule(context: Context) {
        schedule(context, WatchSettings.getMonitorConfig(context).bpIntervalMinutes)
    }

    /**
     * Alarms don't survive app updates or reboots, so re-arm when missing —
     * and clear stale ones if the config says on-demand.
     */
    fun ensureScheduled(context: Context) {
        val interval = WatchSettings.getMonitorConfig(context).bpIntervalMinutes
        val scheduled = isScheduled(context)
        if (interval > 0 && !scheduled) {
            schedule(context, interval)
        } else if (interval <= 0 && scheduled) {
            cancel(context)
        }
    }

    private fun nextMorning8am(now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun isScheduled(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_CHECK).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

    private fun operation(context: Context): PendingIntent {
        val intent = Intent(ACTION_CHECK).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Fires on each scheduled check: takes a 30-second heart-rate sample in the
 * background and sends it to the phone. The phone computes the BP estimate
 * and sends it back; the watch persists and displays it.
 */
class HourlyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CheckScheduler.ACTION_CHECK) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BODY_SENSORS,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val result = HrMeasurement.measure(context)
                        if (result != null) {
                            // Heart-rate alert applies to sampled checks too.
                            AlertManager.checkHeartRate(
                                context.applicationContext,
                                result.averageHr,
                            )
                            val restingHr = WatchSettings.getRestingHr(context)
                            val stress = StressEstimator.estimate(result.samples, restingHr)
                            DataLayer.sendHrReading(
                                context.applicationContext,
                                result.averageHr,
                                System.currentTimeMillis(),
                                stress,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Background check must never crash.
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Re-arm the check alarm after a reboot, per the persisted config.
 *
 * Note: the continuous-HR foreground service can't be started from here on
 * Android 12+ (background foreground-service starts are blocked) — it
 * resumes when the app UI next opens (MainActivity calls
 * HrMonitorService.ensureRunning) and survives kills via START_STICKY.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CheckScheduler.ensureScheduled(context)
            RecordScheduler.ensureScheduled(context)
            // Re-announce to the phone after a reboot: it re-sends the
            // monitoring config + calibration state, so a wiped watch
            // re-programs itself without anyone touching a thing.
            DataLayer.announceToPhone(context)
        }
    }
}

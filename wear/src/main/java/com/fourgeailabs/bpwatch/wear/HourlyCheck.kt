package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Scheduled background BP checks. The phone's "Monitoring & alerts" settings
 * choose the interval; this generalises the old hourly-only alarm.
 *
 * Delivery (v2.3): chained exact alarms via setExactAndAllowWhileIdle.
 * setInexactRepeating was batching into Doze maintenance windows, so a
 * 10-minute interval could silently never fire (reported: 20+ min with no
 * reading). Each fire re-arms the next single alarm at the end of the
 * receiver, so the chain survives without a repeating alarm.
 *
 * Honest caveat: in *deep* Doze the system still throttles exact idle
 * alarms to roughly one per 15 minutes, so a 10-minute cadence is
 * best-effort under Doze, not guaranteed. A foreground service was
 * considered instead and rejected: between ticks there is no sampling to
 * do, so it would buy nothing over alarms while holding a persistent
 * notification and steady battery drain.
 *
 * intervalMinutes: 10 / 15 / 30 / 45 / 60 / 360 (every 6 hours) /
 * 1440 (once a day, at 08:00 local) / 0 = on demand (alarms cancelled).
 */
object CheckScheduler {
    private const val TAG = "CheckScheduler"
    private const val REQUEST_CODE = 4101

    /**
     * Kept stable across the v1.13 rename (was HourlyCheck.ACTION_CHECK) so
     * the manifest-registered receiver keeps working without changes.
     */
    const val ACTION_CHECK = "com.fourgeailabs.bpwatch.wear.HOURLY_CHECK"

    fun schedule(context: Context, intervalMinutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        if (intervalMinutes <= 0) return
        scheduleNextAt(context, nextTrigger(intervalMinutes, System.currentTimeMillis()))
        Log.i(TAG, "BP checks scheduled every $intervalMinutes min")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        Log.i(TAG, "BP checks cancelled")
    }

    /** (Re)applies whatever interval the current config asks for. */
    fun reschedule(context: Context) {
        schedule(context, WatchSettings.getMonitorConfig(context).bpIntervalMinutes)
    }

    /**
     * Re-arms the next single alarm in the chain. The receiver MUST call
     * this on every fire (even when the check was skipped) or the chain
     * dies with the alarm that just fired.
     */
    fun chainNext(context: Context) {
        val interval = try {
            WatchSettings.getMonitorConfig(context).bpIntervalMinutes
        } catch (_: Exception) {
            0
        }
        if (interval <= 0) return
        scheduleNextAt(context, nextTrigger(interval, System.currentTimeMillis()))
    }

    private fun nextTrigger(intervalMinutes: Int, now: Long): Long =
        if (intervalMinutes == 1440) nextMorning8am(now)
        else now + intervalMinutes * 60_000L

    /**
     * Arms one exact idle alarm. Falls back to an inexact single alarm when
     * the exact-alarm permission is missing or was revoked mid-flight
     * (SCHEDULE_EXACT_ALARM is granted by default on API 33+, revocable in
     * Settings → Alarms & reminders).
     */
    private fun scheduleNextAt(context: Context, triggerAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = operation(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                am.canScheduleExactAlarms()
            ) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                Log.w(TAG, "Exact alarms not permitted — inexact fallback")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied; inexact fallback", e)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } catch (_: Exception) {
            }
        }
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
 *
 * Chained alarms (v2.3): the next alarm is re-armed at the end of every
 * fire — including skipped ones — or the chain dies.
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
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.i(TAG, "BP check skipped — body-sensors permission missing")
                        CheckScheduler.chainNext(context)
                        return@runBlocking
                    }
                    val result = HrMeasurement.measure(context)
                    if (result == null || result.offBody) {
                        // Off-wrist: pause — no zeros, no garbage, no alerts.
                        val streak = OffBodyDetector.noteEmptyAttempt(context)
                        Log.i(
                            TAG,
                            "BP check skipped — watch appears off-wrist " +
                                "(empty attempt $streak of " +
                                "${OffBodyDetector.EMPTY_ATTEMPTS_THRESHOLD})",
                        )
                        CheckScheduler.chainNext(context)
                        return@runBlocking
                    }
                    OffBodyDetector.noteValidSignal(context)
                    // Heart-rate alert applies to sampled checks too.
                    AlertManager.checkHeartRate(
                        context.applicationContext,
                        result.averageHr,
                    )
                    val restingHr = WatchSettings.getRestingHr(context)
                    val stress = StressEstimator.estimate(result.samples, restingHr)
                    // (K) Persist the reading so the home screen shows the
                    // latest HR on launch, like every other measurement path.
                    try {
                        val measuredAt = System.currentTimeMillis()
                        WatchSettings.saveLatestHr(context, result.averageHr, measuredAt)
                        WatchSettings.saveLatestStress(context, stress)
                        WatchState.onLatestHr(result.averageHr, measuredAt)
                    } catch (_: Exception) {
                    }
                    DataLayer.sendHrReading(
                        context.applicationContext,
                        result.averageHr,
                        System.currentTimeMillis(),
                        stress,
                    )
                    CheckScheduler.chainNext(context)
                }
            } catch (_: Exception) {
                // Background check must never crash — but keep the chain alive.
                try {
                    CheckScheduler.chainNext(context)
                } catch (_: Exception) {
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "HourlyCheckReceiver"
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

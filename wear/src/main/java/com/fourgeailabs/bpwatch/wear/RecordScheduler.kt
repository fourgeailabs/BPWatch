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

/**
 * Continuous HR + stress recording (v2.0, opt-in). When the phone enables
 * it (PATH_HR_RECORD_SET), this fires every 10 minutes: a 30-second HR
 * sample (same code as the scheduled BP checks), a stress estimate from the
 * same samples, both persisted to [SampleStore] and batched to the phone.
 *
 * Delivery (v2.3): chained exact alarms via setExactAndAllowWhileIdle — the
 * old setInexactRepeating never reliably delivered a 10-minute cadence
 * under Doze. Each fire re-arms the next single alarm. Honest caveat: deep
 * Doze still throttles exact idle alarms to roughly one per 15 minutes, so
 * the 10-minute cadence is best-effort under Doze. (A foreground service
 * was considered and rejected: between ticks there is no sampling to do,
 * so it would buy nothing for steady battery cost.)
 *
 * The toggle defaults OFF and says so in the UI, because waking the HR
 * sensor 144 times a day is a real battery trade-off.
 */
object RecordScheduler {
    private const val TAG = "RecordScheduler"
    private const val REQUEST_CODE = 4102

    /**
     * Kept as a stable string (not derived from the class name) so the
     * manifest-registered receiver keeps working across refactors.
     */
    const val ACTION_RECORD = "com.fourgeailabs.bpwatch.wear.RECORD_SAMPLE"

    /** Fixed 10-minute cadence — the sane default for history graphs. */
    private const val INTERVAL_MS = 10 * 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        scheduleNextAt(context, System.currentTimeMillis() + INTERVAL_MS)
        Log.i(TAG, "Recording scheduled every 10 min")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        Log.i(TAG, "Recording cancelled")
    }

    /**
     * Re-arms the next single alarm in the chain. The receiver MUST call
     * this on every fire (even when the tick was skipped) or the chain
     * dies.
     */
    fun chainNext(context: Context) {
        scheduleNextAt(context, System.currentTimeMillis() + INTERVAL_MS)
    }

    /**
     * Arms one exact idle alarm, with an inexact fallback when the
     * exact-alarm permission is missing or revoked (see CheckScheduler).
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
     * Alarms don't survive reboots or app updates, so re-arm when missing —
     * and clear a stale alarm if recording was turned off while the alarm
     * somehow survived.
     */
    fun ensureScheduled(context: Context) {
        val enabled = WatchSettings.isRecordHrEnabled(context)
        val scheduled = isScheduled(context)
        if (enabled && !scheduled) {
            schedule(context)
        } else if (!enabled && scheduled) {
            cancel(context)
        }
    }

    private fun isScheduled(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_RECORD).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

    private fun operation(context: Context): PendingIntent {
        val intent = Intent(ACTION_RECORD).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Fires every 10 minutes while recording is on: takes the same 30-second
 * heart-rate sample the BP checks use, estimates stress from those samples,
 * persists both, and pushes a batch to the phone when enough has piled up.
 *
 * Chained alarms (v2.3): the next alarm is re-armed at the end of every
 * fire — including skipped ones — or the chain dies.
 */
class RecordSampleReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "RecordSampleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecordScheduler.ACTION_RECORD) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    if (!WatchSettings.isRecordHrEnabled(context)) {
                        // Toggled off between scheduling and firing.
                        RecordScheduler.cancel(context)
                        return@runBlocking
                    }
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BODY_SENSORS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.i(TAG, "Recording tick skipped — body-sensors permission missing")
                        RecordScheduler.chainNext(context)
                        return@runBlocking
                    }
                    val result = HrMeasurement.measure(context)
                    if (result == null || result.offBody) {
                        // Off-wrist: pause — no zeros or garbage in history.
                        val streak = OffBodyDetector.noteEmptyAttempt(context)
                        Log.i(
                            TAG,
                            "Recording tick skipped — watch appears off-wrist " +
                                "(empty attempt $streak of " +
                                "${OffBodyDetector.EMPTY_ATTEMPTS_THRESHOLD})",
                        )
                        RecordScheduler.chainNext(context)
                        return@runBlocking
                    }
                    OffBodyDetector.noteValidSignal(context)
                    val restingHr = WatchSettings.getRestingHr(context)
                    val stress = StressEstimator.estimate(result.samples, restingHr)
                    SampleStore.append(
                        context.applicationContext,
                        SampleStore.Sample(
                            timestamp = System.currentTimeMillis(),
                            bpm = result.averageHr,
                            stress = stress,
                        ),
                    )
                    HistorySync.maybeSync(context.applicationContext)
                    // Report today's step count to the phone alongside the
                    // history sync — best effort, never breaks recording.
                    try {
                        StepsReporter.maybeReport(context.applicationContext)
                    } catch (_: Exception) {
                    }
                    RecordScheduler.chainNext(context)
                }
            } catch (_: Exception) {
                // A recording tick must never crash — but keep the chain alive.
                try {
                    RecordScheduler.chainNext(context)
                } catch (_: Exception) {
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

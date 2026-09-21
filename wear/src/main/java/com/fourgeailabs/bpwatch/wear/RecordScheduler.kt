package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking

/**
 * Continuous HR + stress recording (v2.0, opt-in). When the phone enables
 * it (PATH_HR_RECORD_SET), this fires every 10 minutes: a 30-second HR
 * sample (same code as the scheduled BP checks), a stress estimate from the
 * same samples, both persisted to [SampleStore] and batched to the phone.
 *
 * Inexact alarms batch with Doze maintenance windows, like the check
 * scheduler — but the toggle defaults OFF and says so in the UI, because
 * waking the HR sensor 144 times a day is a real battery trade-off.
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
        val pi = operation(context)
        am.cancel(pi)
        val now = System.currentTimeMillis()
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            now + INTERVAL_MS,
            INTERVAL_MS,
            pi,
        )
        Log.i(TAG, "Recording scheduled every 10 min")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        Log.i(TAG, "Recording cancelled")
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
 */
class RecordSampleReceiver : BroadcastReceiver() {
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
                        return@runBlocking
                    }
                    val result = HrMeasurement.measure(context) ?: return@runBlocking
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
                }
            } catch (_: Exception) {
                // A recording tick must never crash.
            } finally {
                pending.finish()
            }
        }.start()
    }
}

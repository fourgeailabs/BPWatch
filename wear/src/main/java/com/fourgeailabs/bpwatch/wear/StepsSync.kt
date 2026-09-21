package com.fourgeailabs.bpwatch.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Proactive step-count sync (v2.3.1). Before this, the watch only pushed
 * its step total to the phone on Bluetooth reconnect or on the 10-minute
 * continuous-recording tick — so with recording off, the phone's step
 * number went stale for hours and lagged the watch (and Samsung Health,
 * which merges phone + watch steps).
 *
 * This chain fires every 15 minutes regardless of the recording toggle and
 * calls [StepsReporter.maybeReport], which is a no-op when the count
 * hasn't changed since the last successful push. Battery cost is tiny: a
 * one-shot step-counter read (a few seconds) plus one Data Layer message
 * only when the count moved.
 *
 * Delivery: chained exact alarms via setExactAndAllowWhileIdle, same as
 * [RecordScheduler] — each fire re-arms the next single alarm. Deep Doze
 * still throttles to roughly one fire per 15 minutes, which is exactly
 * this cadence, so nothing is lost.
 */
object StepsScheduler {
    private const val TAG = "StepsScheduler"
    private const val REQUEST_CODE = 4103

    /**
     * Kept as a stable string (not derived from the class name) so the
     * manifest-registered receiver keeps working across refactors.
     */
    const val ACTION_REPORT_STEPS = "com.fourgeailabs.bpwatch.wear.REPORT_STEPS"

    /** 15-minute cadence — fresh enough for the Home tile, cheap on battery. */
    private const val INTERVAL_MS = 15 * 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        scheduleNextAt(context, System.currentTimeMillis() + INTERVAL_MS)
        Log.i(TAG, "Step reports scheduled every 15 min")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
        Log.i(TAG, "Step reports cancelled")
    }

    /**
     * Re-arms the next single alarm in the chain. The receiver MUST call
     * this on every fire or the chain dies.
     */
    fun chainNext(context: Context) {
        scheduleNextAt(context, System.currentTimeMillis() + INTERVAL_MS)
    }

    /**
     * Arms one exact idle alarm, with an inexact fallback when the
     * exact-alarm permission is missing or revoked.
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
     * Alarms don't survive reboots or app updates, so re-arm when missing.
     * Unlike [RecordScheduler], this chain is always on — no toggle gates
     * it — because a stale step count is a bug, not a preference.
     */
    fun ensureScheduled(context: Context) {
        if (!isScheduled(context)) {
            schedule(context)
        }
    }

    private fun isScheduled(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_REPORT_STEPS).setPackage(context.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

    private fun operation(context: Context): PendingIntent {
        val intent = Intent(ACTION_REPORT_STEPS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Fires every 15 minutes: pushes today's step total to the phone when it
 * changed since the last successful push ([StepsReporter.maybeReport]
 * handles the dedup), then re-arms the chain. Never throws, never breaks
 * the chain.
 */
class StepsReportReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "StepsReportReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StepsScheduler.ACTION_REPORT_STEPS) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    try {
                        StepsReporter.maybeReport(context.applicationContext)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Step report failed", t)
                    }
                    StepsScheduler.chainNext(context)
                }
            } catch (_: Exception) {
                // The tick must never crash — but keep the chain alive.
                try {
                    StepsScheduler.chainNext(context)
                } catch (_: Exception) {
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

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

/**
 * "Check once an hour" mode: an inexact hourly alarm wakes [HourlyCheckReceiver],
 * which takes a 30-second heart-rate sample in the background and sends it to
 * the phone. The phone computes the BP estimate and sends it back; the watch
 * persists and displays it.
 *
 * Inexact alarms are deliberately used — they batch with Doze maintenance
 * windows, so an hourly wellness check doesn't chew the battery.
 */
object HourlyCheck {
    private const val REQUEST_CODE = 4101
    const val ACTION_CHECK = "com.fourgeailabs.bpwatch.wear.HOURLY_CHECK"

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = operation(context)
        am.cancel(pi)
        val first = System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_HOUR, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(operation(context))
    }

    /** Alarms don't survive app updates, so re-schedule if missing. */
    fun ensureScheduled(context: Context) {
        if (WatchSettings.getCheckMode(context) == WatchSettings.CheckMode.HOURLY &&
            !isScheduled(context)
        ) {
            schedule(context)
        }
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

class HourlyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HourlyCheck.ACTION_CHECK) return
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

/** Re-arm the hourly alarm after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            HourlyCheck.ensureScheduled(context)
        }
    }
}

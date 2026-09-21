package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reports the watch's daily step total to the phone
 * ([Link.PATH_STEPS_DAILY]). The phone falls back to Health Connect steps
 * whenever the watch can't report (no permission, no step sensor, ...).
 *
 * Health Services was evaluated first: health-services-client 1.0.0 (the
 * version the build resolves) has NO DataClient / one-shot read API — only
 * Exercise, Passive-monitoring and Measure clients — so this uses the
 * platform TYPE_STEP_COUNTER sensor with a SharedPreferences midnight
 * baseline instead. Reboots are handled (TYPE_STEP_COUNTER resets to 0 on
 * reboot; steps counted before the reboot are carried over).
 *
 * Call sites: [WatchListenerService.onPeerConnected] and the
 * [RecordSampleReceiver] 10-minute tick, right after the history sync.
 * Nothing here ever throws — a failure just means the phone uses its own
 * Health Connect steps.
 */
object StepsReporter {
    private const val TAG = "StepsReporter"

    private const val PREFS = "bpwatch_steps"
    private const val K_LAST_DATE = "last_date"
    private const val K_LAST_STEPS = "last_steps"
    private const val K_BASELINE_DATE = "baseline_date"
    private const val K_BASELINE_STEPS = "baseline_steps"
    private const val K_CARRIED_STEPS = "carried_steps"
    private const val K_LAST_CUMULATIVE = "last_cumulative"

    /** How long to wait for the step-counter sensor's first event. */
    private const val SENSOR_TIMEOUT_MS = 10_000L

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Reads the watch's step total for today (system zone). Returns null on
     * ANY failure — missing ACTIVITY_RECOGNITION grant, no step-counter
     * sensor, sensor timeout — so the phone can fall back to Health Connect.
     */
    suspend fun readTodaySteps(context: Context): Long? {
        return try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "ACTIVITY_RECOGNITION not granted; phone falls back to Health Connect")
                return null
            }
            val cumulative = readCounterOnce(context) ?: return null

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = LocalDate.now(ZoneId.systemDefault()).format(DATE_FMT)
            var baselineDate = prefs.getString(K_BASELINE_DATE, null)
            var baseline = prefs.getLong(K_BASELINE_STEPS, -1L)
            var carried = prefs.getLong(K_CARRIED_STEPS, 0L)
            val lastCumulative = prefs.getLong(K_LAST_CUMULATIVE, -1L)

            if (baselineDate != today || baseline < 0) {
                // New day (or first run): today's counter reading becomes
                // the midnight baseline. The first read of the day lands
                // within minutes of midnight while the 10-minute recording
                // tick is running, so the undercount is tiny.
                baselineDate = today
                baseline = cumulative
                carried = 0L
            } else if (cumulative < baseline) {
                // Reboot: TYPE_STEP_COUNTER resets to 0. Carry over the
                // steps we'd already counted so the day total survives.
                if (lastCumulative >= baseline) {
                    carried += lastCumulative - baseline
                }
                baseline = cumulative
            }

            val steps = (carried + cumulative - baseline).coerceAtLeast(0)
            prefs.edit()
                .putString(K_BASELINE_DATE, baselineDate)
                .putLong(K_BASELINE_STEPS, baseline)
                .putLong(K_CARRIED_STEPS, carried)
                .putLong(K_LAST_CUMULATIVE, cumulative)
                .apply()
            steps
        } catch (t: Throwable) {
            Log.w(TAG, "readTodaySteps failed", t)
            null
        }
    }

    /**
     * Reads today's steps and, if the count changed since the last
     * successful report, sends it to every connected node on
     * [Link.PATH_STEPS_DAILY]. The last-sent (date, count) is persisted and
     * only updated on a successful send, so a missed send is retried on the
     * next tick / peer connect. Never throws.
     */
    suspend fun maybeReport(context: Context) {
        try {
            val count = readTodaySteps(context) ?: return
            val today = LocalDate.now(ZoneId.systemDefault()).format(DATE_FMT)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (today == prefs.getString(K_LAST_DATE, null) &&
                count == prefs.getLong(K_LAST_STEPS, -1L)
            ) {
                return // Unchanged since the last successful report.
            }
            val nodes = try {
                Wearable.getNodeClient(context).connectedNodes.await()
            } catch (t: Throwable) {
                Log.w(TAG, "No node list; will retry later", t)
                return
            }
            if (nodes.isEmpty()) return
            val payload = DataMap().apply {
                putLong(Link.KEY_STEPS, count)
                putString(Link.KEY_STEP_DATE, today)
                putLong(Link.KEY_TIMESTAMP, System.currentTimeMillis())
            }.toByteArray()
            var sent = false
            for (node in nodes) {
                try {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, Link.PATH_STEPS_DAILY, payload)
                        .await()
                    sent = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Steps send to ${node.displayName} failed", t)
                }
            }
            if (sent) {
                prefs.edit()
                    .putString(K_LAST_DATE, today)
                    .putLong(K_LAST_STEPS, count)
                    .apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "maybeReport failed", t)
        }
    }

    /**
     * One-shot read of the cumulative TYPE_STEP_COUNTER value (steps since
     * last boot). Registers on a dedicated HandlerThread and waits for the
     * sensor's first event, which carries the current counter value.
     */
    private suspend fun readCounterOnce(context: Context): Long? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val thread = HandlerThread("StepsReporter").apply { start() }
        try {
            val result = CompletableDeferred<Long>()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_STEP_COUNTER &&
                        event.values.isNotEmpty()
                    ) {
                        result.complete(event.values[0].toLong())
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }
            val registered = try {
                sm.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    Handler(thread.looper),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Sensor registration failed", t)
                return null
            }
            if (!registered) return null
            try {
                return withTimeoutOrNull(SENSOR_TIMEOUT_MS) { result.await() }
            } finally {
                try {
                    sm.unregisterListener(listener)
                } catch (_: Throwable) {
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

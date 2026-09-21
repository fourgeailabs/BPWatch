package com.fourgeailabs.bpwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log

/**
 * Off-body (off-wrist) detection (v2.3).
 *
 * Sampling HR/BP/stress while the watch lies on a table produces zeros or
 * garbage and can fire phantom alerts — so every sampling path consults
 * this before recording anything:
 *
 * - Preferred: the hardware off-body sensor
 *   (Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, one-shot, present on most
 *   Wear OS 3+ watches). It reports 1.0 = on-body, 0.0 = off-body. Because
 *   it is one-shot it only fires on state *changes*, so it is started
 *   alongside every sampling run and its last-known state is consulted —
 *   an explicit off-body event is authoritative, an absent event is not.
 * - Fallback heuristic: N consecutive sampling attempts with no valid HR
 *   signal => treat as off-wrist; resume on the first valid signal.
 *   N = 3 (EMPTY_ATTEMPTS_THRESHOLD): low enough to stop recording
 *   garbage quickly, high enough that one flaky 30 s run doesn't pause
 *   everything.
 *
 * Never throws.
 */
object OffBodyDetector {
    private const val TAG = "OffBodyDetector"

    /** Consecutive empty attempts before the heuristic calls it off-wrist. */
    const val EMPTY_ATTEMPTS_THRESHOLD = 3

    /** @return true when the device has a dedicated off-body sensor. */
    fun hasHardwareSensor(context: Context): Boolean = try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Records an attempt that produced no usable HR signal (null result, or
     * the hardware sensor explicitly reported off-body). @return the new
     * consecutive-empty count.
     */
    fun noteEmptyAttempt(context: Context): Int {
        val next = emptyAttempts(context) + 1
        try {
            WatchSettings.setOffBodyStreak(context, next)
        } catch (_: Exception) {
        }
        return next
    }

    /** A valid HR signal resets the streak — the watch is back on-wrist. */
    fun noteValidSignal(context: Context) {
        try {
            WatchSettings.setOffBodyStreak(context, 0)
        } catch (_: Exception) {
        }
    }

    fun emptyAttempts(context: Context): Int = try {
        WatchSettings.getOffBodyStreak(context)
    } catch (_: Exception) {
        0
    }

    /** True when the heuristic considers the watch off-wrist. */
    fun isPausedByHeuristic(context: Context): Boolean =
        emptyAttempts(context) >= EMPTY_ATTEMPTS_THRESHOLD
}

/**
 * Thin listener for TYPE_LOW_LATENCY_OFFBODY_DETECT. One-shot: fires only
 * when the on-body state *changes*. [lastState] is null until the first
 * event — null means "unknown"; never treat it as off-body.
 */
class OffBodySensor(context: Context) : SensorEventListener {
    val appContext: Context = context.applicationContext

    private val sensorManager: SensorManager? = try {
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    } catch (_: Exception) {
        null
    }
    private val sensor: Sensor? = try {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
    } catch (_: Exception) {
        null
    }

    val present: Boolean get() = sensor != null

    /** true = on-body, false = off-body, null = no event seen yet. */
    @Volatile
    var lastState: Boolean? = null
        private set

    /** Fires on every state change (from whatever thread the handler uses). */
    var onState: ((Boolean) -> Unit)? = null

    fun start(handler: Handler?) {
        val s = sensor ?: return
        val sm = sensorManager ?: return
        try {
            if (handler != null) {
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
            } else {
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            // e.g. SecurityException on odd firmware — the heuristic
            // fallback covers us, so just log and carry on.
            Log.w(TAG, "Off-body sensor unavailable", e)
        }
    }

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
        onState = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT &&
            event.values.isNotEmpty()
        ) {
            val onBody = event.values[0] >= 0.5f
            lastState = onBody
            try {
                onState?.invoke(onBody)
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for a binary state.
    }

    companion object {
        private const val TAG = "OffBodyDetector"
    }
}

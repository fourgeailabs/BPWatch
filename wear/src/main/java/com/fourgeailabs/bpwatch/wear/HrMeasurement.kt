package com.fourgeailabs.bpwatch.wear

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay

/**
 * One self-contained heart-rate sampling run, usable from the UI or from the
 * background hourly check. The sensor listener runs on its own thread so this
 * works without a UI Looper.
 */
object HrMeasurement {
    const val DURATION_MS = 30_000L

    data class Result(
        /** Average heart rate in bpm. */
        val averageHr: Float,
        /** Raw bpm samples (for stress estimation). */
        val samples: List<Float>,
    )

    /** @return the measurement, or null if no valid samples. */
    suspend fun measure(context: Context, durationMs: Long = DURATION_MS): Result? {
        val monitor = HeartRateMonitor(context)
        if (!monitor.available) return null
        val samples = mutableListOf<Float>()
        val thread = HandlerThread("bpwatch-hr").apply { start() }
        try {
            monitor.onSample = { hr ->
                if (hr > 0f) samples.add(hr)
            }
            monitor.start(Handler(thread.looper))
            delay(durationMs)
        } finally {
            monitor.stop()
            thread.quitSafely()
        }
        val valid = samples.filter { it in 25f..250f }
        if (valid.isEmpty()) return null
        return Result(
            averageHr = valid.average().toFloat(),
            samples = valid,
        )
    }
}

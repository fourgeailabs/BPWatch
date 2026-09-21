package com.fourgeailabs.bpwatch.wear

import kotlin.math.sqrt

/**
 * Experimental stress estimate (0-100) from heart-rate samples.
 *
 * This is NOT a medical measurement. The built-in stress sensors on
 * watches like the Galaxy Watch are proprietary to the manufacturer's
 * health app and not exposed to third-party apps, so this
 * derives a rough proxy from two signals available during a 30-second
 * measurement:
 *
 * 1. Heart-rate elevation above the user's resting baseline — sustained
 *    elevation at rest correlates with sympathetic (fight-or-flight) activity.
 * 2. Beat-to-beat variability proxy — a very steady heart rate at rest
 *    (low coefficient of variation) correlates with tension, while more
 *    variability correlates with a relaxed parasympathetic state.
 *
 * Returns -1 when there aren't enough valid samples to say anything.
 */
object StressEstimator {

    fun estimate(samples: List<Float>, restingHr: Float): Int {
        val valid = samples.filter { it in 25f..250f }
        if (valid.size < 5 || restingHr <= 0f) return -1

        val mean = valid.average().toFloat()
        val variance = valid.map { (it - mean) * (it - mean) }.average()
        val cv = sqrt(variance).toFloat() / mean

        // Elevation: 0 points at resting, up to 60 at 2x resting.
        val elevationScore = ((mean - restingHr) / restingHr)
            .coerceIn(0f, 1f) * 60f

        // Variability: CV of 0.02 (metronome-steady) -> 40 pts,
        // CV of 0.06+ (nice and wobbly) -> 0 pts.
        val variabilityScore = ((0.06f - cv) / 0.04f)
            .coerceIn(0f, 1f) * 40f

        return (elevationScore + variabilityScore).toInt().coerceIn(0, 100)
    }

    /** Human label for a stress score. */
    fun label(score: Int): String = when {
        score < 0 -> "—"
        score < 25 -> "Relaxed"
        score < 50 -> "Calm"
        score < 75 -> "Tense"
        else -> "Stressed"
    }
}

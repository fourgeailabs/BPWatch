package com.fourgeailabs.bpwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class StressEstimatorTest {

    @Test
    fun `returns -1 with no samples`() {
        assertEquals(-1, StressEstimator.estimate(emptyList(), 70f))
    }

    @Test
    fun `returns -1 with fewer than 5 valid samples`() {
        assertEquals(-1, StressEstimator.estimate(listOf(70f, 71f, 69f, 70f), 70f))
    }

    @Test
    fun `returns -1 without a positive resting baseline`() {
        val samples = List(10) { 70f }
        assertEquals(-1, StressEstimator.estimate(samples, 0f))
        assertEquals(-1, StressEstimator.estimate(samples, -60f))
    }

    @Test
    fun `filters out physiologically impossible samples`() {
        // 10 and 300 bpm are dropped; the five 70s that remain decide.
        val samples = listOf(10f, 300f, 70f, 70f, 70f, 70f, 70f)
        assertEquals(40, StressEstimator.estimate(samples, 70f))
        // But four valid samples are still not enough.
        assertEquals(-1, StressEstimator.estimate(listOf(10f, 300f, 70f, 70f, 70f, 70f), 70f))
    }

    @Test
    fun `steady heart rate at resting baseline scores 40`() {
        // No elevation (0 pts) + metronome-steady variability (40 pts).
        assertEquals(40, StressEstimator.estimate(List(10) { 70f }, 70f))
    }

    @Test
    fun `heart rate at twice resting with no variability maxes out at 100`() {
        assertEquals(100, StressEstimator.estimate(List(10) { 140f }, 70f))
    }

    @Test
    fun `elevation above resting adds up to 60 points`() {
        // Steady 90 against a resting 60: elevation 0.5 -> 30 pts,
        // variability 40 pts -> 70 total.
        assertEquals(70, StressEstimator.estimate(List(10) { 90f }, 60f))
    }

    @Test
    fun `heart rate below resting adds no elevation points`() {
        assertEquals(40, StressEstimator.estimate(List(10) { 50f }, 70f))
    }

    @Test
    fun `high variability wipes the variability component`() {
        // Alternating 65/75 at rest: cv ~0.071 >= 0.06 -> 0 variability pts.
        val samples = List(10) { i -> if (i % 2 == 0) 65f else 75f }
        assertEquals(0, StressEstimator.estimate(samples, 70f))
    }

    @Test
    fun `variability score scales between cv 0_02 and 0_06`() {
        // Alternating 66/74 at rest: mean 70, sd 4, cv ~0.057 ->
        // variability ((0.06 - 0.057) / 0.04) * 40 ~ 2.86 -> 2 pts.
        val medium = List(10) { i -> if (i % 2 == 0) 66f else 74f }
        assertEquals(2, StressEstimator.estimate(medium, 70f))
        // Perfectly steady (cv 0) keeps the full 40 variability points.
        assertEquals(40, StressEstimator.estimate(List(10) { 70f }, 70f))
    }

    @Test
    fun `label maps the documented bands`() {
        assertEquals("—", StressEstimator.label(-1))
        assertEquals("Relaxed", StressEstimator.label(0))
        assertEquals("Relaxed", StressEstimator.label(24))
        assertEquals("Calm", StressEstimator.label(25))
        assertEquals("Calm", StressEstimator.label(49))
        assertEquals("Tense", StressEstimator.label(50))
        assertEquals("Tense", StressEstimator.label(74))
        assertEquals("Stressed", StressEstimator.label(75))
        assertEquals("Stressed", StressEstimator.label(100))
    }
}

package com.fourgeailabs.bpwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateTest {

    @Test
    fun `onEstimate publishes the latest estimate`() {
        WatchState.onEstimate(128, 84, 123456789L)
        val estimate = WatchState.lastEstimate.value
        assertEquals(BpEstimate(128, 84, 123456789L), estimate)
    }

    @Test
    fun `a newer estimate replaces the previous one`() {
        WatchState.onEstimate(120, 80, 1L)
        WatchState.onEstimate(135, 88, 2L)
        assertEquals(BpEstimate(135, 88, 2L), WatchState.lastEstimate.value)
    }

    @Test
    fun `onCalibration flips the calibrated flag`() {
        WatchState.onCalibration(true)
        assertTrue(WatchState.calibrated.value)
        WatchState.onCalibration(false)
        assertFalse(WatchState.calibrated.value)
    }

    @Test
    fun `onMonitorConfig publishes the config`() {
        val config = MonitorConfig(bpIntervalMinutes = 60, hrHighEnabled = true)
        WatchState.onMonitorConfig(config)
        assertEquals(config, WatchState.monitorConfig.value)
    }

    @Test
    fun `onLiveHr publishes the live heart rate`() {
        WatchState.onLiveHr(96.5f)
        assertEquals(96.5f, WatchState.liveHr.value, 0.0f)
    }
}

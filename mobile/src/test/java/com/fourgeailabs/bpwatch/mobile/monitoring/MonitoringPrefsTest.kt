package com.fourgeailabs.bpwatch.mobile.monitoring

import com.fourgeailabs.bpwatch.mobile.testutil.FakeContext
import com.fourgeailabs.bpwatch.mobile.testutil.FakeSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitoringConfigTest {

    @Test
    fun `intervalLabel covers every offered option`() {
        assertEquals("Every 10 minutes", MonitoringConfig.intervalLabel(10))
        assertEquals("Every 15 minutes", MonitoringConfig.intervalLabel(15))
        assertEquals("Every 30 minutes", MonitoringConfig.intervalLabel(30))
        assertEquals("Every 45 minutes", MonitoringConfig.intervalLabel(45))
        assertEquals("Every hour", MonitoringConfig.intervalLabel(60))
        assertEquals("Every 6 hours", MonitoringConfig.intervalLabel(360))
        assertEquals("Once a day", MonitoringConfig.intervalLabel(1440))
        assertEquals("On demand only", MonitoringConfig.intervalLabel(0))
    }

    @Test
    fun `intervalLabel falls back for unknown values`() {
        assertEquals("On demand only", MonitoringConfig.intervalLabel(20))
        assertEquals("On demand only", MonitoringConfig.intervalLabel(-5))
        assertEquals("On demand only", MonitoringConfig.intervalLabel(9999))
    }

    @Test
    fun `INTERVAL_OPTIONS matches the documented set in order`() {
        assertEquals(
            listOf(10, 15, 30, 45, 60, 360, 1440, 0),
            MonitoringConfig.INTERVAL_OPTIONS,
        )
    }

    @Test
    fun `companion constants have the documented values`() {
        assertEquals(0, MonitoringConfig.BP_ON_DEMAND)
        assertEquals(1440, MonitoringConfig.BP_ONCE_A_DAY)
        assertEquals(80, MonitoringConfig.HR_THRESHOLD_MIN)
        assertEquals(220, MonitoringConfig.HR_THRESHOLD_MAX)
        assertEquals(70, MonitoringConfig.BP_SYS_MIN)
        assertEquals(250, MonitoringConfig.BP_SYS_MAX)
        assertEquals(40, MonitoringConfig.BP_DIA_MIN)
        assertEquals(150, MonitoringConfig.BP_DIA_MAX)
    }
}

class MonitoringPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefsBacking = mutableMapOf<String, FakeSharedPreferences>()

    private fun context() = FakeContext(File(tmp.root, "files"), prefsBacking)

    @Test
    fun `defaults are monitoring-off and unconfigured`() {
        val prefs = MonitoringPrefs(context())
        val config = prefs.config.value
        assertEquals(MonitoringConfig(), config)
        assertFalse(prefs.isConfigured())
        assertFalse(prefs.recordHr.value)
    }

    @Test
    fun `update clamps the heart-rate threshold into range`() {
        val prefs = MonitoringPrefs(context())
        assertEquals(220, prefs.update { it.copy(hrHighThreshold = 500) }.hrHighThreshold)
        assertEquals(80, prefs.update { it.copy(hrHighThreshold = 10) }.hrHighThreshold)
        assertEquals(120, prefs.update { it.copy(hrHighThreshold = 120) }.hrHighThreshold)
    }

    @Test
    fun `update rejects interval values outside the offered options`() {
        val prefs = MonitoringPrefs(context())
        // 20 minutes is not an offered option -> falls back to on-demand.
        assertEquals(0, prefs.update { it.copy(bpIntervalMinutes = 20) }.bpIntervalMinutes)
        // Negative values are not options either -> on-demand.
        assertEquals(0, prefs.update { it.copy(bpIntervalMinutes = -3) }.bpIntervalMinutes)
        // Every offered option survives sanitisation.
        MonitoringConfig.INTERVAL_OPTIONS.forEach { option ->
            assertEquals(
                "option $option should survive",
                option,
                prefs.update { it.copy(bpIntervalMinutes = option) }.bpIntervalMinutes,
            )
        }
    }

    @Test
    fun `update clamps blood-pressure thresholds into range`() {
        val prefs = MonitoringPrefs(context())
        val clamped = prefs.update {
            it.copy(sysHigh = 999, diaHigh = 999, sysLow = -50, diaLow = -50)
        }
        assertEquals(250, clamped.sysHigh)
        assertEquals(150, clamped.diaHigh)
        assertEquals(70, clamped.sysLow)
        assertEquals(40, clamped.diaLow)
    }

    @Test
    fun `update marks the prefs configured and persists across instances`() {
        val prefs = MonitoringPrefs(context())
        prefs.update { it.copy(hrHighThreshold = 130, bpIntervalMinutes = 60) }
        assertTrue(prefs.isConfigured())

        // A new instance over the same backing store sees the saved values.
        val reloaded = MonitoringPrefs(context())
        assertTrue(reloaded.isConfigured())
        assertEquals(130, reloaded.config.value.hrHighThreshold)
        assertEquals(60, reloaded.config.value.bpIntervalMinutes)
    }

    @Test
    fun `setRecordHr round-trips and persists`() {
        val prefs = MonitoringPrefs(context())
        prefs.setRecordHr(true)
        assertTrue(prefs.recordHr.value)
        assertTrue(MonitoringPrefs(context()).recordHr.value)

        prefs.setRecordHr(false)
        assertFalse(prefs.recordHr.value)
        assertFalse(MonitoringPrefs(context()).recordHr.value)
    }
}

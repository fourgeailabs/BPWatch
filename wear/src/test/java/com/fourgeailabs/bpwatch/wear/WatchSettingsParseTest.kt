package com.fourgeailabs.bpwatch.wear

import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSettingsParseTest {

    private fun fullConfigMap(): DataMap = DataMap().apply {
        putInt(Link.KEY_CONFIG_V, 1)
        putBoolean(Link.KEY_CONTINUOUS_HR, true)
        putBoolean(Link.KEY_HR_HIGH_ENABLED, true)
        putInt(Link.KEY_HR_HIGH_THRESHOLD, 135)
        putInt(Link.KEY_BP_INTERVAL_MIN, 45)
        putBoolean(Link.KEY_BP_HIGH_ENABLED, true)
        putInt(Link.KEY_SYS_HIGH, 150)
        putInt(Link.KEY_DIA_HIGH, 95)
        putBoolean(Link.KEY_BP_LOW_ENABLED, true)
        putInt(Link.KEY_SYS_LOW, 85)
        putInt(Link.KEY_DIA_LOW, 55)
    }

    @Test
    fun `parseMonitorConfig reads every field`() {
        val config = WatchSettings.parseMonitorConfig(fullConfigMap())
        assertEquals(
            MonitorConfig(
                continuousHr = true,
                hrHighEnabled = true,
                hrHighThreshold = 135,
                bpIntervalMinutes = 45,
                bpHighEnabled = true,
                sysHigh = 150,
                diaHigh = 95,
                bpLowEnabled = true,
                sysLow = 85,
                diaLow = 55,
            ),
            config,
        )
    }

    @Test
    fun `parseMonitorConfig falls back to defaults without a version key`() {
        // An old phone build that never sent KEY_CONFIG_V -> safe defaults.
        assertEquals(MonitorConfig(), WatchSettings.parseMonitorConfig(DataMap()))
    }

    @Test
    fun `parseMonitorConfig uses per-field defaults for missing keys`() {
        val map = DataMap().apply {
            putInt(Link.KEY_CONFIG_V, 1)
            putInt(Link.KEY_BP_INTERVAL_MIN, 60)
        }
        val config = WatchSettings.parseMonitorConfig(map)
        assertEquals(60, config.bpIntervalMinutes)
        assertEquals(120, config.hrHighThreshold)
        assertEquals(140, config.sysHigh)
        assertEquals(90, config.diaHigh)
        assertEquals(90, config.sysLow)
        assertEquals(60, config.diaLow)
        assertFalse(config.continuousHr)
        assertFalse(config.hrHighEnabled)
        assertFalse(config.bpHighEnabled)
        assertFalse(config.bpLowEnabled)
    }

    @Test
    fun `parseMonitorConfig accepts any version value once present`() {
        // Only the presence of KEY_CONFIG_V is checked, so a newer phone
        // build's fields still parse; unknown extras are ignored.
        val map = fullConfigMap().apply { putInt(Link.KEY_CONFIG_V, 99) }
        val config = WatchSettings.parseMonitorConfig(map)
        assertEquals(45, config.bpIntervalMinutes)
        assertEquals(135, config.hrHighThreshold)
    }

    @Test
    fun `parseMonitorConfig survives the DataMap wire format`() {
        val decoded = DataMap.fromByteArray(fullConfigMap().toByteArray())
        assertEquals(45, WatchSettings.parseMonitorConfig(decoded).bpIntervalMinutes)
    }

    @Test
    fun `bpIntervalLabel covers every watch interval choice`() {
        assertEquals("Every 10 minutes", bpIntervalLabel(10))
        assertEquals("Every 15 minutes", bpIntervalLabel(15))
        assertEquals("Every 30 minutes", bpIntervalLabel(30))
        assertEquals("Every 45 minutes", bpIntervalLabel(45))
        assertEquals("Every hour", bpIntervalLabel(60))
        assertEquals("Every 6 hours", bpIntervalLabel(360))
        assertEquals("Once a day", bpIntervalLabel(1440))
    }

    @Test
    fun `bpIntervalLabel falls back to Off`() {
        assertEquals("Off", bpIntervalLabel(0))
        assertEquals("Off", bpIntervalLabel(20))
        assertEquals("Off", bpIntervalLabel(-1))
    }

    @Test
    fun `WATCH_INTERVAL_CHOICES lists every option in picker order`() {
        assertEquals(
            listOf(10, 15, 30, 45, 60, 360, 1440, 0),
            WATCH_INTERVAL_CHOICES,
        )
        // Every choice has a real label (none falls through to "Off"
        // except the off option itself).
        WATCH_INTERVAL_CHOICES.forEach { minutes ->
            if (minutes != 0) {
                assertTrue(
                    "choice $minutes has no label",
                    bpIntervalLabel(minutes) != "Off",
                )
            }
        }
    }

    @Test
    fun `alert cooldown keys are all distinct`() {
        val keys = setOf(
            WatchSettings.ALERT_HR_HIGH,
            WatchSettings.ALERT_HR_LOW_EXTREME,
            WatchSettings.ALERT_HR_HIGH_EXTREME,
            WatchSettings.ALERT_BP_HIGH,
            WatchSettings.ALERT_BP_HIGH_EXTREME,
            WatchSettings.ALERT_BP_LOW,
            WatchSettings.ALERT_BP_LOW_EXTREME,
        )
        assertEquals(7, keys.size)
    }
}

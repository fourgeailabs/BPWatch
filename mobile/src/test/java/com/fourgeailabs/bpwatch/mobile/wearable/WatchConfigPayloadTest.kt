package com.fourgeailabs.bpwatch.mobile.wearable

import com.fourgeailabs.bpwatch.Link
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the real phone-side monitoring-config serializer
 * (WatchConfigSender.toPayload, reached via reflection because it is
 * private) by decoding its bytes with DataMap — the same bytes the watch
 * parses with WatchSettings.parseMonitorConfig.
 */
class WatchConfigPayloadTest {

    private fun toPayload(config: MonitoringConfig): ByteArray {
        val method = WatchConfigSender::class.java.getDeclaredMethod(
            "toPayload",
            MonitoringConfig::class.java,
        )
        method.isAccessible = true
        return method.invoke(WatchConfigSender, config) as ByteArray
    }

    private fun configMap(config: MonitoringConfig): DataMap =
        DataMap.fromByteArray(toPayload(config))

    @Test
    fun `payload is versioned so the watch can ignore newer fields`() {
        val map = configMap(MonitoringConfig())
        assertTrue(map.containsKey(Link.KEY_CONFIG_V))
        assertEquals(1, map.getInt(Link.KEY_CONFIG_V))
    }

    @Test
    fun `payload carries every monitoring field`() {
        val config = MonitoringConfig(
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
        )
        val map = configMap(config)
        assertEquals(true, map.getBoolean(Link.KEY_CONTINUOUS_HR))
        assertEquals(true, map.getBoolean(Link.KEY_HR_HIGH_ENABLED))
        assertEquals(135, map.getInt(Link.KEY_HR_HIGH_THRESHOLD))
        assertEquals(45, map.getInt(Link.KEY_BP_INTERVAL_MIN))
        assertEquals(true, map.getBoolean(Link.KEY_BP_HIGH_ENABLED))
        assertEquals(150, map.getInt(Link.KEY_SYS_HIGH))
        assertEquals(95, map.getInt(Link.KEY_DIA_HIGH))
        assertEquals(true, map.getBoolean(Link.KEY_BP_LOW_ENABLED))
        assertEquals(85, map.getInt(Link.KEY_SYS_LOW))
        assertEquals(55, map.getInt(Link.KEY_DIA_LOW))
    }

    @Test
    fun `default config serialises to the documented defaults`() {
        val map = configMap(MonitoringConfig())
        assertEquals(false, map.getBoolean(Link.KEY_CONTINUOUS_HR))
        assertEquals(false, map.getBoolean(Link.KEY_HR_HIGH_ENABLED))
        assertEquals(120, map.getInt(Link.KEY_HR_HIGH_THRESHOLD))
        assertEquals(0, map.getInt(Link.KEY_BP_INTERVAL_MIN))
        assertEquals(140, map.getInt(Link.KEY_SYS_HIGH))
        assertEquals(90, map.getInt(Link.KEY_DIA_HIGH))
        assertEquals(90, map.getInt(Link.KEY_SYS_LOW))
        assertEquals(60, map.getInt(Link.KEY_DIA_LOW))
    }

    @Test
    fun `payload survives a full DataMap wire round-trip`() {
        val config = MonitoringConfig(hrHighThreshold = 150, bpIntervalMinutes = 360)
        val bytes = toPayload(config)
        // The exact bytes the watch receives on PATH_MONITORING_CONFIG.
        val decoded = DataMap.fromByteArray(bytes)
        assertEquals(150, decoded.getInt(Link.KEY_HR_HIGH_THRESHOLD))
        assertEquals(360, decoded.getInt(Link.KEY_BP_INTERVAL_MIN))
    }
}

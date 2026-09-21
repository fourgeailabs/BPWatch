package com.fourgeailabs.bpwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Data Layer contract: every path/key must be unique and
 * well-formed, and the threshold constants must carry the documented
 * values. The mobile and wear copies of Link.kt must stay identical —
 * both modules run this same suite against their own copy.
 */
class LinkContractTest {

    private fun constValues(prefix: String): List<String> =
        Link::class.java.declaredFields
            .filter { it.name.startsWith(prefix) }
            .map { it.get(null) as String }

    @Test
    fun `every path is distinct and namespaced under bpwatch`() {
        val paths = constValues("PATH_")
        assertTrue("expected a healthy set of paths, got ${paths.size}", paths.size >= 15)
        assertEquals(paths.size, paths.toSet().size)
        paths.forEach { path ->
            assertTrue("path not namespaced: $path", path.startsWith("/bpwatch/"))
        }
    }

    @Test
    fun `every key is distinct and non-blank`() {
        val keys = constValues("KEY_")
        assertTrue("expected a healthy set of keys, got ${keys.size}", keys.size >= 20)
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("blank key", key.isNotBlank())
        }
    }

    @Test
    fun `no key collides with a path value`() {
        val paths = constValues("PATH_").toSet()
        val keys = constValues("KEY_").toSet()
        assertTrue(keys.intersect(paths).isEmpty())
    }

    @Test
    fun `alert types and severities carry the documented values`() {
        assertEquals("hr_high", Link.AlertType.HR_HIGH)
        assertEquals("hr_low", Link.AlertType.HR_LOW)
        assertEquals("bp_high", Link.AlertType.BP_HIGH)
        assertEquals("bp_low", Link.AlertType.BP_LOW)
        assertEquals("normal", Link.Severity.NORMAL)
        assertEquals("extreme", Link.Severity.EXTREME)
    }

    @Test
    fun `extreme thresholds carry the documented fixed values`() {
        assertEquals(160f, Link.ExtremeThresholds.HR_HIGH)
        assertEquals(40f, Link.ExtremeThresholds.HR_LOW)
        assertEquals(180, Link.ExtremeThresholds.SYS_HIGH)
        assertEquals(120, Link.ExtremeThresholds.DIA_HIGH)
        assertEquals(80, Link.ExtremeThresholds.SYS_LOW)
        assertEquals(50, Link.ExtremeThresholds.DIA_LOW)
    }

    @Test
    fun `config payload is versioned`() {
        assertEquals("v", Link.KEY_CONFIG_V)
    }

    @Test
    fun `history batch keys are distinct`() {
        val batchKeys = setOf(Link.KEY_HIST_TS, Link.KEY_HIST_BPM, Link.KEY_HIST_STRESS)
        assertEquals(3, batchKeys.size)
    }

    @Test
    fun `history ACK carries the batch min and max timestamps`() {
        // The ACK bounds pruning to [timestamp_min, timestamp] so a failed
        // earlier batch is never deleted by a later batch's ACK.
        assertEquals("timestamp_min", Link.KEY_TIMESTAMP_MIN)
        assertEquals("timestamp", Link.KEY_TIMESTAMP)
    }
}

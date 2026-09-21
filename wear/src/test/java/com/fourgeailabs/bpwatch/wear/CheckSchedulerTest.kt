package com.fourgeailabs.bpwatch.wear

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the daily-alarm anchor math in CheckScheduler. nextMorning8am is
 * private, so it is reached via reflection — but this is the real
 * function the 1440-minute (once a day) schedule uses.
 */
class CheckSchedulerTest {

    private val nextMorning8am = CheckScheduler::class.java
        .getDeclaredMethod("nextMorning8am", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private fun nextMorning8am(now: Long): Long =
        nextMorning8am.invoke(CheckScheduler, now) as Long

    /** [now] as a Calendar set to the given local time today. */
    private fun todayAt(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun fields(ts: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = ts }

    @Test
    fun `morning time anchors to 8am today`() {
        val now = todayAt(7, 30)
        val result = fields(nextMorning8am(now))
        val expected = fields(todayAt(8, 0))
        assertEquals(expected.timeInMillis, result.timeInMillis)
    }

    @Test
    fun `afternoon time rolls to 8am tomorrow`() {
        val now = todayAt(9, 15)
        val cal = fields(nextMorning8am(now))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
        // Tomorrow, not today.
        assertTrue(nextMorning8am(now) - now in 1..(24 * 3600_000L))
        assertTrue(nextMorning8am(now) > todayAt(8, 0))
    }

    @Test
    fun `exactly 8am rolls to tomorrow since the alarm time already passed`() {
        val now = todayAt(8, 0)
        val result = nextMorning8am(now)
        assertTrue(result > now)
        assertTrue(result - now in 1..(24 * 3600_000L))
        assertEquals(8, fields(result).get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `just before midnight still lands on the coming 8am`() {
        val now = todayAt(23, 59)
        val result = nextMorning8am(now)
        val cal = fields(result)
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertTrue(result > now)
        assertTrue(result - now <= 24 * 3600_000L)
    }

    @Test
    fun `result is always a future 8am within 24 hours`() {
        listOf(
            todayAt(0, 0),
            todayAt(7, 59),
            todayAt(12, 0),
            todayAt(20, 45),
        ).forEach { now ->
            val result = nextMorning8am(now)
            val cal = fields(result)
            assertTrue("result must be in the future (now=$now)", result > now)
            assertTrue("result must be within 24h", result - now <= 24 * 3600_000L)
            assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, cal.get(Calendar.MINUTE))
            assertEquals(0, cal.get(Calendar.SECOND))
            assertEquals(0, cal.get(Calendar.MILLISECOND))
        }
    }

    @Test
    fun `interval choices stay stable for the alarm scheduler`() {
        // The once-a-day branch keys off exactly 1440; the picker and the
        // scheduler must agree on it.
        assertTrue(WATCH_INTERVAL_CHOICES.contains(1440))
    }
}

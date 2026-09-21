package com.fourgeailabs.bpwatch.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the chart bucketing helpers in TrendsScreen.kt. They are
 * file-private, so they are reached via reflection — but these are the real
 * functions the charts use, not copies.
 */
class TrendBucketingTest {

    private val screenKt = Class.forName("com.fourgeailabs.bpwatch.mobile.ui.TrendsScreenKt")
    private val chartPoint = Class.forName("com.fourgeailabs.bpwatch.mobile.ui.ChartPoint")

    private val ctor = chartPoint
        .getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }
    private val getX = chartPoint.getDeclaredMethod("getX").apply { isAccessible = true }
    private val getY = chartPoint.getDeclaredMethod("getY").apply { isAccessible = true }

    private val bucketHourly = screenKt
        .getDeclaredMethod(
            "bucketHourly",
            List::class.java,
            Long::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }
    private val downsample = screenKt
        .getDeclaredMethod(
            "downsample",
            List::class.java,
            Int::class.javaPrimitiveType,
        )
        .apply { isAccessible = true }
    private val fmtDouble = screenKt
        .getDeclaredMethod("fmt", Double::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private fun pt(x: Long, y: Float): Any = ctor.newInstance(x, y)
    private fun xOf(p: Any): Long = getX.invoke(p) as Long
    private fun yOf(p: Any): Float = getY.invoke(p) as Float

    @Suppress("UNCHECKED_CAST")
    private fun bucketed(points: List<Any>, start: Long): List<Any> =
        bucketHourly.invoke(null, points, start) as List<Any>

    @Suppress("UNCHECKED_CAST")
    private fun downsampled(points: List<Any>, max: Int = 240): List<Any> =
        downsample.invoke(null, points, max) as List<Any>

    // ------------------------------------------------------------------
    // bucketHourly
    // ------------------------------------------------------------------

    @Test
    fun `bucketHourly of empty input is empty`() {
        assertTrue(bucketed(emptyList(), 0L).isEmpty())
    }

    @Test
    fun `bucketHourly groups points into hour buckets anchored at start`() {
        val hour = 3600_000L
        val start = 1_000_000L
        val out = bucketed(
            listOf(
                pt(start + 10 * 60_000L, 10f),
                pt(start + 50 * 60_000L, 20f),
                pt(start + hour + 5 * 60_000L, 30f),
            ),
            start,
        )
        assertEquals(2, out.size)
        assertEquals(start, xOf(out[0]))
        assertEquals(15f, yOf(out[0]), 0.001f)
        assertEquals(start + hour, xOf(out[1]))
        assertEquals(30f, yOf(out[1]), 0.001f)
    }

    @Test
    fun `bucketHourly clamps pre-start points into the first bucket`() {
        val start = 1_000_000L
        val out = bucketed(listOf(pt(start - 5_000L, 99f)), start)
        assertEquals(1, out.size)
        assertEquals(start, xOf(out[0]))
        assertEquals(99f, yOf(out[0]), 0.001f)
    }

    @Test
    fun `bucketHourly sorts buckets by time`() {
        val hour = 3600_000L
        val out = bucketed(
            listOf(
                pt(3 * hour, 3f),
                pt(hour, 1f),
                pt(2 * hour, 2f),
            ),
            0L,
        )
        assertEquals(listOf(1L * hour, 2L * hour, 3L * hour), out.map { xOf(it) })
    }

    @Test
    fun `bucketHourly collapses a dense hour to one average point`() {
        val start = 0L
        val points = (0 until 60).map { pt(it * 60_000L, 70f + it) }
        val out = bucketed(points, start)
        assertEquals(1, out.size)
        assertEquals(start, xOf(out[0]))
        // Average of 70..129.
        assertEquals(99.5f, yOf(out[0]), 0.001f)
    }

    // ------------------------------------------------------------------
    // downsample
    // ------------------------------------------------------------------

    @Test
    fun `downsample leaves short series untouched`() {
        val points = listOf(pt(1L, 2f), pt(3L, 4f))
        val out = downsampled(points)
        assertEquals(2, out.size)
        assertEquals(1L, xOf(out[0]))
        assertEquals(4f, yOf(out[1]), 0.001f)
    }

    @Test
    fun `downsample of exactly max points is untouched`() {
        val points = (0 until 240).map { pt(it.toLong(), it.toFloat()) }
        assertEquals(240, downsampled(points).size)
    }

    @Test
    fun `downsample reduces a dense series to max buckets`() {
        val points = (0 until 500).map { i -> pt(i * 60_000L, i.toFloat()) }
        val out = downsampled(points)
        assertEquals(240, out.size)
        // First bucket covers points 0 and 1: x = (0 + 60000)/2.
        assertEquals(30_000L, xOf(out[0]))
        assertEquals(0.5f, yOf(out[0]), 0.001f)
        // Buckets stay in time order.
        val xs = out.map { xOf(it) }
        assertEquals(xs.sorted(), xs)
        // Last bucket reaches the end of the series.
        assertTrue(xOf(out.last()) >= xOf(points.last()) - 60_000L)
    }

    @Test
    fun `downsample averages y within each bucket`() {
        // 480 identical-x points, alternating y 0/100 -> every bucket ~50.
        val points = (0 until 480).map { i -> pt(i.toLong(), (i % 2) * 100f) }
        val out = downsampled(points)
        assertEquals(240, out.size)
        out.forEach { p ->
            assertEquals(50f, yOf(p), 0.001f)
        }
    }

    // ------------------------------------------------------------------
    // fmt
    // ------------------------------------------------------------------

    private fun fmt(v: Double): String = fmtDouble.invoke(null, v) as String

    @Test
    fun `fmt drops the decimal for whole numbers`() {
        assertEquals("5", fmt(5.0))
        assertEquals("0", fmt(0.0))
        assertEquals("100", fmt(100.0))
    }

    @Test
    fun `fmt shows one decimal otherwise`() {
        assertEquals("5.2", fmt(5.24))
        assertEquals("5.3", fmt(5.25))
    }
}

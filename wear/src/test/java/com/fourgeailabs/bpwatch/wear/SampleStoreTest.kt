package com.fourgeailabs.bpwatch.wear

import com.fourgeailabs.bpwatch.wear.testutil.FakeContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun context() = FakeContext(File(tmp.root, "files"))

    private fun sample(ts: Long, bpm: Float = 72f, stress: Int = 30) =
        SampleStore.Sample(timestamp = ts, bpm = bpm, stress = stress)

    @Test
    fun `empty store reads empty and counts zero`() {
        val ctx = context()
        assertTrue(SampleStore.readAll(ctx).isEmpty())
        assertEquals(0, SampleStore.count(ctx))
    }

    @Test
    fun `append then readAll preserves order and exact values`() {
        val ctx = context()
        val samples = listOf(
            sample(1000L, 68.5f, 20),
            sample(2000L, 72.25f, 40),
            sample(3000L, 120.75f, 90),
        )
        samples.forEach { SampleStore.append(ctx, it) }

        assertEquals(3, SampleStore.count(ctx))
        assertEquals(samples, SampleStore.readAll(ctx))
    }

    @Test
    fun `records are 16 bytes big-endian timestamp plus float plus int`() {
        val ctx = context()
        SampleStore.append(ctx, sample(0x0102030405060708L, 72.5f, 0x0A0B0C0D))
        SampleStore.append(ctx, sample(2000L, 80f, 10))

        val bytes = File(ctx.filesDir, "hr_samples.bin").readBytes()
        assertEquals(32, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x0102030405060708L, buf.long)
        assertEquals(72.5f, buf.float, 0.0f)
        assertEquals(0x0A0B0C0D, buf.int)
    }

    @Test
    fun `trailing partial record from a crashed append is dropped`() {
        val ctx = context()
        SampleStore.append(ctx, sample(1000L))
        SampleStore.append(ctx, sample(2000L))
        // Simulate a crash mid-append: 7 stray bytes after 2 full records.
        File(ctx.filesDir, "hr_samples.bin").appendBytes(ByteArray(7) { 0x7F })

        val read = SampleStore.readAll(ctx)
        assertEquals(2, read.size)
        assertEquals(1000L, read[0].timestamp)
        assertEquals(2000L, read[1].timestamp)
    }

    @Test
    fun `deleteUpTo removes samples at or before the timestamp`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L), sample(3000L))
            .forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteUpTo(ctx, 2000L)

        val remaining = SampleStore.readAll(ctx)
        assertEquals(1, remaining.size)
        assertEquals(3000L, remaining[0].timestamp)
    }

    @Test
    fun `deleteUpTo with an older timestamp keeps everything`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L)).forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteUpTo(ctx, 500L)

        assertEquals(2, SampleStore.readAll(ctx).size)
    }

    @Test
    fun `deleteUpTo with the newest timestamp clears the store and deletes the file`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L)).forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteUpTo(ctx, 2000L)

        assertTrue(SampleStore.readAll(ctx).isEmpty())
        assertEquals(0, SampleStore.count(ctx))
        assertFalse(File(ctx.filesDir, "hr_samples.bin").exists())
    }

    @Test
    fun `append works after a full prune`() {
        val ctx = context()
        SampleStore.append(ctx, sample(1000L))
        SampleStore.deleteUpTo(ctx, 1000L)
        SampleStore.append(ctx, sample(5000L, 75f, 10))

        val read = SampleStore.readAll(ctx)
        assertEquals(1, read.size)
        assertEquals(sample(5000L, 75f, 10), read[0])
    }

    @Test
    fun `duplicate timestamps are all kept until pruned`() {
        val ctx = context()
        SampleStore.append(ctx, sample(1000L, 70f, 10))
        SampleStore.append(ctx, sample(1000L, 71f, 20))

        assertEquals(2, SampleStore.readAll(ctx).size)
        SampleStore.deleteUpTo(ctx, 1000L)
        assertTrue(SampleStore.readAll(ctx).isEmpty())
    }

    @Test
    fun `deleteRange keeps samples outside the acknowledged window`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L), sample(3000L), sample(4000L))
            .forEach { SampleStore.append(ctx, it) }

        // Phone ACKed only the middle batch; the failed earlier batch
        // (1000) and the not-yet-sent sample (4000) must survive.
        SampleStore.deleteRange(ctx, 2000L, 3000L)

        val remaining = SampleStore.readAll(ctx).map { it.timestamp }
        assertEquals(listOf(1000L, 4000L), remaining)
    }

    @Test
    fun `deleteRange with an empty window keeps everything`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L)).forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteRange(ctx, 5000L, 6000L)

        assertEquals(2, SampleStore.readAll(ctx).size)
    }

    @Test
    fun `deleteRange boundaries are inclusive`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L), sample(3000L))
            .forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteRange(ctx, 1000L, 2000L)

        val remaining = SampleStore.readAll(ctx)
        assertEquals(1, remaining.size)
        assertEquals(3000L, remaining[0].timestamp)
    }

    @Test
    fun `deleteUpTo behaves as deleteRange from the beginning of time`() {
        val ctx = context()
        listOf(sample(1000L), sample(2000L), sample(3000L))
            .forEach { SampleStore.append(ctx, it) }

        SampleStore.deleteUpTo(ctx, 2000L)

        val remaining = SampleStore.readAll(ctx)
        assertEquals(1, remaining.size)
        assertEquals(3000L, remaining[0].timestamp)
    }
}

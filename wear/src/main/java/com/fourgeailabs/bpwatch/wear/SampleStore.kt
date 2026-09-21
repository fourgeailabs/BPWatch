package com.fourgeailabs.bpwatch.wear

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-watch store for continuous-recording samples (v2.0).
 *
 * A plain append-only binary file in internal storage — no database needed,
 * and it survives reboots and app updates. Each record is 16 bytes:
 * timestamp (long) + bpm (float) + stress (int). A trailing partial record
 * (crash mid-append) is dropped on read. At one sample per 10 minutes this
 * is ~2.3 KB/day, so even weeks of backlog are trivial.
 *
 * Samples are deleted only after the phone ACKs them (PATH_HISTORY_PUSH_ACK),
 * so nothing is lost when the phone is out of range.
 */
object SampleStore {
    private const val TAG = "SampleStore"
    private const val FILE_NAME = "hr_samples.bin"
    private const val RECORD_BYTES = 16

    data class Sample(val timestamp: Long, val bpm: Float, val stress: Int)

    @Synchronized
    fun append(context: Context, sample: Sample) {
        try {
            val file = file(context)
            file.outputStream().use { outs ->
                val buf = java.nio.ByteBuffer.allocate(RECORD_BYTES)
                buf.putLong(sample.timestamp)
                buf.putFloat(sample.bpm)
                buf.putInt(sample.stress)
                outs.write(buf.array())
            }
        } catch (e: Exception) {
            Log.w(TAG, "append failed", e)
        }
    }

    /** All complete records, oldest first. */
    @Synchronized
    fun readAll(context: Context): List<Sample> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return try {
            val bytes = file.readBytes()
            val count = bytes.size / RECORD_BYTES
            val buf = java.nio.ByteBuffer.wrap(bytes)
            (0 until count).map {
                Sample(
                    timestamp = buf.long,
                    bpm = buf.float,
                    stress = buf.int,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAll failed", e)
            emptyList()
        }
    }

    /**
     * Deletes every sample with timestamp <= [timestamp] by rewriting the
     * file with only newer records. Called when the phone ACKs a batch.
     */
    @Synchronized
    fun deleteUpTo(context: Context, timestamp: Long) {
        try {
            val keep = readAll(context).filter { it.timestamp > timestamp }
            val file = file(context)
            if (keep.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.outputStream().use { outs ->
                val buf = java.nio.ByteBuffer.allocate(RECORD_BYTES * keep.size)
                keep.forEach { s ->
                    buf.putLong(s.timestamp)
                    buf.putFloat(s.bpm)
                    buf.putInt(s.stress)
                }
                outs.write(buf.array())
            }
            tmp.renameTo(file)
            Log.i(TAG, "Pruned to ${keep.size} samples after ack($timestamp)")
        } catch (e: Exception) {
            Log.w(TAG, "deleteUpTo failed", e)
        }
    }

    @Synchronized
    fun count(context: Context): Int {
        val file = file(context)
        return if (file.exists()) (file.length() / RECORD_BYTES).toInt() else 0
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

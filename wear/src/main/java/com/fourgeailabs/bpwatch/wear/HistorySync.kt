package com.fourgeailabs.bpwatch.wear

import android.content.Context
import android.util.Log
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Pushes recorded HR/stress samples to the phone in batches
 * (PATH_HISTORY_PUSH). The phone ACKs each batch (PATH_HISTORY_PUSH_ACK)
 * with the newest timestamp it stored; the watch then prunes everything up
 * to that timestamp. Batches the phone never ACKs stay on the watch and are
 * retried later — nothing is lost when the phone is out of range, and
 * re-sends are idempotent on the phone (REPLACE on the timestamp key).
 */
object HistorySync {
    private const val TAG = "HistorySync"

    /** Samples per PATH_HISTORY_PUSH message — ~3 KB, well within limits. */
    private const val BATCH_SIZE = 200

    /**
     * Push when at least this many samples are waiting, so the radio isn't
     * woken for every single 10-minute tick.
     */
    private const val MIN_BATCH = 6

    /** Called after each recording tick. */
    suspend fun maybeSync(context: Context) {
        val pending = SampleStore.readAll(context)
        if (pending.size < MIN_BATCH) return
        pushBatches(context, pending)
    }

    /** Pushes everything waiting — called on peer connect. */
    suspend fun pushAll(context: Context) {
        val pending = SampleStore.readAll(context)
        if (pending.isEmpty()) return
        Log.i(TAG, "Pushing ${pending.size} pending samples")
        pushBatches(context, pending)
    }

    private suspend fun pushBatches(context: Context, samples: List<SampleStore.Sample>) {
        val nodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (_: Exception) {
            return
        }
        if (nodes.isEmpty()) return
        samples.chunked(BATCH_SIZE).forEach { chunk ->
            val payload = DataMap().apply {
                putLongArray(Link.KEY_HIST_TS, chunk.map { it.timestamp }.toLongArray())
                putFloatArray(Link.KEY_HIST_BPM, chunk.map { it.bpm }.toFloatArray())
                putIntegerArrayList(Link.KEY_HIST_STRESS, ArrayList(chunk.map { it.stress }))
            }.toByteArray()
            nodes.forEach { node ->
                try {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, Link.PATH_HISTORY_PUSH, payload)
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "history push failed", e)
                    return
                }
            }
        }
    }
}

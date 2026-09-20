package com.fourgeailabs.bpwatch.wear

import android.content.Context
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object DataLayer {

    /**
     * Sends an averaged heart-rate reading (plus stress score, -1 if unknown)
     * to every connected node (your Pixel, via the Wear OS companion /
     * Bluetooth link).
     */
    suspend fun sendHrReading(
        context: Context,
        heartRate: Float,
        timestamp: Long,
        stress: Int = -1,
    ): Boolean {
        return try {
            val payload = DataMap().apply {
                putFloat(Link.KEY_HEART_RATE, heartRate)
                putLong(Link.KEY_TIMESTAMP, timestamp)
                putInt(Link.KEY_STRESS, stress)
            }.toByteArray()

            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false

            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, Link.PATH_HR_READING, payload)
                    .await()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

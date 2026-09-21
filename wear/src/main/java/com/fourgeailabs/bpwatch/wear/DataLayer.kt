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

    /**
     * Watch → phone: the user changed the BP-check interval on the watch.
     * The phone persists it so the two stay in sync (the phone re-broadcasts
     * its config on every reading, which would otherwise clobber the watch's
     * choice).
     */
    suspend fun sendIntervalSet(context: Context, intervalMinutes: Int): Boolean {
        val payload = DataMap().apply {
            putInt(Link.KEY_BP_INTERVAL_MIN, intervalMinutes)
        }.toByteArray()
        return sendToAllNodes(context, Link.PATH_INTERVAL_SET, payload)
    }

    /**
     * Watch → phone: live heart-rate tick so the phone can mirror what the
     * watch is showing. Callers throttle this (~10s); the phone also guards
     * against stale data.
     */
    suspend fun sendHrLive(context: Context, heartRate: Float): Boolean {
        val payload = DataMap().apply {
            putFloat(Link.KEY_HEART_RATE, heartRate)
            putLong(Link.KEY_TIMESTAMP, System.currentTimeMillis())
        }.toByteArray()
        return sendToAllNodes(context, Link.PATH_HR_LIVE, payload)
    }

    /**
     * Watch → phone: an alert just fired on the watch. The phone mirrors it
     * as a notification — or a full-screen takeover when [severity] is
     * [Link.Severity.EXTREME].
     */
    suspend fun sendAlert(
        context: Context,
        alertType: String,
        severity: String,
        title: String,
        message: String,
    ): Boolean {
        val payload = DataMap().apply {
            putString(Link.KEY_ALERT_TYPE, alertType)
            putString(Link.KEY_SEVERITY, severity)
            putString(Link.KEY_ALERT_TITLE, title)
            putString(Link.KEY_ALERT_MESSAGE, message)
            putLong(Link.KEY_TIMESTAMP, System.currentTimeMillis())
        }.toByteArray()
        return sendToAllNodes(context, Link.PATH_ALERT, payload)
    }

    private suspend fun sendToAllNodes(
        context: Context,
        path: String,
        payload: ByteArray,
    ): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, path, payload)
                    .await()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

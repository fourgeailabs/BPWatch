package com.fourgeailabs.bpwatch.wear

import android.util.Log
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Receives blood-pressure estimates (and calibration status) back from the
 * phone app so the watch can display the latest result — plus the
 * "Monitoring & alerts" config, which is applied immediately.
 *
 * v1.15+: also the receiving end of the one-tap updater (PATH_APK_BEGIN /
 * PATH_APK_UPDATE) and the settings-sync handshake (watch info, config
 * push/pull on peer connect).
 */
class WatchListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onPeerConnected(node: Node) {
        // Announce ourselves and sync settings both ways: the phone learns
        // our version and our config (which it adopts if it was never
        // configured), and we pull the phone's config + calibration state
        // in case we were just reinstalled or wiped.
        scope.launch {
            try {
                DataLayer.sendWatchInfo(this@WatchListenerService)
            } catch (_: Exception) {
            }
            try {
                DataLayer.sendWatchConfig(this@WatchListenerService)
            } catch (_: Exception) {
            }
            try {
                DataLayer.requestConfig(this@WatchListenerService)
            } catch (_: Exception) {
            }
            // Re-arm recording alarms (they die on reboot/update) and push
            // any samples the phone hasn't ACKed yet.
            try {
                RecordScheduler.ensureScheduled(this@WatchListenerService)
            } catch (_: Exception) {
            }
            try {
                HistorySync.pushAll(this@WatchListenerService)
            } catch (_: Exception) {
            }
            // Report today's step count to the phone (date-keyed; the phone
            // falls back to Health Connect when the watch can't report).
            try {
                StepsReporter.maybeReport(this@WatchListenerService)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != Link.PATH_APK_UPDATE) continue
            val map = try {
                DataMapItem.fromDataItem(event.dataItem).dataMap
            } catch (_: Exception) {
                continue
            }
            scope.launch { handleApkUpdate(map) }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Link.PATH_BP_ESTIMATE -> {
                val map = DataMap.fromByteArray(event.data)
                val sys = map.getInt(Link.KEY_SYS)
                val dia = map.getInt(Link.KEY_DIA)
                val timestamp = map.getLong(Link.KEY_TIMESTAMP)
                WatchState.onEstimate(sys, dia, timestamp)
                // Persist: the estimate may arrive while the UI isn't running
                // (scheduled background checks).
                WatchSettings.saveEstimate(this, sys, dia, timestamp)
                // Blood-pressure alerts fire on every estimate.
                try {
                    AlertManager.checkBloodPressure(this, sys, dia)
                } catch (_: Exception) {
                    // Alerting must never break estimate handling.
                }
            }
            Link.PATH_CALIBRATION -> {
                val map = DataMap.fromByteArray(event.data)
                val calibrated = map.getBoolean(Link.KEY_CALIBRATED)
                WatchState.onCalibration(calibrated)
                WatchSettings.saveCalibrated(this, calibrated)
                if (map.containsKey(Link.KEY_RESTING_HR)) {
                    WatchSettings.saveRestingHr(this, map.getFloat(Link.KEY_RESTING_HR))
                }
            }
            Link.PATH_MONITORING_CONFIG -> {
                val config = try {
                    WatchSettings.parseMonitorConfig(DataMap.fromByteArray(event.data))
                } catch (_: Exception) {
                    return
                }
                WatchSettings.saveMonitorConfig(this, config)
                WatchState.onMonitorConfig(config)
                // Apply immediately: (re)schedule checks, start/stop
                // continuous HR.
                try {
                    CheckScheduler.reschedule(this)
                } catch (_: Exception) {
                }
                try {
                    // May no-op on Android 12+ when the UI is dead (background
                    // foreground-service starts are blocked) — MainActivity
                    // picks it up on next launch; the config is persisted.
                    HrMonitorService.ensureRunning(this)
                } catch (_: Exception) {
                }
            }
            Link.PATH_APK_BEGIN -> {
                scope.launch { handleApkBegin(event) }
            }
            Link.PATH_HR_RECORD_SET -> {
                scope.launch { handleHrRecordSet(event) }
            }
            Link.PATH_HISTORY_PUSH_ACK -> {
                scope.launch { handleHistoryAck(event) }
            }
            Link.PATH_WATCH_INFO_REQUEST -> {
                scope.launch {
                    try {
                        DataLayer.sendWatchInfo(this@WatchListenerService)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // One-tap updater (v1.15+).
    // ------------------------------------------------------------------

    /**
     * The phone asks "I have version X — do you need it?" Reply with our
     * installed version and whether the offered one is newer, so the phone
     * only beams 20 MB over Bluetooth when there's something to install.
     */
    private suspend fun handleApkBegin(event: MessageEvent) {
        var needsUpdate = false
        var offeredName = ""
        try {
            val map = DataMap.fromByteArray(event.data)
            val offeredCode = map.getLong(Link.KEY_APK_VERSION_CODE)
            offeredName = map.getString(Link.KEY_APK_VERSION_NAME).orEmpty()
            val (installedCode, _) = DataLayer.installedVersion(this)
            needsUpdate = offeredCode > installedCode
            Log.i(TAG, "APK begin: offered $offeredName ($offeredCode), " +
                "installed $installedCode, needsUpdate=$needsUpdate")
        } catch (e: Exception) {
            Log.w(TAG, "Bad APK_BEGIN payload", e)
        }
        try {
            val (installedCode, installedName) = DataLayer.installedVersion(this)
            val reply = DataMap().apply {
                putLong(Link.KEY_APK_VERSION_CODE, installedCode)
                putString(Link.KEY_APK_VERSION_NAME, installedName)
                putBoolean(Link.KEY_APK_NEEDS_UPDATE, needsUpdate)
            }.toByteArray()
            Wearable.getMessageClient(this)
                .sendMessage(event.sourceNodeId, Link.PATH_APK_READY, reply)
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "APK_READY reply failed", e)
        }
    }

    /**
     * The APK DataItem arrived. Save the asset to a file, sanity-check the
     * version, and hand it to the self-updater. The system shows the update
     * confirmation — one tap on the watch, no debugging.
     */
    private suspend fun handleApkUpdate(map: DataMap) {
        fun result(status: String, message: String) {
            scope.launch {
                try {
                    val nodes = Wearable.getNodeClient(this@WatchListenerService)
                        .connectedNodes.await()
                    val payload = DataMap().apply {
                        putString(Link.KEY_APK_RESULT, status)
                        putString(Link.KEY_APK_MESSAGE, message)
                    }.toByteArray()
                    nodes.forEach { node ->
                        Wearable.getMessageClient(this@WatchListenerService)
                            .sendMessage(node.id, Link.PATH_APK_RESULT, payload)
                            .await()
                    }
                } catch (_: Exception) {
                }
            }
        }
        try {
            val offeredCode = map.getLong(Link.KEY_APK_VERSION_CODE, 0L)
            val offeredName = map.getString(Link.KEY_APK_VERSION_NAME).orEmpty()
            val asset: Asset = map.getAsset(Link.KEY_APK_ASSET)
                ?: return result("failed", "Update arrived without its file.")
            val (installedCode, _) = DataLayer.installedVersion(this)
            if (offeredCode <= installedCode) {
                Log.i(TAG, "APK update $offeredName not newer than installed; skipping")
                return result("up_to_date", "Watch is already up to date.")
            }
            val outFile = File(cacheDir, "bpwatch-update.apk")
            Wearable.getDataClient(this).getFdForAsset(asset).await()
                .inputStream.use { ins ->
                    outFile.outputStream().use { outs -> ins.copyTo(outs) }
                }
            Log.i(TAG, "APK saved (${outFile.length()} bytes), verifying $offeredName")
            // Integrity check: a ~20 MB Bluetooth transfer can silently
            // corrupt. Skip only when the phone didn't send a hash (older
            // phone build).
            val expectedSha = if (map.containsKey(Link.KEY_APK_SHA256)) {
                map.getString(Link.KEY_APK_SHA256)
            } else {
                null
            }
            if (expectedSha != null) {
                val actualSha = sha256Of(outFile)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    Log.w(TAG, "APK SHA-256 mismatch: expected $expectedSha, got $actualSha")
                    return result(
                        "failed",
                        "Update file was corrupted in transfer — try again.",
                    )
                }
            }
            Log.i(TAG, "APK verified, installing $offeredName")
            val ok = ApkSelfUpdater.installUpdate(this, outFile)
            if (ok) {
                result("installing", "Confirm the update on your watch.")
            } else {
                result("failed", "Couldn't start the installer on the watch.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "APK update failed", e)
            result("failed", "Update failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WatchListenerService"

        /** Hex SHA-256 of a file, streamed so a 20 MB APK is no problem. */
        private fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (ins.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    // ------------------------------------------------------------------
    // Continuous HR + stress recording (v2.0).
    // ------------------------------------------------------------------

    /**
     * The phone toggled continuous recording. The phone is the source of
     * truth — the watch just applies it: persist the flag and arm/cancel
     * the 10-minute sampling alarm.
     */
    private suspend fun handleHrRecordSet(event: MessageEvent) {
        try {
            val enabled = DataMap.fromByteArray(event.data)
                .getBoolean(Link.KEY_HR_RECORD)
            WatchSettings.setRecordHrEnabled(this, enabled)
            if (enabled) {
                RecordScheduler.schedule(this)
            } else {
                RecordScheduler.cancel(this)
            }
            Log.i(TAG, "Continuous recording ${if (enabled) "enabled" else "disabled"} by phone")
        } catch (e: Exception) {
            Log.w(TAG, "Bad HR_RECORD_SET payload", e)
        }
    }

    /**
     * The phone stored a history batch up to KEY_TIMESTAMP — prune those
     * samples so the on-watch store doesn't grow forever.
     */
    private suspend fun handleHistoryAck(event: MessageEvent) {
        try {
            val maxTs = DataMap.fromByteArray(event.data).getLong(Link.KEY_TIMESTAMP)
            if (maxTs > 0) {
                SampleStore.deleteUpTo(this, maxTs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad HISTORY_PUSH_ACK payload", e)
        }
    }
}

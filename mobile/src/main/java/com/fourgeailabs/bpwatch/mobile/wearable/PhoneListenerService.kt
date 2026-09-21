package com.fourgeailabs.bpwatch.mobile.wearable

import com.fourgeailabs.bpwatch.Link
import com.fourgeailabs.bpwatch.mobile.BpRepository
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import com.fourgeailabs.bpwatch.mobile.data.WatchSteps
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs
import com.fourgeailabs.bpwatch.mobile.notifications.NotificationHelper
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

/**
 * Receives heart-rate readings from the Galaxy Watch, runs them through the
 * calibration model to estimate blood pressure, stores the result, sends the
 * estimate back to the watch for display, publishes it to Health Connect,
 * and notifies the user that a new result is ready for review.
 */
class PhoneListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Link.PATH_INTERVAL_SET -> handleIntervalSet(event)
            Link.PATH_HR_READING -> handleHrReading(event)
            Link.PATH_HR_LIVE -> handleHrLive(event)
            Link.PATH_STEPS_DAILY -> handleStepsDaily(event)
            Link.PATH_ALERT -> handleAlert(event)
            Link.PATH_APK_READY -> handleApkReady(event)
            Link.PATH_APK_RESULT -> handleApkResult(event)
            Link.PATH_HISTORY_PUSH -> handleHistoryPush(event)
            Link.PATH_WATCH_INFO -> handleWatchInfo(event)
            Link.PATH_WATCH_CONFIG -> handleWatchConfig(event)
            Link.PATH_CONFIG_REQUEST -> handleConfigRequest(event)
            Link.PATH_BP_RESULT -> handleBpResult(event)
        }
    }

    /**
     * A watch (re)connected — maybe after an update, a reboot, or a
     * reinstall. Push everything it needs to be fully programmed, and ask
     * it to report its version for the updater UI.
     */
    override fun onPeerConnected(node: Node) {
        scope.launch {
            try {
                pushFullSync(node.id)
            } catch (_: Exception) {
            }
            try {
                WatchUpdater.requestWatchInfo(applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pushes everything the watch needs to be fully programmed: monitoring
     * config (only once the user has configured it on the phone — otherwise
     * the watch keeps its own schedule), calibration state, and the
     * resting-HR baseline. Called on peer connect, on config request, and
     * after every heart-rate reading, so a wiped/reinstalled/updated watch
     * re-programs itself within seconds — never by hand.
     */
    private suspend fun pushFullSync(nodeId: String) {
        val repo = BpRepository.get(applicationContext)
        val model = try {
            repo.calibrationModel.first()
        } catch (_: Exception) {
            null
        }
        // Tell the watch whether it's calibrated, plus the resting-HR
        // baseline the watch uses for stress estimation (lowest HR
        // across calibration points).
        try {
            val restingHr = repo.calibrationPoints.first()
                .mapNotNull { it.heartRate }
                .minOrNull()
            val calibratedPayload = DataMap().apply {
                putBoolean(Link.KEY_CALIBRATED, model != null)
                if (restingHr != null) {
                    putFloat(Link.KEY_RESTING_HR, restingHr)
                }
            }.toByteArray()
            Wearable.getMessageClient(applicationContext)
                .sendMessage(nodeId, Link.PATH_CALIBRATION, calibratedPayload)
                .await()
        } catch (_: Exception) {
        }

        // Re-send monitoring settings: only once the user has configured
        // them — otherwise the watch keeps its existing schedule.
        try {
            val monitoringPrefs = MonitoringPrefs(applicationContext)
            if (monitoringPrefs.isConfigured()) {
                WatchConfigSender.sendToNode(
                    applicationContext,
                    nodeId,
                    monitoringPrefs.config.value,
                )
            }
        } catch (_: Exception) {
        }

        // Re-send the continuous-recording toggle too: the phone is the
        // source of truth, so a reconnected/reinstalled watch always
        // converges back to the phone's choice.
        try {
            WatchConfigSender.sendRecordSet(
                applicationContext,
                MonitoringPrefs(applicationContext).recordHr.value,
            )
        } catch (_: Exception) {
        }
    }

    /**
     * The user picked a new BP-check interval on the watch. Persist it so the
     * phone and watch stay in sync.
     */
    private fun handleIntervalSet(event: MessageEvent) {
        scope.launch {
            try {
                val minutes = DataMap.fromByteArray(event.data)
                    .getInt(Link.KEY_BP_INTERVAL_MIN)
                MonitoringPrefs(applicationContext).update {
                    it.copy(bpIntervalMinutes = minutes)
                }
            } catch (_: Exception) {
                // Never crash the listener on a malformed message.
            }
        }
    }

    /**
     * Throttled live heart-rate tick from the watch (manual measurement or
     * continuous monitoring). Updates the in-app live mirror.
     *
     * (H) Also persists the tick into hr_samples so HR Trends populates
     * whenever the watch is streaming, even with the opt-in continuous
     * recording toggle off (that toggle only fills the table via the
     * 10-minute PATH_HISTORY_PUSH batches). Ticks arrive ~every 10s, so
     * writes are throttled to at most one per 60 seconds — minor timestamp
     * overlap with the batch writer is acceptable, no dedupe.
     */
    @Volatile
    private var lastLiveHrPersistedAt = 0L

    private fun handleHrLive(event: MessageEvent) {
        try {
            val map = DataMap.fromByteArray(event.data)
            val hr = map.getFloat(Link.KEY_HEART_RATE)
            WatchLiveState.updateLiveHr(hr)
            if (hr > 0f) {
                val now = System.currentTimeMillis()
                if (now - lastLiveHrPersistedAt >= 60_000L) {
                    lastLiveHrPersistedAt = now
                    scope.launch {
                        try {
                            BpRepository.get(applicationContext).sampleDao.insertHr(
                                listOf(
                                    com.fourgeailabs.bpwatch.mobile.data.HrSample(
                                        timestamp = now,
                                        bpm = hr,
                                        source = "watch",
                                    )
                                )
                            )
                        } catch (_: Exception) {
                            // Never crash the listener on a persistence hiccup.
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Never crash the listener on a malformed message.
        }
    }

    /**
     * The watch reported its daily step total (PATH_STEPS_DAILY, v2.2).
     * Date-keyed upsert; later reports for the same day overwrite. The
     * dashboard falls back to Health Connect steps when no row exists.
     */
    private fun handleStepsDaily(event: MessageEvent) {
        scope.launch {
            try {
                val map = DataMap.fromByteArray(event.data)
                val date = map.getString(Link.KEY_STEP_DATE).orEmpty()
                if (date.isEmpty()) return@launch
                AppDatabase.get(applicationContext).watchStepsDao().upsert(
                    WatchSteps(
                        date = date,
                        steps = map.getLong(Link.KEY_STEPS),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } catch (_: Exception) {
                // Never crash the listener on a malformed message.
            }
        }
    }

    /**
     * An alert fired on the watch. Mirror it in the app's live state and as
     * a phone notification — a full-screen takeover for extreme readings.
     */
    private fun handleAlert(event: MessageEvent) {
        scope.launch {
            try {
                val map = DataMap.fromByteArray(event.data)
                val alert = WatchAlert(
                    type = map.getString(Link.KEY_ALERT_TYPE).orEmpty(),
                    severity = map.getString(Link.KEY_SEVERITY).orEmpty(),
                    title = map.getString(Link.KEY_ALERT_TITLE).orEmpty(),
                    message = map.getString(Link.KEY_ALERT_MESSAGE).orEmpty(),
                    at = map.getLong(Link.KEY_TIMESTAMP),
                )
                if (alert.title.isEmpty()) return@launch
                WatchLiveState.postAlert(alert)
                try {
                    NotificationHelper.notifyWatchAlert(
                        applicationContext,
                        alert,
                    )
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
                // Never crash the listener on a malformed message.
            }
        }
    }

    private fun handleHrReading(event: MessageEvent) {
        scope.launch {
            try {
                val map = DataMap.fromByteArray(event.data)
                val hr = map.getFloat(Link.KEY_HEART_RATE)
                val timestamp = map.getLong(Link.KEY_TIMESTAMP)
                val stressRaw = if (map.containsKey(Link.KEY_STRESS)) {
                    map.getInt(Link.KEY_STRESS)
                } else -1
                val stress = stressRaw.takeIf { it >= 0 }

                val repo = BpRepository.get(applicationContext)
                val id = repo.dao.insert(
                    Reading(
                        timestamp = timestamp,
                        heartRate = hr,
                        stress = stress,
                        source = "watch",
                    )
                )

                val model = repo.calibrationModel.first()
                if (model != null) {
                    val (sys, dia) = CalibrationEngine.estimate(model, hr)
                    repo.dao.update(
                        Reading(
                            id = id,
                            timestamp = timestamp,
                            heartRate = hr,
                            sysEstimate = sys,
                            diaEstimate = dia,
                            stress = stress,
                            source = "watch",
                        )
                    )

                    // Send the estimate back to the watch that sent the reading.
                    val payload = DataMap().apply {
                        putInt(Link.KEY_SYS, sys)
                        putInt(Link.KEY_DIA, dia)
                        putLong(Link.KEY_TIMESTAMP, timestamp)
                    }.toByteArray()
                    try {
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(event.sourceNodeId, Link.PATH_BP_ESTIMATE, payload)
                            .await()
                    } catch (_: Exception) {
                        // Watch may be out of range; the reading is still stored.
                    }

                    // Notify: new result ready for review, with everything
                    // we know at scan time.
                    try {
                        NotificationHelper.notifyEstimateReady(
                            applicationContext,
                            NotificationHelper.ResultDetails(
                                sys = sys,
                                dia = dia,
                                heartRate = hr,
                                stress = stress,
                                timestamp = timestamp,
                            ),
                        )
                    } catch (_: Exception) {
                    }

                    // Publish to Health Connect (best effort).
                    try {
                        val hc = HealthConnectManager(applicationContext)
                        if (hc.isAvailable && hc.hasPermissions()) {
                            hc.writeBloodPressure(sys, dia, Instant.ofEpochMilli(timestamp))
                        }
                    } catch (_: Exception) {
                    }
                }

                // v2.3 phone-triggered BP check: the reading that answers our
                // request ends the Measuring state. Reading timestamps come
                // from the watch clock while requestTs is phone time, so a
                // 5-second grace covers small skews. Placed after the estimate
                // store so the state clears even when the phone isn't
                // calibrated yet (the watch still took the reading).
                if (BpCheckState.status.value is BpCheckState.Status.Measuring &&
                    timestamp >= BpCheckState.requestTs - 5_000
                ) {
                    BpCheckState.toIdle()
                }

                // The watch is fully programmed on every reading (config,
                // calibration, resting HR — covers reconnects, updates and
                // reinstalls). See pushFullSync.
                pushFullSync(event.sourceNodeId)
            } catch (_: Exception) {
                // Never crash the listener on a malformed message.
            }
        }
    }

    // ------------------------------------------------------------------
    // One-tap updater + settings sync (v1.15+).
    // ------------------------------------------------------------------

    /** The watch answered PATH_APK_BEGIN: record its version for the UI. */
    private fun handleApkReady(event: MessageEvent) {
        try {
            val map = DataMap.fromByteArray(event.data)
            val versionCode = map.getLong(Link.KEY_APK_VERSION_CODE)
            val versionName = map.getString(Link.KEY_APK_VERSION_NAME).orEmpty()
            val needsUpdate = map.getBoolean(Link.KEY_APK_NEEDS_UPDATE)
            WatchUpdateState.onWatchInfo(versionCode, versionName)
            if (!needsUpdate) {
                WatchUpdateState.upToDate("$versionName ($versionCode)")
            }
            // When an update IS needed, the updater UI is already watching
            // for the version and proceeds to beam the APK.
        } catch (_: Exception) {
        }
    }

    /**
     * The watch pushed a batch of recorded HR/stress samples
     * (PATH_HISTORY_PUSH). Store them, then ACK the newest timestamp so
     * the watch can prune what arrived. Inserts are idempotent (REPLACE),
     * so a re-sent batch after a lost ACK is harmless.
     */
    private fun handleHistoryPush(event: MessageEvent) {
        scope.launch {
            try {
                val map = DataMap.fromByteArray(event.data)
                val timestamps = map.getLongArray(Link.KEY_HIST_TS) ?: return@launch
                val bpms = map.getFloatArray(Link.KEY_HIST_BPM) ?: return@launch
                val stresses = map.getIntegerArrayList(Link.KEY_HIST_STRESS) ?: return@launch
                if (timestamps.isEmpty() ||
                    timestamps.size != bpms.size ||
                    timestamps.size != stresses.size
                ) {
                    return@launch
                }
                val repo = BpRepository.get(applicationContext)
                repo.insertHistorySamples(
                    hr = timestamps.indices.map { i ->
                        com.fourgeailabs.bpwatch.mobile.data.HrSample(
                            timestamp = timestamps[i],
                            bpm = bpms[i],
                            source = "watch",
                        )
                    },
                    stress = timestamps.indices.map { i ->
                        com.fourgeailabs.bpwatch.mobile.data.StressSample(
                            timestamp = timestamps[i],
                            score = stresses[i],
                        )
                    },
                )
                val ack = DataMap().apply {
                    putLong(Link.KEY_TIMESTAMP, timestamps.max())
                }.toByteArray()
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(event.sourceNodeId, Link.PATH_HISTORY_PUSH_ACK, ack)
                    .await()
            } catch (_: Exception) {
                // The watch keeps the batch and retries later.
            }
        }
    }

    /** The watch reports what happened with the delivered APK. */
    private fun handleApkResult(event: MessageEvent) {        try {
            val map = DataMap.fromByteArray(event.data)
            val message = map.getString(Link.KEY_APK_MESSAGE).orEmpty()
            when (map.getString(Link.KEY_APK_RESULT).orEmpty()) {
                "installing" -> WatchUpdateState.waitingWatch(message)
                "up_to_date" -> WatchUpdateState.upToDate(
                    WatchUpdateState.state.value.watchVersion ?: "",
                )
                "failed" -> WatchUpdateState.error(message)
            }
        } catch (_: Exception) {
        }
    }

    /** The watch reported its installed version. */
    private fun handleWatchInfo(event: MessageEvent) {
        try {
            val map = DataMap.fromByteArray(event.data)
            WatchUpdateState.onWatchInfo(
                map.getLong(Link.KEY_APK_VERSION_CODE),
                map.getString(Link.KEY_APK_VERSION_NAME).orEmpty(),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * The watch pushed its monitoring config on connect. If the phone was
     * reinstalled/wiped (never configured) and the watch has real settings,
     * adopt them — the watch becomes the bridge that saves the user from
     * re-entering everything. Otherwise the phone's config wins and is
     * pushed back so both sides converge.
     */
    private fun handleWatchConfig(event: MessageEvent) {
        scope.launch {
            try {
                val map = DataMap.fromByteArray(event.data)
                if (!map.containsKey(Link.KEY_CONFIG_V)) return@launch
                val watchConfig = MonitoringConfig(
                    continuousHr = map.getBoolean(Link.KEY_CONTINUOUS_HR),
                    hrHighEnabled = map.getBoolean(Link.KEY_HR_HIGH_ENABLED),
                    hrHighThreshold = map.getInt(Link.KEY_HR_HIGH_THRESHOLD, 120),
                    bpIntervalMinutes = map.getInt(Link.KEY_BP_INTERVAL_MIN),
                    bpHighEnabled = map.getBoolean(Link.KEY_BP_HIGH_ENABLED),
                    sysHigh = map.getInt(Link.KEY_SYS_HIGH, 140),
                    diaHigh = map.getInt(Link.KEY_DIA_HIGH, 90),
                    bpLowEnabled = map.getBoolean(Link.KEY_BP_LOW_ENABLED),
                    sysLow = map.getInt(Link.KEY_SYS_LOW, 90),
                    diaLow = map.getInt(Link.KEY_DIA_LOW, 60),
                )
                val prefs = MonitoringPrefs(applicationContext)
                if (!prefs.isConfigured() && watchConfig != MonitoringConfig()) {
                    prefs.update { watchConfig }
                } else if (prefs.isConfigured()) {
                    WatchConfigSender.sendToNode(
                        applicationContext,
                        event.sourceNodeId,
                        prefs.config.value,
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v2.3: the watch answered a phone-triggered BP check. "started" means it
     * is sampling now — Measuring stays on until the HR reading arrives (or
     * the ViewModel's 90-second timeout fires). "failed" carries the detail
     * in KEY_BP_MESSAGE.
     */
    private fun handleBpResult(event: MessageEvent) {
        try {
            if (BpCheckState.status.value !is BpCheckState.Status.Measuring) return
            val map = DataMap.fromByteArray(event.data)
            val result = map.getString(Link.KEY_BP_RESULT).orEmpty()
            val message = map.getString(Link.KEY_BP_MESSAGE).orEmpty()
            when (result) {
                "failed" -> BpCheckState.toFailed(
                    message.ifEmpty { "The watch couldn't take a reading." }
                )
                // "started" — deliberately a no-op: the incoming HR reading
                // ends the Measuring state.
            }
        } catch (_: Exception) {
            // Never crash the listener on a malformed message.
        }
    }

    /** The watch asked for the full config + calibration state. */
    private fun handleConfigRequest(event: MessageEvent) {
        scope.launch {
            try {
                pushFullSync(event.sourceNodeId)
            } catch (_: Exception) {
            }
        }
    }
}

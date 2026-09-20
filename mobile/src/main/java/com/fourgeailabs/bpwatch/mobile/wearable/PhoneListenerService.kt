package com.fourgeailabs.bpwatch.mobile.wearable

import com.fourgeailabs.bpwatch.Link
import com.fourgeailabs.bpwatch.mobile.BpRepository
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.notifications.NotificationHelper
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
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
        if (event.path != Link.PATH_HR_READING) return
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

                    // Latest SpO2 for the notification (best effort).
                    val spo2 = try {
                        val hc = HealthConnectManager(applicationContext)
                        if (hc.isAvailable && hc.hasPermissions()) hc.readLatestSpo2() else null
                    } catch (_: Exception) {
                        null
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
                                spo2 = spo2,
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
                        .sendMessage(event.sourceNodeId, Link.PATH_CALIBRATION, calibratedPayload)
                        .await()
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
                // Never crash the listener on a malformed message.
            }
        }
    }
}

package com.fourgeailabs.bpwatch.wear

import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives blood-pressure estimates (and calibration status) back from the
 * phone app so the watch can display the latest result — plus the
 * "Monitoring & alerts" config, which is applied immediately.
 */
class WatchListenerService : WearableListenerService() {

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
        }
    }
}

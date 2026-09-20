package com.fourgeailabs.bpwatch.wear

import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives blood-pressure estimates (and calibration status) back from the
 * phone app so the watch can display the latest result.
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
                // (hourly background checks).
                WatchSettings.saveEstimate(this, sys, dia, timestamp)
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
        }
    }
}

package com.fourgeailabs.bpwatch.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BpEstimate(val sys: Int, val dia: Int, val timestamp: Long)

/**
 * In-process state shared between WatchListenerService and MainActivity
 * (they run in the same process, so a singleton is enough).
 */
object WatchState {
    private val _lastEstimate = MutableStateFlow<BpEstimate?>(null)
    val lastEstimate: StateFlow<BpEstimate?> = _lastEstimate

    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated

    fun onEstimate(sys: Int, dia: Int, timestamp: Long) {
        _lastEstimate.value = BpEstimate(sys, dia, timestamp)
    }

    fun onCalibration(calibrated: Boolean) {
        _calibrated.value = calibrated
    }

    /**
     * Seed from persisted prefs on UI start. Estimates can arrive while the
     * UI process is dead (hourly background checks), so the persisted copy is
     * the source of truth across restarts.
     */
    fun restoreFromPrefs(context: Context) {
        WatchSettings.loadEstimate(context)?.let { _lastEstimate.value = it }
        _calibrated.value = WatchSettings.isCalibrated(context)
    }
}

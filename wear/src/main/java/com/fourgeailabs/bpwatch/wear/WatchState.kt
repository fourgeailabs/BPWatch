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

    private val _monitorConfig = MutableStateFlow<MonitorConfig?>(null)
    val monitorConfig: StateFlow<MonitorConfig?> = _monitorConfig

    /** Latest live heart-rate sample from continuous monitoring, 0 when off. */
    private val _liveHr = MutableStateFlow(0f)
    val liveHr: StateFlow<Float> = _liveHr

    /**
     * Latest measured HR (manual, phone-triggered or scheduled check),
     * restored from prefs on launch so the home screen never opens empty
     * (v2.3, K). 0 bpm / 0 ts when no measurement has ever been taken.
     */
    private val _latestHrBpm = MutableStateFlow(0f)
    val latestHrBpm: StateFlow<Float> = _latestHrBpm
    private val _latestHrTs = MutableStateFlow(0L)
    val latestHrTs: StateFlow<Long> = _latestHrTs

    /** True while the watch believes it is off-wrist (v2.3). */
    private val _offBody = MutableStateFlow(false)
    val offBody: StateFlow<Boolean> = _offBody

    fun onEstimate(sys: Int, dia: Int, timestamp: Long) {
        _lastEstimate.value = BpEstimate(sys, dia, timestamp)
    }

    fun onLatestHr(bpm: Float, timestamp: Long) {
        _latestHrBpm.value = bpm
        _latestHrTs.value = timestamp
    }

    fun onOffBody(paused: Boolean) {
        _offBody.value = paused
    }

    fun onCalibration(calibrated: Boolean) {
        _calibrated.value = calibrated
    }

    fun onMonitorConfig(config: MonitorConfig) {
        _monitorConfig.value = config
    }

    fun onLiveHr(hr: Float) {
        _liveHr.value = hr
    }

    /**
     * Seed from persisted prefs on UI start. Estimates can arrive while the
     * UI process is dead (scheduled background checks), so the persisted copy is
     * the source of truth across restarts.
     */
    fun restoreFromPrefs(context: Context) {
        WatchSettings.loadEstimate(context)?.let { _lastEstimate.value = it }
        _calibrated.value = WatchSettings.isCalibrated(context)
        _monitorConfig.value = WatchSettings.getMonitorConfig(context)
        // (K) Home shows the latest BP + HR immediately on launch — the
        // stored values render instantly, live updates replace them.
        _latestHrBpm.value = WatchSettings.loadLatestHr(context)
        _latestHrTs.value = WatchSettings.loadLatestHrTs(context)
    }
}

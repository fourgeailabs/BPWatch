package com.fourgeailabs.bpwatch.mobile.wearable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State for a phone-triggered BP check (v2.3).
 *
 * MainViewModel.requestBpCheck() sends PATH_BP_REQUEST over the Data Layer;
 * the watch runs its normal 30-second HR sampling headlessly and answers
 * PATH_BP_RESULT ("started"/"failed"), then the HR reading flows through
 * [PhoneListenerService.handleHrReading] as usual and ends the Measuring
 * state. A 90-second timeout in the ViewModel guards a silent watch.
 *
 * Plain object so the listener service (background coroutine) and the
 * ViewModel/UI can share it safely; the timestamp is volatile so the
 * 90-second timeout can't misfire on a stale value.
 */
object BpCheckState {

    sealed interface Status {
        data object Idle : Status
        data class Measuring(val requestTs: Long) : Status
        data class Failed(val message: String) : Status
        data object NoWatch : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var requestTs: Long = 0L
        internal set

    fun toMeasuring(ts: Long) {
        requestTs = ts
        _status.value = Status.Measuring(ts)
    }

    fun toFailed(message: String) {
        _status.value = Status.Failed(message)
    }

    fun toNoWatch() {
        _status.value = Status.NoWatch
    }

    fun toIdle() {
        _status.value = Status.Idle
    }
}

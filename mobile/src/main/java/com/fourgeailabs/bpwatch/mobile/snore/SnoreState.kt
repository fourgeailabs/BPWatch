package com.fourgeailabs.bpwatch.mobile.snore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory status for the snore-detection feature: surfaced under the
 * Settings toggle (e.g. "Microphone permission needed", "Listening now").
 * Transient by design — it describes the current moment, not persisted state.
 */
object SnoreState {
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    fun post(message: String?) {
        _status.value = message
    }

    fun setListening(value: Boolean) {
        _listening.value = value
    }
}

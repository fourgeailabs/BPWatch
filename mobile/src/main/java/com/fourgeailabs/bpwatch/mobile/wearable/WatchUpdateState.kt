package com.fourgeailabs.bpwatch.mobile.wearable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state for the one-tap watch updater (v1.15+).
 *
 * The flow: phone sends PATH_APK_BEGIN → watch replies PATH_APK_READY →
 * phone beams the APK as a DataItem → watch installs it itself via
 * PackageInstaller and reports back on PATH_APK_RESULT.
 */
object WatchUpdateState {

    enum class Status {
        IDLE,
        /** Asked the watch whether it needs the update; awaiting reply. */
        CHECKING,
        /** Beaming the APK to the watch over the Data Layer. */
        SENDING,
        /** APK delivered; waiting for the user to confirm on the watch. */
        WAITING_WATCH,
        DONE,
        UP_TO_DATE,
        ERROR,
    }

    data class UiState(
        val status: Status = Status.IDLE,
        /** Human-readable detail for the current status. */
        val message: String = "",
        /** Bundled watch APK, e.g. "1.15.0 (19)". Null until read. */
        val bundledVersion: String? = null,
        /** Installed watch version, e.g. "1.14.0 (18)". Null until known. */
        val watchVersion: String? = null,
        /** Bumped every time the watch answers (info or update handshake),
         * so the UI can wait for a *fresh* reply rather than a stale value. */
        val lastReadyAt: Long = 0L,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setBundled(versionCode: Long, versionName: String) {
        _state.value = _state.value.copy(
            bundledVersion = "$versionName ($versionCode)",
        )
    }

    fun onWatchInfo(versionCode: Long, versionName: String) {
        _state.value = _state.value.copy(
            watchVersion = "$versionName ($versionCode)",
            lastReadyAt = System.currentTimeMillis(),
        )
    }

    fun checking(message: String) {
        _state.value = _state.value.copy(status = Status.CHECKING, message = message)
    }

    fun sending(message: String) {
        _state.value = _state.value.copy(status = Status.SENDING, message = message)
    }

    fun waitingWatch(message: String) {
        _state.value = _state.value.copy(status = Status.WAITING_WATCH, message = message)
    }

    fun done(message: String) {
        _state.value = _state.value.copy(status = Status.DONE, message = message)
    }

    fun upToDate(watchVersion: String) {
        _state.value = _state.value.copy(
            status = Status.UP_TO_DATE,
            watchVersion = watchVersion,
            message = "Your watch is already running the latest build.",
        )
    }

    fun error(message: String) {
        _state.value = _state.value.copy(status = Status.ERROR, message = message)
    }

    fun reset() {
        val current = _state.value
        _state.value = UiState(
            bundledVersion = current.bundledVersion,
            watchVersion = current.watchVersion,
        )
    }
}

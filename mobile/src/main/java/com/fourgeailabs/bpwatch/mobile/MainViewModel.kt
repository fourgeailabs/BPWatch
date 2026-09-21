package com.fourgeailabs.bpwatch.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.HealthConnectClient
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs
import com.fourgeailabs.bpwatch.mobile.profile.ProfileStore
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile
import com.fourgeailabs.bpwatch.mobile.wearable.WatchConfigSender
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BpRepository.get(app)
    private val hc = HealthConnectManager(app)
    private val profileStore = ProfileStore(app)
    private val monitoringPrefs = MonitoringPrefs(app)

    val readings: StateFlow<List<Reading>> =
        repo.readings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<Reading?> =
        repo.latest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val calibrationPoints: StateFlow<List<CalibrationPoint>> =
        repo.calibrationPoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calibrationModel: StateFlow<CalibrationModel?> =
        repo.calibrationModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val userProfile: StateFlow<UserProfile> = profileStore.profile
    val monitoringConfig: StateFlow<MonitoringConfig> = monitoringPrefs.config

    var spo2: Int? by mutableStateOf(null)
        private set
    var hcAvailable: Boolean by mutableStateOf(hc.isAvailable)
        private set
    var hcGranted: Boolean by mutableStateOf(false)
        private set
    /** Raw Health Connect status for diagnostics, e.g. "needs Health Connect update". */
    var hcStatusText: String by mutableStateOf(hc.sdkStatusText())
        private set
    /** True when Health Connect needs a Play Store update before it can work. */
    var hcNeedsUpdate: Boolean by mutableStateOf(
        hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    )
        private set
    /** Per-permission Android runtime status, for diagnostics. */
    var hcPermissionDetails: List<String> by mutableStateOf(hc.permissionStatusLines())
        private set
    /**
     * SpO2 diagnostic line for Settings, e.g. "SpO2 records (7d): 14 ·
     * newest 2h ago" or why there are none. Empty when Health Connect isn't
     * readable yet.
     */
    var spo2Diagnostic: String by mutableStateOf("")
        private set
    var latestWatchHr: Float? by mutableStateOf(null)
        private set

    val hcPermissions: Set<String> get() = hc.permissions
    val hcReadPermissions: Set<String> get() = hc.readPermissions

    init {
        refreshHealthConnect()
        refreshLatestWatchHr()
    }

    fun refreshHealthConnect() {
        hcStatusText = hc.sdkStatusText()
        hcNeedsUpdate =
            hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        hcPermissionDetails = hc.permissionStatusLines()
        viewModelScope.launch {
            hcGranted = try {
                hc.isAvailable && hc.hasPermissions()
            } catch (_: Exception) {
                false
            }
            // SpO2 reads only need the read grant — never the BP write one.
            val readGranted = try {
                hc.isAvailable && hc.hasReadPermissions()
            } catch (_: Exception) {
                false
            }
            spo2 = try {
                if (readGranted) hc.readLatestSpo2() else null
            } catch (_: Exception) {
                null
            }
            spo2Diagnostic = try {
                when {
                    !hc.isAvailable -> ""
                    !readGranted -> "SpO2: read permission not granted yet."
                    else -> {
                        val stats = hc.readSpo2Stats()
                        when {
                            stats == null -> "SpO2: query timed out — Health Connect isn't responding."
                            stats.count == 0 -> "SpO2: no records in the last 7 days — " +
                                "check Samsung Health → Settings → Health Connect sharing, " +
                                "and turn on “Blood oxygen during sleep”."
                            else -> "SpO2 records (7d): ${stats.count} · " +
                                "newest ${stats.newest?.let { timeAgo(it) } ?: "?"}"
                        }
                    }
                }
            } catch (_: Exception) {
                "SpO2: couldn't read records."
            }
        }
    }

    fun onHcPermissionResult() = refreshHealthConnect()

    fun refreshLatestWatchHr() {
        viewModelScope.launch {
            latestWatchHr = try {
                repo.dao.latestWatchReading()?.heartRate
            } catch (_: Exception) {
                null
            }
        }
    }

    fun addCalibrationPoint(sys: Int, dia: Int, hr: Float) {
        viewModelScope.launch {
            repo.addCalibrationPoint(CalibrationPoint(hr, sys, dia))
            refreshLatestWatchHr()
        }
    }

    fun removeCalibrationPoint(index: Int) {
        viewModelScope.launch { repo.calibrationStore.removePoint(index) }
    }

    fun clearCalibration() {
        viewModelScope.launch { repo.clearCalibration() }
    }

    fun addManualSpo2(value: Int) {
        viewModelScope.launch {
            repo.addManualSpo2(value)
            spo2 = value
        }
    }

    fun saveProfile(profile: UserProfile) {
        profileStore.save(profile)
    }

    /**
     * Applies a monitoring-settings change, persists it, and pushes it to
     * every connected watch immediately.
     */
    fun updateMonitoring(transform: (MonitoringConfig) -> MonitoringConfig) {
        val updated = monitoringPrefs.update(transform)
        viewModelScope.launch {
            WatchConfigSender.sendToAll(getApplication(), updated)
        }
    }

    /** Re-sends the current monitoring config (e.g. manual refresh). */
    fun pushMonitoringConfig() {
        viewModelScope.launch {
            WatchConfigSender.sendToAll(getApplication(), monitoringPrefs.config.value)
        }
    }

    fun isMonitoringConfigured(): Boolean = monitoringPrefs.isConfigured()

    private fun timeAgo(instant: java.time.Instant): String {
        val mins = java.time.Duration.between(instant, java.time.Instant.now())
            .toMinutes().coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}

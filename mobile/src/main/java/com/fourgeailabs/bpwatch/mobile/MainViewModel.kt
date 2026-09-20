package com.fourgeailabs.bpwatch.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.profile.ProfileStore
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BpRepository.get(app)
    private val hc = HealthConnectManager(app)
    private val profileStore = ProfileStore(app)

    val readings: StateFlow<List<Reading>> =
        repo.readings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<Reading?> =
        repo.latest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val calibrationPoints: StateFlow<List<CalibrationPoint>> =
        repo.calibrationPoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calibrationModel: StateFlow<CalibrationModel?> =
        repo.calibrationModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val userProfile: StateFlow<UserProfile> = profileStore.profile

    var spo2: Int? by mutableStateOf(null)
        private set
    var hcAvailable: Boolean by mutableStateOf(hc.isAvailable)
        private set
    var hcGranted: Boolean by mutableStateOf(false)
        private set
    var latestWatchHr: Float? by mutableStateOf(null)
        private set

    val hcPermissions: Set<String> get() = hc.permissions

    init {
        refreshHealthConnect()
        refreshLatestWatchHr()
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            hcGranted = try {
                hc.isAvailable && hc.hasPermissions()
            } catch (_: Exception) {
                false
            }
            spo2 = try {
                if (hcGranted) hc.readLatestSpo2() else null
            } catch (_: Exception) {
                null
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
}

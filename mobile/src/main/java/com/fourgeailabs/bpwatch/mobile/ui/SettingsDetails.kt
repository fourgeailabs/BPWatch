package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepDiagnosis

/**
 * v2.4.0: Settings is now a hub of clickable cards; each section below is
 * the detail screen a card opens. They reuse the same section cards the
 * old inline Settings used, so nothing was re-implemented.
 */

@Composable
private fun SettingsDetailColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun BodyProfileSettingsScreen(viewModel: MainViewModel) {
    val profile by viewModel.userProfile.collectAsState()
    SettingsDetailColumn {
        ProfileCard(profile = profile, onSave = { viewModel.saveProfile(it) })
    }
}

@Composable
fun ConnectionsSettingsScreen(
    viewModel: MainViewModel,
    onRequestHealthConnectPermissions: (Set<String>) -> Unit,
) {
    SettingsDetailColumn {
        SamsungHealthCard(
            hcAvailable = viewModel.hcAvailable,
            hcGranted = viewModel.hcGranted,
            hcStatusText = viewModel.hcStatusText,
            hcNeedsUpdate = viewModel.hcNeedsUpdate,
            hcGrantedSet = viewModel.hcGrantedSet,
            hcPermissions = viewModel.hcPermissions,
            hcReadPermissions = viewModel.hcReadPermissions,
            onRequestPermissions = onRequestHealthConnectPermissions,
        )
    }
}

@Composable
fun MonitoringSettingsScreen(viewModel: MainViewModel) {
    val monitoring by viewModel.monitoringConfig.collectAsState()
    val recordHr by viewModel.recordHr.collectAsState()
    SettingsDetailColumn {
        MonitoringCard(
            config = monitoring,
            onUpdate = { viewModel.updateMonitoring(it) },
            recordHr = recordHr,
            onRecordHrChange = { viewModel.setRecordHr(it) },
        )
    }
}

@Composable
fun SleepSettingsScreen(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit,
    onDiagnoseSleep: suspend () -> SleepDiagnosis,
) {
    val snoreEnabled by viewModel.snoreEnabled.collectAsState()
    val snoreStatus by viewModel.snoreStatus.collectAsState()
    val snoreListening by viewModel.snoreListening.collectAsState()
    SettingsDetailColumn {
        SnoreCard(
            enabled = snoreEnabled,
            listening = snoreListening,
            status = snoreStatus,
            onToggle = { viewModel.onSnoreToggle(it) },
            onStartNow = { viewModel.startSnoreNow() },
            onRequestMicPermission = onRequestMicPermission,
            isMicGranted = { viewModel.isMicGranted() },
        )
        SleepDiagnosticsCard(onDiagnose = onDiagnoseSleep)
    }
}

@Composable
fun CalibrationSettingsScreen(viewModel: MainViewModel) {
    val model by viewModel.calibrationModel.collectAsState()
    val points by viewModel.calibrationPoints.collectAsState()
    SettingsDetailColumn {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val m = model
                Text(
                    text = if (m != null)
                        "Calibrated with ${m.points} points. " +
                            "Systolic ≈ ${"%.2f".format(m.aSys)}×HR ${"%+.1f".format(m.bSys)}; " +
                            "diastolic ≈ ${"%.2f".format(m.aDia)}×HR ${"%+.1f".format(m.bDia)}."
                    else
                        "Not calibrated yet — ${CalibrationEngine.MIN_POINTS - points.size} more " +
                            "cuff readings needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (points.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.clearCalibration() }) {
                        Text("Clear calibration")
                    }
                }
            }
        }
    }
}

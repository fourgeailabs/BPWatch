package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onRequestHealthConnectPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val model by viewModel.calibrationModel.collectAsState()
    val points by viewModel.calibrationPoints.collectAsState()
    val profile by viewModel.userProfile.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        ProfileCard(profile = profile, onSave = { viewModel.saveProfile(it) })

        SamsungHealthCard(
            hcAvailable = viewModel.hcAvailable,
            hcGranted = viewModel.hcGranted,
            onRequestPermissions = onRequestHealthConnectPermissions,
            onRefresh = { viewModel.refreshHealthConnect() },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calibration", style = MaterialTheme.typography.titleSmall)
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Important", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "BPWatch is a personal wellness tool, not a medical device. " +
                        "Blood pressure here is estimated from heart rate using your own " +
                        "cuff calibration — it is not a measurement. Never use it to " +
                        "diagnose, treat, or adjust medication. When in doubt, use a cuff.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "BPWatch 9.0.0 — built for Galaxy Watch Ultra + Pixel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(profile: UserProfile, onSave: (UserProfile) -> Unit) {
    var height by remember(profile) { mutableStateOf(profile.heightCm?.toString() ?: "") }
    var weight by remember(profile) { mutableStateOf(profile.weightKg?.toString() ?: "") }
    var age by remember(profile) { mutableStateOf(profile.age?.toString() ?: "") }
    var sex by remember(profile) { mutableStateOf(profile.sex ?: "") }
    var sexExpanded by remember { mutableStateOf(false) }
    val sexOptions = listOf("Female", "Male", "Other", "Prefer not to say")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body profile", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Height, weight, age and sex — used for BMI and health context. " +
                    "Your BP estimate comes from your own cuff calibration, so your " +
                    "physiology is already baked in; this just rounds out the picture.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Age") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                ExposedDropdownMenuBox(
                    expanded = sexExpanded,
                    onExpandedChange = { sexExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = sex,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sex") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sexExpanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = sexExpanded,
                        onDismissRequest = { sexExpanded = false },
                    ) {
                        sexOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    sex = option
                                    sexExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            val preview = UserProfile(
                heightCm = height.toFloatOrNull(),
                weightKg = weight.toFloatOrNull(),
                age = age.toIntOrNull(),
                sex = sex.ifBlank { null },
            )
            preview.bmi?.let { bmi ->
                Text(
                    text = "BMI: ${"%.1f".format(bmi)} (${preview.bmiLabel})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = {
                    onSave(preview)
                },
            ) {
                Text("Save profile")
            }
        }
    }
}

@Composable
private fun SamsungHealthCard(
    hcAvailable: Boolean,
    hcGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val hcInstalled = remember {
        HealthConnectManager.isHealthConnectInstalled(context)
    }
    val samsungInstalled = remember {
        HealthConnectManager.isSamsungHealthInstalled(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Samsung Health", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "SpO2 and other watch data reach BPWatch through Samsung Health → " +
                    "Health Connect. Two steps:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "1. In Samsung Health: Settings → Health Connect → allow sharing.\n" +
                    "2. Below: grant BPWatch permission to read Health Connect.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when {
                    hcGranted -> "Status: connected ✓"
                    !hcInstalled -> "Status: Health Connect isn't installed."
                    !hcAvailable -> "Status: Health Connect isn't available on this phone."
                    else -> "Status: not connected"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (samsungInstalled) {
                OutlinedButton(
                    onClick = { HealthConnectManager.openSamsungHealth(context) },
                ) {
                    Text("Open Samsung Health")
                }
            }
            if (!hcInstalled) {
                Button(
                    onClick = { HealthConnectManager.openHealthConnectInPlayStore(context) },
                ) {
                    Text("Install Health Connect")
                }
            } else if (!hcGranted) {
                Button(onClick = onRequestPermissions) {
                    Text("Connect Health Connect")
                }
                OutlinedButton(
                    onClick = { HealthConnectManager.openHealthConnectSettings(context) },
                ) {
                    Text("Health Connect settings")
                }
            }
            if (hcGranted) {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh SpO2 now")
                }
            }
        }
    }
}

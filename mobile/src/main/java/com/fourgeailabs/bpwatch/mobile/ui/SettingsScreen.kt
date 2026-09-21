package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.fourgeailabs.bpwatch.BuildConfig
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onRequestHealthConnectPermissions: (Set<String>) -> Unit,
) {
    val model by viewModel.calibrationModel.collectAsState()
    val points by viewModel.calibrationPoints.collectAsState()
    val profile by viewModel.userProfile.collectAsState()
    val monitoring by viewModel.monitoringConfig.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SettingsSection(title = "Body profile", icon = Icons.Filled.Person) {
            ProfileCard(profile = profile, onSave = { viewModel.saveProfile(it) })
        }

        SettingsSection(title = "Connections", icon = Icons.Filled.Favorite) {
            SamsungHealthCard(
                hcAvailable = viewModel.hcAvailable,
                hcGranted = viewModel.hcGranted,
                hcStatusText = viewModel.hcStatusText,
                hcNeedsUpdate = viewModel.hcNeedsUpdate,
                hcPermissionDetails = viewModel.hcPermissionDetails,
                spo2Diagnostic = viewModel.spo2Diagnostic,
                hcPermissions = viewModel.hcPermissions,
                hcReadPermissions = viewModel.hcReadPermissions,
                onRequestPermissions = onRequestHealthConnectPermissions,
                onRefresh = { viewModel.refreshHealthConnect() },
            )
        }

        SettingsSection(title = "Monitoring & alerts", icon = Icons.Filled.MonitorHeart) {
            MonitoringCard(
                config = monitoring,
                onUpdate = { viewModel.updateMonitoring(it) },
            )
        }

        SettingsSection(title = "Calibration", icon = Icons.Filled.Tune) {
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

        SettingsSection(title = "About", icon = Icons.Filled.Info) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Important",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = "BPWatch is a personal wellness tool, not a medical device. " +
                            "Blood pressure here is estimated from heart rate using your own " +
                            "cuff calibration — it is not a measurement. Never use it to " +
                            "diagnose, treat, or adjust medication. When in doubt, use a cuff.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "BPWatch ${BuildConfig.VERSION_NAME} — built for Galaxy Watch Ultra + Pixel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
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

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "BMI: ${"%.1f".format(bmi)} (${preview.bmiLabel})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
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
    hcStatusText: String,
    hcNeedsUpdate: Boolean,
    hcPermissionDetails: List<String>,
    spo2Diagnostic: String,
    hcPermissions: Set<String>,
    hcReadPermissions: Set<String>,
    onRequestPermissions: (Set<String>) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val hcInstalled = remember {
        HealthConnectManager.isHealthConnectInstalled(context)
    }
    val samsungInstalled = remember {
        HealthConnectManager.isSamsungHealthInstalled(context)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon = Icons.Filled.Favorite, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Samsung Health", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "SpO2 and watch data via Samsung Health → Health Connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "1. In Samsung Health: Settings → Health Connect → allow sharing.\n" +
                    "2. Below: grant BPWatch permission to read Health Connect.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Connection status row.
            val statusText = when {
                hcGranted -> "Status: connected ✓"
                !hcInstalled -> "Status: Health Connect isn't installed."
                hcNeedsUpdate -> "Status: Health Connect needs an update before apps can use it."
                !hcAvailable -> "Status: Health Connect $hcStatusText."
                else -> "Status: not connected (Health Connect $hcStatusText)"
            }
            ListItem(
                headlineContent = {
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                },
                leadingContent = {
                    Icon(
                        if (hcGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (hcGranted)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )

            // Per-permission diagnostics: shows exactly what Android thinks is granted.
            if (hcPermissionDetails.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        hcPermissionDetails.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // SpO2 diagnostic: record count from the last 7 days, or why
            // there are none. Updates on every refresh.
            if (spo2Diagnostic.isNotEmpty()) {
                Text(
                    text = spo2Diagnostic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (samsungInstalled) {
                OutlinedButton(
                    onClick = { HealthConnectManager.openSamsungHealth(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Samsung Health")
                }
            }
            if (!hcInstalled || hcNeedsUpdate) {
                Button(
                    onClick = { HealthConnectManager.openHealthConnectInPlayStore(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (hcNeedsUpdate) "Update Health Connect" else "Install Health Connect")
                }
            } else if (!hcGranted) {
                Button(
                    onClick = { onRequestPermissions(hcPermissions) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect Health Connect")
                }
                OutlinedButton(
                    onClick = { onRequestPermissions(hcReadPermissions) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Try read-only request")
                }
                OutlinedButton(
                    onClick = {
                        val ok = HealthConnectManager.openAppHealthPermissions(context)
                        Toast.makeText(
                            context,
                            if (ok) "Opening BPWatch's Health Connect toggles — " +
                                "switch them on, then come back and tap Refresh below."
                            else "Couldn't open Health Connect settings.",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant permissions manually")
                }
                OutlinedButton(
                    onClick = { HealthConnectManager.openHealthConnectSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Health Connect settings")
                }
            }
            if (hcGranted) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Refresh SpO2 now")
                }
            }
        }
    }
}

/**
 * "Monitoring & alerts" — tells the watch what to keep an eye on. Every
 * change is pushed to the watch immediately (see MainViewModel.updateMonitoring).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitoringCard(
    config: MonitoringConfig,
    onUpdate: ((MonitoringConfig) -> MonitoringConfig) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon = Icons.Filled.MonitorHeart, contentDescription = null)
                Text(
                    "Your watch does the measuring — these settings tell it what " +
                        "to watch for. Changes are sent to your watch straight away.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            SwitchRow(
                headline = "Continuous heart rate",
                subtitle = "Keeps the heart-rate sensor on all day. Uses noticeably more battery.",
                checked = config.continuousHr,
                onCheckedChange = { checked -> onUpdate { cfg -> cfg.copy(continuousHr = checked) } },
            )

            MonitoringSubHeader("Heart-rate alert")
            SwitchRow(
                headline = "High heart-rate alert",
                subtitle = "Buzz your watch when your heart rate goes over the limit.",
                checked = config.hrHighEnabled,
                onCheckedChange = { enabled -> onUpdate { cfg -> cfg.copy(hrHighEnabled = enabled) } },
            )
            if (config.hrHighEnabled) {
                ThresholdField(
                    label = "Alert above",
                    unit = "bpm",
                    value = config.hrHighThreshold,
                    range = MonitoringConfig.HR_THRESHOLD_MIN..MonitoringConfig.HR_THRESHOLD_MAX,
                    onCommit = { value -> onUpdate { cfg -> cfg.copy(hrHighThreshold = value) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            MonitoringSubHeader("Blood-pressure checks")
            var freqExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = freqExpanded,
                onExpandedChange = { freqExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = MonitoringConfig.intervalLabel(config.bpIntervalMinutes),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Check frequency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(freqExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = freqExpanded,
                    onDismissRequest = { freqExpanded = false },
                ) {
                    MonitoringConfig.INTERVAL_OPTIONS.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(MonitoringConfig.intervalLabel(minutes)) },
                            onClick = {
                                onUpdate { it.copy(bpIntervalMinutes = minutes) }
                                freqExpanded = false
                            },
                        )
                    }
                }
            }
            Text(
                "Each check samples your heart rate in the background and " +
                    "estimates blood pressure from your calibration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MonitoringSubHeader("Blood-pressure alerts")
            SwitchRow(
                headline = "High blood-pressure alert",
                subtitle = "Buzz when systolic or diastolic reaches your high limit.",
                checked = config.bpHighEnabled,
                onCheckedChange = { enabled -> onUpdate { cfg -> cfg.copy(bpHighEnabled = enabled) } },
            )
            if (config.bpHighEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdField(
                        label = "Sys ≥",
                        unit = "mmHg",
                        value = config.sysHigh,
                        range = MonitoringConfig.BP_SYS_MIN..MonitoringConfig.BP_SYS_MAX,
                        onCommit = { value -> onUpdate { cfg -> cfg.copy(sysHigh = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThresholdField(
                        label = "Dia ≥",
                        unit = "mmHg",
                        value = config.diaHigh,
                        range = MonitoringConfig.BP_DIA_MIN..MonitoringConfig.BP_DIA_MAX,
                        onCommit = { value -> onUpdate { cfg -> cfg.copy(diaHigh = value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SwitchRow(
                headline = "Low blood-pressure alert",
                subtitle = "Buzz when systolic or diastolic drops to your low limit.",
                checked = config.bpLowEnabled,
                onCheckedChange = { enabled -> onUpdate { cfg -> cfg.copy(bpLowEnabled = enabled) } },
            )
            if (config.bpLowEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdField(
                        label = "Sys ≤",
                        unit = "mmHg",
                        value = config.sysLow,
                        range = MonitoringConfig.BP_SYS_MIN..MonitoringConfig.BP_SYS_MAX,
                        onCommit = { value -> onUpdate { cfg -> cfg.copy(sysLow = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThresholdField(
                        label = "Dia ≤",
                        unit = "mmHg",
                        value = config.diaLow,
                        range = MonitoringConfig.BP_DIA_MIN..MonitoringConfig.BP_DIA_MAX,
                        onCommit = { value -> onUpdate { cfg -> cfg.copy(diaLow = value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Alerts buzz on your watch and show the reading that tripped them. " +
                    "Each alert type waits 15 minutes before buzzing again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonitoringSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    headline: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A numeric threshold field that commits (validates + clamps) when the user
 * taps Done or moves focus away.
 */
@Composable
private fun ThresholdField(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun commit() {
        val parsed = text.toIntOrNull()?.coerceIn(range) ?: value
        text = parsed.toString()
        if (parsed != value) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
        label = { Text(label) },
        suffix = { Text(unit) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        singleLine = true,
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}

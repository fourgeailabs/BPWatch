package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
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
import androidx.compose.material3.TextButton
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
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepDiagnosis
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile
import com.fourgeailabs.bpwatch.mobile.snore.SnoreScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenWatch: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenMonitoring: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    // v2.4.0: Settings is a hub — every section is a clickable card that
    // opens that section's own screen (back button returns here).
    val sections = listOf(
        Quad(Icons.Filled.Watch, "Watch app", "Install and update the watch app", onOpenWatch),
        Quad(Icons.Filled.Person, "Body profile", "Height, weight, age, sex and BMI", onOpenProfile),
        Quad(Icons.Filled.Favorite, "Connections", "Samsung Health and Health Connect", onOpenConnections),
        Quad(Icons.Filled.MonitorHeart, "Monitoring & alerts", "Check schedule, thresholds, alerts", onOpenMonitoring),
        Quad(Icons.Filled.Bedtime, "Sleep", "Snore detection and sleep data", onOpenSleep),
        Quad(Icons.Filled.Tune, "Calibration", "Cuff readings and model status", onOpenCalibration),
        Quad(Icons.Filled.Info, "About", "Version, credits and links", onOpenAbout),
        Quad(Icons.Filled.NewReleases, "What's new", "Release history, newest first", onOpenChangelog),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        sections.forEach { (icon, title, subtitle, onClick) ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** One row of the Settings hub. */
private data class Quad(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileCard(profile: UserProfile, onSave: (UserProfile) -> Unit) {
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
fun SamsungHealthCard(
    hcAvailable: Boolean,
    hcGranted: Boolean,
    hcStatusText: String,
    hcNeedsUpdate: Boolean,
    hcGrantedSet: Set<String>,
    hcPermissions: Set<String>,
    hcReadPermissions: Set<String>,
    onRequestPermissions: (Set<String>) -> Unit,
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
                        "Watch data via Samsung Health → Health Connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "1. In Samsung Health: Settings → Health Connect → allow sharing " +
                    "(Sleep has its own toggle there — it must be on too).\n" +
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

            // Per-permission status: which declared permissions Android
            // reports as granted, each with its own re-request button.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                hcPermissions.sorted().forEach { perm ->
                    val granted = perm in hcGrantedSet
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = perm.substringAfterLast('.'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (granted) "granted" else "not granted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        if (!granted) {
                            TextButton(onClick = { onRequestPermissions(setOf(perm)) }) {
                                Text("Request")
                            }
                        }
                    }
                }
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
            // v2.3.2: one-tap re-request even when everything looks granted —
            // covers silently-revoked or stuck grants without a Settings hunt.
            // The result toast + refreshed list below confirm what changed.
            if (hcGranted && hcInstalled && !hcNeedsUpdate) {
                OutlinedButton(
                    onClick = { onRequestPermissions(hcPermissions) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-request permissions")
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
fun MonitoringCard(
    config: MonitoringConfig,
    onUpdate: ((MonitoringConfig) -> MonitoringConfig) -> Unit,
    recordHr: Boolean,
    onRecordHrChange: (Boolean) -> Unit,
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

            SwitchRow(
                headline = "Record heart rate continuously",
                subtitle = "Samples heart rate and stress every 10 minutes for your Trends " +
                    "graphs. Off by default — it uses more battery.",
                checked = recordHr,
                onCheckedChange = onRecordHrChange,
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
fun MonitoringSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(
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
fun ThresholdField(
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

/**
 * Snore detection (v2.3): opt-in overnight listening on the phone
 * microphone. The copy is explicit that the microphone records overnight
 * and that it costs extra battery, and that clips stay on the phone.
 */
@Composable
fun SnoreCard(
    enabled: Boolean,
    listening: Boolean,
    status: String?,
    onToggle: (Boolean) -> Unit,
    onStartNow: () -> Unit,
    onRequestMicPermission: () -> Unit,
    isMicGranted: () -> Boolean,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon = Icons.Filled.Bedtime, contentDescription = null)
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Snore detection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Listens with the microphone from 22:00 to 07:00. " +
                            "Uses extra battery overnight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (want && !isMicGranted()) {
                            onRequestMicPermission()
                        } else {
                            onToggle(want)
                        }
                    },
                )
            }
            if (enabled && !listening) {
                // Manual start only makes sense inside the overnight window;
                // outside it the status line already says when listening begins.
                val inWindow = remember { SnoreScheduler.inWindow() }
                if (inWindow) {
                    OutlinedButton(onClick = onStartNow, modifier = Modifier.fillMaxWidth()) {
                        Text("Start listening now")
                    }
                }
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Clips stay on this phone — nothing is sent or uploaded anywhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

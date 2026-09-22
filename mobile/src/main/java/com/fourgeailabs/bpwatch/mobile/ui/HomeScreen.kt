package com.fourgeailabs.bpwatch.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.DashboardMetrics
import com.fourgeailabs.bpwatch.mobile.HealthLogKind
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.wearable.BpCheckState
import com.fourgeailabs.bpwatch.mobile.wearable.WatchLiveState
import java.util.Locale

private val Navy = Color(0xFF0A1A33)
private val Crimson = Color(0xFFDC143C)
private val LiveGreen = Color(0xFF34A853)

/**
 * v2.4.1 Home: a Google Health-style dashboard. BP estimate stays the hero,
 * below it the day's real metrics as tiles, then snore and calibrate cards.
 * The readings timeline lives on the History tab, not here.
 * British spelling throughout, no em dashes in copy.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenCalibrate: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrends: (TrendMetric) -> Unit,
    onOpenSnore: () -> Unit,
) {
    val readings by viewModel.readings.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    var showLogSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshDashboard() }

    val latest = readings.firstOrNull { it.sysEstimate != null || it.sysCuff != null }
    val sys = latest?.sysEstimate ?: latest?.sysCuff
    val dia = latest?.diaEstimate ?: latest?.diaCuff
    val estimated = latest?.sysEstimate != null
    // v2.3 phone-triggered BP check state.
    val bpStatus by BpCheckState.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Centred wordmark, like the reference layout.
        Text(
            "BPWatch",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- BP hero: deep navy card, crimson heart, white ECG line identity.
        // Tapping jumps to the blood-pressure trend.
        Card(
            colors = CardDefaults.cardColors(containerColor = Navy),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenTrends(TrendMetric.BLOOD_PRESSURE) },
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(
                    Icons.Filled.MonitorHeart,
                    contentDescription = null,
                    size = 56.dp,
                    containerColor = Crimson,
                    contentColor = Color.White,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "BP estimate",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        if (sys != null && dia != null) "$sys / $dia" else "No data",
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White,
                    )
                    Text(
                        "mmHg · " + if (sys != null) {
                            if (estimated) "Estimated" else "Cuff reading"
                        } else {
                            "Calibrate to begin"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    // v2.3 phone-triggered BP check: asks the watch to sample
                    // now, over the Data Layer.
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.requestBpCheck() },
                        // Retry stays available after a failure or when no
                        // watch is connected; only a live check disables it.
                        enabled = bpStatus !is BpCheckState.Status.Measuring,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.6f),
                        ),
                    ) {
                        Text(
                            if (bpStatus is BpCheckState.Status.Measuring) "Measuring…"
                            else "Check now"
                        )
                    }
                    val bpErrorText = when (bpStatus) {
                        is BpCheckState.Status.Failed ->
                            (bpStatus as BpCheckState.Status.Failed).message
                        is BpCheckState.Status.NoWatch -> "No watch connected."
                        else -> null
                    }
                    if (bpErrorText != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bpErrorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        // --- Primary actions.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { showLogSheet = true },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Log")
            }
            Button(
                onClick = onOpenWatch,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
        }

        if (!dashboard.hcReadGranted) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(Icons.Filled.Info, contentDescription = null, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tiles are empty", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Connect Health Connect to fill them with your real data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        }

        // --- Metric grid: real data only, nothing invented.
        MetricGrid(dashboard = dashboard, onOpenTrends = onOpenTrends)

        // --- Snoring card (v2.3): last night's 22:00–07:00 snore count.
        // "No data" when none; tap opens the Snore detail screen.
        val snoreCount = viewModel.lastNightSnoreCount
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSnore),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(icon = Icons.Filled.Bedtime, contentDescription = null)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text("Snoring", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = when {
                            snoreCount == null -> "No data"
                            snoreCount > 0 -> "$snoreCount last night"
                            else -> "No data"
                        },
                        style = MaterialTheme.typography.bodyMedium,
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

        // --- Slim calibrate entry.
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCalibrate),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(Icons.Filled.Tune, contentDescription = null, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Calibrate", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep estimates accurate with your cuff",
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

        Text(
            "BPWatch gives wellness estimates from your own cuff calibration. " +
                "It is not a medical device. Check with a cuff before making health decisions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    if (showLogSheet) {
        LogSheet(
            viewModel = viewModel,
            onDismiss = { showLogSheet = false },
        )
    }
}

/** Two-column grid of health tiles. Tapping a tile opens its Trends graph. */
@Composable
private fun MetricGrid(
    dashboard: DashboardMetrics,
    onOpenTrends: (TrendMetric) -> Unit,
) {
    val liveHr by WatchLiveState.liveHr.collectAsState()
    val liveHrAt by WatchLiveState.liveHrAt.collectAsState()
    val liveFresh = liveHr != null && liveHrAt > 0L && WatchLiveState.isLiveHrFresh()

    val stepsText = dashboard.steps?.let { "%,d".format(Locale.US, it) }
    val distanceText = dashboard.distanceMi?.let { "%.1f mi".format(Locale.US, it) }
    val caloriesText = dashboard.caloriesKcal?.let { "${it.toInt()} kcal" }
    val hrText = when {
        liveFresh -> "${liveHr!!.toInt()} bpm"
        dashboard.heartRateBpm != null -> "${dashboard.heartRateBpm} bpm"
        else -> null
    }
    val weightText = dashboard.weightLb?.let {
        if (it == it.toLong().toDouble()) "${it.toLong()} lb" else "%.1f lb".format(Locale.US, it)
    }
    val sleepText = dashboard.sleepHours?.let {
        val h = it.toInt()
        val m = ((it - h) * 60).toInt()
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    // v2.3 stress tile: 0-100 scale, explicit in the label, never a %.
    val stressText = dashboard.stress?.let { "$it" }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthTile(Icons.Filled.DirectionsWalk, "Steps", stepsText, Color(0xFF1A73E8)) {
                onOpenTrends(TrendMetric.STEPS)
            }
            HealthTile(Icons.Filled.Place, "Distance", distanceText, Color(0xFF9334E6)) {
                onOpenTrends(TrendMetric.DISTANCE)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthTile(Icons.Filled.LocalFireDepartment, "Calories", caloriesText, Color(0xFFEA8600)) {
                onOpenTrends(TrendMetric.CALORIES)
            }
            HealthTile(Icons.Filled.Favorite, "Heart rate", hrText, Color(0xFFD93025), live = liveFresh) {
                onOpenTrends(TrendMetric.HEART_RATE)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthTile(Icons.Filled.MonitorWeight, "Weight", weightText, Color(0xFF0B8043)) {
                onOpenTrends(TrendMetric.WEIGHT)
            }
            HealthTile(Icons.Filled.Bedtime, "Sleep", sleepText, Color(0xFF3949AB)) {
                onOpenTrends(TrendMetric.SLEEP)
            }
        }
        // v2.3: hydration and discontinued tiles removed from the grid (the
        // hydration Health Connect read and Trends metric stay). Stress takes
        // the final slot, deep-linking into its Trends graph.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthTile(
                Icons.Filled.Scale,
                "BMI",
                dashboard.bmi?.let {
                    "%.1f".format(it) + (dashboard.bmiLabel?.let { l -> " · $l" } ?: "")
                },
                Color(0xFF5E35B1),
            ) {
                onOpenTrends(TrendMetric.BMI)
            }
            HealthTile(
                Icons.Filled.Psychology,
                "Stress · 0–100",
                stressText,
                Color(0xFF7B1FA2),
            ) {
                onOpenTrends(TrendMetric.STRESS)
            }
        }
    }
}

/**
 * One metric tile. Tiles with data get a tinted card; empty ones sit back
 * on surfaceContainerHigh. Nothing is ever faked. Tapping opens the tile's
 * Trends graph.
 *
 * Contrast note: a filled tile uses tertiaryContainer, so its text must use
 * onTertiaryContainer — the old onSurface/onSurfaceVariant pairing went
 * unreadable under dynamic-colour dark themes where tertiaryContainer
 * renders light.
 */
@Composable
private fun RowScope.HealthTile(
    icon: ImageVector,
    label: String,
    value: String?,
    accent: Color,
    live: Boolean = false,
    onClick: () -> Unit,
) {
    val hasData = value != null
    ElevatedCard(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (hasData) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TintedIcon(
                    icon,
                    contentDescription = null,
                    size = 40.dp,
                    containerColor = accent.copy(alpha = 0.16f),
                    contentColor = accent,
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(LiveGreen)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (hasData) MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value ?: "No data",
                style = MaterialTheme.typography.titleLarge,
                color = if (hasData) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------
// "+ Log" bottom sheet: weight, hydration, food -> Health Connect + Room.
// ------------------------------------------------------------------

private enum class LogMode { WEIGHT, HYDRATION, FOOD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<LogMode?>(null) }

    fun saved(message: String, hcOk: Boolean) {
        Toast.makeText(
            context,
            if (hcOk) message else "$message (could not sync to Health Connect)",
            Toast.LENGTH_LONG,
        ).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (mode) {
                        null -> "Log"
                        LogMode.WEIGHT -> "Log weight"
                        LogMode.HYDRATION -> "Log hydration"
                        LogMode.FOOD -> "Log food"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (mode != null) {
                    IconButton(onClick = { mode = null }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            }

            when (mode) {
                null -> {
                    LogChoiceRow(Icons.Filled.MonitorWeight, "Weight", "Body weight in lb") {
                        mode = LogMode.WEIGHT
                    }
                    LogChoiceRow(Icons.Filled.WaterDrop, "Hydration", "Water in ml") {
                        mode = LogMode.HYDRATION
                    }
                    LogChoiceRow(Icons.Filled.Restaurant, "Food", "Calories and meal") {
                        mode = LogMode.FOOD
                    }
                }
                LogMode.WEIGHT -> {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Weight (lb)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val lb = text.toDoubleOrNull()
                    Button(
                        onClick = { viewModel.logWeightLb(lb!!) { hcOk -> saved("Weight logged", hcOk) } },
                        enabled = lb != null && lb > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
                LogMode.HYDRATION -> {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Water (ml)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("250", "500", "750").forEach { quick ->
                            FilterChip(
                                selected = text == quick,
                                onClick = { text = quick },
                                label = { Text("$quick ml") },
                            )
                        }
                    }
                    val ml = text.toDoubleOrNull()
                    Button(
                        onClick = { viewModel.logHydrationMl(ml!!) { hcOk -> saved("Hydration logged", hcOk) } },
                        enabled = ml != null && ml > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
                LogMode.FOOD -> {
                    var text by remember { mutableStateOf("") }
                    var meal by remember { mutableStateOf(HealthConnectManager.FoodMeal.LUNCH) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Calories (kcal)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Breakfast" to HealthConnectManager.FoodMeal.BREAKFAST,
                            "Lunch" to HealthConnectManager.FoodMeal.LUNCH,
                            "Dinner" to HealthConnectManager.FoodMeal.DINNER,
                            "Snack" to HealthConnectManager.FoodMeal.SNACK,
                        ).forEach { (name, type) ->
                            FilterChip(
                                selected = meal == type,
                                onClick = { meal = type },
                                label = { Text(name) },
                            )
                        }
                    }
                    val kcal = text.toDoubleOrNull()
                    Button(
                        onClick = {
                            viewModel.logFoodKcal(kcal!!, meal) { hcOk -> saved("Food logged", hcOk) }
                        },
                        enabled = kcal != null && kcal > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun LogChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(icon, contentDescription = null, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
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

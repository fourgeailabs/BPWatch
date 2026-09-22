package com.fourgeailabs.bpwatch.mobile.ui

import android.content.Context
import android.graphics.Paint
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.bpwatch.mobile.DashboardMetrics
import com.fourgeailabs.bpwatch.mobile.HealthLogKind
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.snore.SnoreScheduler
import com.fourgeailabs.bpwatch.mobile.wearable.BpCheckState
import com.fourgeailabs.bpwatch.mobile.wearable.WatchLiveState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0A1A33)
private val Crimson = Color(0xFFDC143C)
private val LiveGreen = Color(0xFF34A853)

/**
 * v2.4.2 Home: a Google Health-style dashboard. BP estimate stays the hero,
 * below it the day's real metrics as tiles, then the snoring card.
 * The readings timeline lives on the History tab, calibration moved to
 * Settings -> Watch app. Pull down anywhere to refresh the tiles.
 * British spelling throughout, no em dashes in copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenWatch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrends: (TrendMetric) -> Unit,
    onOpenSnore: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val readings by viewModel.readings.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    var showLogSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshDashboard() }

    val latest = readings.firstOrNull { it.sysEstimate != null || it.sysCuff != null }
    val sys = latest?.sysEstimate ?: latest?.sysCuff
    val dia = latest?.diaEstimate ?: latest?.diaCuff
    val estimated = latest?.sysEstimate != null
    // v2.4.6: HR captured at the time of the BP recording.
    val bpHrBpm = latest?.heartRate?.takeIf { it > 0f }?.toInt()
    // v2.3 phone-triggered BP check state.
    val bpStatus by BpCheckState.status.collectAsState()
    // v2.4.2: the screen stays on while a check is measuring.
    KeepScreenAwakeWhileMeasuring(bpStatus is BpCheckState.Status.Measuring)

    // v2.4.4: pull-to-refresh temporarily removed while the v2.4.2/v2.4.3
    // launch crash is diagnosed. Plain scrollable column; the dashboard
    // still refreshes on launch via the LaunchedEffect below.
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
                        // v2.4.6: HR captured at the time of the BP recording.
                        if (bpHrBpm != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$bpHrBpm bpm · at recording",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Crimson.copy(alpha = 0.9f),
                            )
                        }
                        // v2.3 phone-triggered BP check: asks the watch to sample
                        // now, over the Data Layer. v2.4.2: while measuring, the
                        // hero shows the same beating heart wrapped in a
                        // colour-shifting ring as the watch, not a static label.
                        Spacer(Modifier.height(10.dp))
                        if (bpStatus is BpCheckState.Status.Measuring) {
                            val liveHr by WatchLiveState.liveHr.collectAsState()
                            val liveBpm = liveHr?.takeIf { it > 0f }?.toInt()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MeasuringHeart(liveHr = liveHr)
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        "Measuring…",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                    )
                                    Text(
                                        if (liveBpm != null) "$liveBpm bpm"
                                        else "Sit still, arm at heart level",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.requestBpCheck() },
                                // Retry stays available after a failure or when no
                                // watch is connected; a live check swaps the button
                                // for the measuring indicator above.
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.6f),
                                ),
                            ) {
                                Text("Check now")
                            }
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
                    onClick = onOpenSleep,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Bedtime, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Sleep")
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

            // --- Snoring card (v2.4.2): full card with last night's episodes,
            // total minutes and a 7-night chart. Tap opens the Snore screen.
            SnoreCard(viewModel = viewModel, onOpenSnore = onOpenSnore)

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

/** Google brand colours, cycled by the measuring ring — same as the watch. */
private val GoogleBlue = Color(0xFF4285F4)
private val GoogleRed = Color(0xFFEA4335)
private val GoogleYellow = Color(0xFFFBBC05)
private val GoogleGreen = Color(0xFF34A853)

/**
 * v2.4.2: the watch's measuring indicator, ported to the phone — a beating
 * red heart wrapped in a colour-shifting loading ring. The beat follows the
 * live heart rate (60 bpm until the first tick arrives).
 */
@Composable
private fun MeasuringHeart(liveHr: Float?, modifier: Modifier = Modifier) {
    val googleColors = remember { listOf(GoogleBlue, GoogleRed, GoogleYellow, GoogleGreen) }
    var ringColor by remember { mutableStateOf(GoogleBlue) }
    // Manual colour tween loop, mirroring the watch's EdgeProgressRing.
    LaunchedEffect(Unit) {
        var index = 0
        while (true) {
            val from = googleColors[index % googleColors.size]
            val to = googleColors[(index + 1) % googleColors.size]
            repeat(30) { step ->
                ringColor = lerp(from, to, (step + 1) / 30f)
                delay(30)
            }
            index++
        }
    }
    val beatScale = rememberHeartbeatScale(liveHr)
    Box(modifier = modifier.size(84.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            color = ringColor,
            strokeWidth = 5.dp,
        )
        Text(
            text = "♥",
            color = Color.Red,
            fontSize = 40.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer(
                scaleX = beatScale,
                scaleY = beatScale,
            ),
        )
    }
}

/**
 * Shared heartbeat scale, ported from the watch: the heart swells 1.0 to
 * 1.35 on every beat at the live rate. Manual tween, no animation library.
 */
@Composable
private fun rememberHeartbeatScale(liveHr: Float?): Float {
    var beatScale by remember { mutableFloatStateOf(1f) }
    val bpmNow by rememberUpdatedState(if (liveHr != null && liveHr > 0f) liveHr else 60f)
    LaunchedEffect(Unit) {
        suspend fun tweenScale(from: Float, to: Float, durationMs: Long) {
            val steps = 6
            repeat(steps) { i ->
                beatScale = from + (to - from) * (i + 1) / steps.toFloat()
                delay(durationMs / steps)
            }
        }
        while (true) {
            val intervalMs = (60_000f / bpmNow).toLong().coerceIn(350L, 1500L)
            tweenScale(1f, 1.35f, 110)
            tweenScale(1.35f, 1f, 160)
            delay((intervalMs - 270).coerceAtLeast(150))
        }
    }
    return beatScale
}

/**
 * v2.4.2 full snoring card: last night's episode count and total minutes,
 * plus a 7-night bar chart. Tapping opens the Snore detail screen.
 */
@Composable
private fun SnoreCard(viewModel: MainViewModel, onOpenSnore: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val (lastNightStart, _) = remember { SnoreScheduler.lastNightWindow() }
    val now = remember { System.currentTimeMillis() }
    val windowMs = SnoreScheduler.WINDOW_HOURS * 3_600_000L
    val weekStart = remember(lastNightStart) { lastNightStart - 6 * 24 * 3_600_000L }
    val events by remember(weekStart) {
        viewModel.observeSnoreEvents(weekStart, now)
    }.collectAsState(initial = emptyList())

    val buckets = remember(events, lastNightStart) {
        (6 downTo 0).map { back ->
            val start = lastNightStart - back * 24 * 3_600_000L
            val count = events.count { it.timestamp >= start && it.timestamp < start + windowMs }
            start to count
        }
    }
    val lastNightCount = buckets.lastOrNull()?.second ?: 0
    val lastNightMin = remember(events, lastNightStart) {
        events.filter { it.timestamp >= lastNightStart && it.timestamp < lastNightStart + windowMs }
            .sumOf { it.durationMs } / 60_000
    }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE").withZone(zone) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSnore),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TintedIcon(icon = Icons.Filled.Bedtime, contentDescription = null)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text("Snoring", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Overnight 22:00–07:00",
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$lastNightCount",
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (lastNightCount == 1) "episode last night" else "episodes last night",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$lastNightMin min total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val barColor = MaterialTheme.colorScheme.primary
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
            ) {
                val max = buckets.maxOf { it.second }.coerceAtLeast(1)
                val n = buckets.size
                val gap = 6.dp.toPx()
                val barW = (size.width / n - gap).coerceAtLeast(2f)
                val plotH = size.height - 20.dp.toPx()
                val paint = Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                buckets.forEachIndexed { i, (start, count) ->
                    val barH = plotH * count / max
                    val x = i * (barW + gap)
                    if (barH > 0) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, 4.dp.toPx() + plotH - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        dayFmt.format(Instant.ofEpochMilli(start)),
                        x + barW / 2,
                        size.height - 4.dp.toPx(),
                        paint,
                    )
                }
            }
        }
    }
}

/**
 * v2.4.2: while a phone-triggered BP check is measuring, hold a bright
 * wake lock so the phone screen stays on — the beating heart is the thing
 * to watch. Released the moment measuring ends; the 90s acquire timeout
 * matches the check timeout as a backstop. Never throws.
 */
@Composable
private fun KeepScreenAwakeWhileMeasuring(measuring: Boolean) {
    val context = LocalContext.current
    DisposableEffect(measuring) {
        var wakeLock: PowerManager.WakeLock? = null
        if (measuring) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "BPWatch:BPCheck",
                ).apply { acquire(90_000L) }
            } catch (_: Exception) {
                wakeLock = null
            }
        }
        onDispose {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {
            }
        }
    }
}

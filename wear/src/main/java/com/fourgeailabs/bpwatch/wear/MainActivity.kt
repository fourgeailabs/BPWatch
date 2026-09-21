package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.BuildConfig
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var hrMonitor: HeartRateMonitor

    private val bodySensorLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* re-checked before each measurement */ }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* alerts degrade to the full-screen activity only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Estimates can arrive while the UI process is dead (scheduled checks).
        WatchState.restoreFromPrefs(this)
        // Alarms don't survive app updates — re-arm if needed.
        CheckScheduler.ensureScheduled(this)
        // Continuous-HR service resumes here after reboot (it can't be
        // started from the boot receiver on Android 12+).
        try {
            HrMonitorService.ensureRunning(this)
        } catch (_: Exception) {
        }
        hrMonitor = HeartRateMonitor(this)
        ensureBodySensorPermission()
        ensureNotificationPermission()
        setContent {
            MaterialTheme {
                BpWatchApp(hrMonitor) { ensureBodySensorPermission() }
            }
        }
    }

    private fun ensureBodySensorPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bodySensorLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class UiState { IDLE, MEASURING, SENDING, DONE, ERROR }

private const val MEASURE_DURATION_MS = 30_000L

private fun timeAgo(timestamp: Long): String {
    val mins = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        else -> "${mins / 60} h ago"
    }
}

@Composable
private fun BpWatchApp(
    monitor: HeartRateMonitor,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var uiState by remember { mutableStateOf(UiState.IDLE) }
    var liveHr by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var sentHr by remember { mutableStateOf<Float?>(null) }
    var sentStress by remember { mutableStateOf(-1) }
    var errorMessage by remember { mutableStateOf("") }
    var pickingInterval by remember { mutableStateOf(false) }
    val lastEstimate by WatchState.lastEstimate.collectAsState()
    val calibrated by WatchState.calibrated.collectAsState()
    val monitorConfig by WatchState.monitorConfig.collectAsState()
    val liveContinuousHr by WatchState.liveHr.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * Applies a BP-check interval picked on the watch: persists it, re-arms
     * the scheduler, updates the UI, and tells the phone so both stay in sync
     * (the phone re-broadcasts its config on every reading, which would
     * otherwise clobber the watch's choice).
     */
    fun applyInterval(minutes: Int) {
        val updated = WatchSettings.getMonitorConfig(appContext)
            .copy(bpIntervalMinutes = minutes)
        WatchSettings.saveMonitorConfig(appContext, updated)
        WatchState.onMonitorConfig(updated)
        CheckScheduler.reschedule(appContext)
        scope.launch {
            DataLayer.sendIntervalSet(appContext, minutes)
        }
    }

    fun startMeasurement() {
        onRequestPermission()
        if (!monitor.available) {
            errorMessage = "No heart-rate sensor found on this watch."
            uiState = UiState.ERROR
            return
        }
        uiState = UiState.MEASURING
        progress = 0f
        liveHr = 0f
        val samples = mutableListOf<Float>()
        monitor.onSample = { hr ->
            if (hr > 0f) {
                samples.add(hr)
                liveHr = hr
            }
        }
        monitor.start()
        scope.launch {
            var elapsed = 0L
            val step = 500L
            while (elapsed < MEASURE_DURATION_MS) {
                delay(step)
                elapsed += step
                progress = elapsed / MEASURE_DURATION_MS.toFloat()
                // Live tick every 5s so the phone mirrors the measurement.
                if (elapsed % 5_000L == 0L && liveHr > 0f) {
                    try {
                        DataLayer.sendHrLive(appContext, liveHr)
                    } catch (_: Exception) {
                    }
                }
            }
            monitor.stop()
            val valid = samples.filter { it in 25f..250f }
            if (valid.isEmpty()) {
                errorMessage = "Couldn't get a steady reading. Stay still and keep the watch snug, then try again."
                uiState = UiState.ERROR
                return@launch
            }
            val avg = valid.average().toFloat()
            uiState = UiState.SENDING
            sentHr = avg
            try {
                AlertManager.checkHeartRate(monitor.appContext, avg)
            } catch (_: Exception) {
                // Alerting must never break a manual measurement.
            }
            val stress = StressEstimator.estimate(
                valid,
                WatchSettings.getRestingHr(appContext),
            )
            sentStress = stress
            val ok = DataLayer.sendHrReading(
                monitor.appContext,
                avg,
                System.currentTimeMillis(),
                stress,
            )
            if (ok) {
                uiState = UiState.DONE
            } else {
                errorMessage = "Watch couldn't reach your phone. Check Bluetooth and that BPWatch is installed on the Pixel."
                uiState = UiState.ERROR
            }
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        val listState = rememberScalingLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            // Loading ring hugs the screen edge while measuring or sending —
            // never a little spinner floating in the middle.
            if (uiState == UiState.MEASURING || uiState == UiState.SENDING) {
                EdgeProgressRing(
                    progress = if (uiState == UiState.MEASURING) progress else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Clear the TimeText the Scaffold draws at the top edge.
                    Spacer(Modifier.height(26.dp))
                    Text(
                        text = "BPWatch",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            when (uiState) {
                UiState.IDLE -> if (pickingInterval) {
                    // Check-frequency picker: a pop-up menu of intervals.
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Check frequency",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                        )
                    }
                    WATCH_INTERVAL_CHOICES.forEach { minutes ->
                        item {
                            val selected = monitorConfig?.bpIntervalMinutes == minutes
                            Chip(
                                onClick = {
                                    applyInterval(minutes)
                                    pickingInterval = false
                                },
                                label = {
                                    Text(
                                        if (selected) "✓ ${bpIntervalLabel(minutes)}"
                                        else bpIntervalLabel(minutes),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(0.9f),
                                colors = if (selected) ChipDefaults.primaryChipColors()
                                else ChipDefaults.secondaryChipColors(),
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = { pickingInterval = false },
                            label = { Text("Cancel", textAlign = TextAlign.Center) },
                            modifier = Modifier.fillMaxWidth(0.9f),
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                } else {
                    // Calibration status sits where the title used to be.
                    if (!calibrated) {
                        item {
                            Text(
                                text = "Not calibrated — set up on your phone",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                    item {
                        // The reading is the hero: dead centre of the display.
                        // (Both texts must live in a Column — bare siblings in
                        // an item stack on top of each other like a Box.)
                        val est = lastEstimate
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (est != null) "${est.sys}/${est.dia}" else "--/--",
                                style = MaterialTheme.typography.display2,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (est != null) "mmHg · ${timeAgo(est.timestamp)}"
                                else if (calibrated) "Calibrated — take a reading"
                                else "Take a reading when ready",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        Chip(
                            onClick = { startMeasurement() },
                            label = { Text("Measure now", textAlign = TextAlign.Center) },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                    item {
                        val cfg = monitorConfig
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Chip(
                                onClick = { pickingInterval = true },
                                label = { Text("Blood-pressure checks") },
                                secondaryLabel = {
                                    Text(bpIntervalLabel(cfg?.bpIntervalMinutes ?: 0))
                                },
                                modifier = Modifier.fillMaxWidth(0.85f),
                            )
                            if (cfg?.continuousHr == true && liveContinuousHr > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "♥ ${liveContinuousHr.toInt()} bpm",
                                    style = MaterialTheme.typography.title3,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    item {
                        // Version stamp: confirms at a glance which build is
                        // on the wrist (handy after a one-tap update).
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        )
                    }
                }

                UiState.MEASURING -> {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Spacer(Modifier.height(20.dp))
                            // One shared heartbeat: the red heart AND the red
                            // bpm number below it both thump at the live rate.
                            val beatScale = rememberHeartbeatScale(liveHr)
                            BeatingHeart(beatScale = beatScale)
                            Text(
                                text = if (liveHr > 0) "${liveHr.toInt()} bpm" else "Reading…",
                                style = MaterialTheme.typography.display3,
                                textAlign = TextAlign.Center,
                                color = Color.Red,
                                modifier = Modifier.graphicsLayer(
                                    scaleX = beatScale,
                                    scaleY = beatScale,
                                ),
                            )
                            Text(
                                text = "Sit still, arm at heart level",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                UiState.SENDING -> {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp))
                            Text("Sending to phone…", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                UiState.DONE -> {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                text = "Sent ✓",
                                style = MaterialTheme.typography.title2,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            sentHr?.let {
                                Text(
                                    "Avg heart rate: ${it.toInt()} bpm",
                                    textAlign = TextAlign.Center,
                                )
                            }
                        if (sentStress >= 0) {
                            Text(
                                "Stress: $sentStress/100 (${StressEstimator.label(sentStress)})",
                                style = MaterialTheme.typography.caption1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                    item {
                        Text(
                            text = "Your estimate will appear here once the phone works it out.",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Chip(
                                onClick = { uiState = UiState.IDLE },
                                label = { Text("Done", textAlign = TextAlign.Center) },
                                modifier = Modifier.fillMaxWidth(0.85f),
                                colors = ChipDefaults.primaryChipColors(),
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                UiState.ERROR -> {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Chip(
                                onClick = { startMeasurement() },
                                label = { Text("Try again", textAlign = TextAlign.Center) },
                                modifier = Modifier.fillMaxWidth(0.85f),
                                colors = ChipDefaults.primaryChipColors(),
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
}

/** Google brand colours used for the shifting progress ring. */
private val GoogleBlue = Color(0xFF4285F4)
private val GoogleRed = Color(0xFFEA4335)
private val GoogleYellow = Color(0xFFFBBC05)
private val GoogleGreen = Color(0xFF34A853)

/**
 * A progress ring that hugs the circumference of the watch screen edge,
 * drawn full-bleed behind the content. Its colour continuously shifts
 * through the Google brand colours. Pass null for [progress] for the
 * indeterminate (sending) state.
 */
@Composable
fun EdgeProgressRing(progress: Float?, modifier: Modifier = Modifier) {
    val googleColors = remember { listOf(GoogleBlue, GoogleRed, GoogleYellow, GoogleGreen) }
    var shiftingColor by remember { mutableStateOf(GoogleBlue) }
    // Manual colour tween loop — no animation library needed on Wear.
    LaunchedEffect(Unit) {
        var index = 0
        val steps = 30
        val stepDelayMs = 30L
        while (true) {
            val from = googleColors[index % googleColors.size]
            val to = googleColors[(index + 1) % googleColors.size]
            repeat(steps) { step ->
                shiftingColor = lerp(from, to, (step + 1) / steps.toFloat())
                delay(stepDelayMs)
            }
            index++
        }
    }
    val ringModifier = modifier.padding(5.dp)
    if (progress == null) {
        CircularProgressIndicator(
            modifier = ringModifier,
            indicatorColor = shiftingColor,
            strokeWidth = 5.dp,
        )
    } else {
        CircularProgressIndicator(
            progress = progress,
            modifier = ringModifier,
            indicatorColor = shiftingColor,
            strokeWidth = 5.dp,
        )
    }
}

/**
 * Drives one shared heartbeat scale while the watch scans. The beat rate
 * follows the live heart-rate reading (falling back to 60 bpm until the
 * sensor locks on). Manual scale tween — no animation library needed on Wear.
 */
@Composable
fun rememberHeartbeatScale(liveHr: Float): Float {
    var beatScale by remember { mutableFloatStateOf(1f) }
    val bpmNow by rememberUpdatedState(if (liveHr > 0f) liveHr else 60f)
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

/** A big red heart that beats with the shared heartbeat scale. */
@Composable
fun BeatingHeart(beatScale: Float) {
    Text(
        text = "♥",
        color = Color.Red,
        fontSize = 64.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.graphicsLayer(scaleX = beatScale, scaleY = beatScale),
    )
}

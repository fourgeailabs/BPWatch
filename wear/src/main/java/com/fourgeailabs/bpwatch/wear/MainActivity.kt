package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* re-checked before each measurement */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Estimates can arrive while the UI process is dead (hourly checks).
        WatchState.restoreFromPrefs(this)
        // Alarms don't survive app updates — re-arm if needed.
        HourlyCheck.ensureScheduled(this)
        hrMonitor = HeartRateMonitor(this)
        ensureBodySensorPermission()
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
            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
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
    var checkMode by remember { mutableStateOf(WatchSettings.getCheckMode(context)) }

    val lastEstimate by WatchState.lastEstimate.collectAsState()
    val calibrated by WatchState.calibrated.collectAsState()
    val scope = rememberCoroutineScope()

    fun setMode(mode: WatchSettings.CheckMode) {
        WatchSettings.setCheckMode(appContext, mode)
        if (mode == WatchSettings.CheckMode.HOURLY) {
            HourlyCheck.schedule(appContext)
        } else {
            HourlyCheck.cancel(appContext)
        }
        checkMode = mode
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
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "BPWatch",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }

            when (uiState) {
                UiState.IDLE -> {
                    val est = lastEstimate
                    item {
                        if (est != null) {
                            Text(
                                text = "${est.sys}/${est.dia}",
                                style = MaterialTheme.typography.display2,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = "mmHg · ${timeAgo(est.timestamp)}",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                text = "--/--",
                                style = MaterialTheme.typography.display2,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (calibrated) "Calibrated — take a reading"
                                else "Not calibrated — set up on your phone",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    if (est != null && !calibrated) {
                        item {
                            Text(
                                text = "Not calibrated — set up on your phone",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = { startMeasurement() },
                            label = { Text("Measure now", textAlign = TextAlign.Center) },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                    item {
                        Chip(
                            onClick = {
                                setMode(
                                    if (checkMode == WatchSettings.CheckMode.MANUAL) {
                                        WatchSettings.CheckMode.HOURLY
                                    } else {
                                        WatchSettings.CheckMode.MANUAL
                                    },
                                )
                            },
                            label = {
                                Text(
                                    if (checkMode == WatchSettings.CheckMode.HOURLY) "Hourly: On"
                                    else "Hourly: Off",
                                    textAlign = TextAlign.Center,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                    item {
                        Text(
                            text = if (checkMode == WatchSettings.CheckMode.HOURLY) {
                                "Auto-checks every hour"
                            } else {
                                "Manual checks only"
                            },
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                UiState.MEASURING -> {
                    item {
                        Spacer(Modifier.height(24.dp))
                        CircularProgressIndicator(progress = progress)
                    }
                    item {
                        Text(
                            text = if (liveHr > 0) "${liveHr.toInt()} bpm" else "Reading…",
                            style = MaterialTheme.typography.display3,
                            textAlign = TextAlign.Center,
                        )
                    }
                    item {
                        Text(
                            text = "Sit still, arm at heart level",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                UiState.SENDING -> {
                    item {
                        Spacer(Modifier.height(24.dp))
                        CircularProgressIndicator()
                    }
                    item {
                        Text("Sending to phone…", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                    }
                }

                UiState.DONE -> {
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "Sent ✓",
                            style = MaterialTheme.typography.title2,
                            textAlign = TextAlign.Center,
                        )
                    }
                    item {
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
                            )
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
                        Chip(
                            onClick = { uiState = UiState.IDLE },
                            label = { Text("Done", textAlign = TextAlign.Center) },
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }

                UiState.ERROR -> {
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
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

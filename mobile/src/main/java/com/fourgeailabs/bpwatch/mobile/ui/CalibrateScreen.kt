package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine

@Composable
fun CalibrateScreen(viewModel: MainViewModel) {
    val points by viewModel.calibrationPoints.collectAsState()
    val model by viewModel.calibrationModel.collectAsState()
    val watchHr = viewModel.latestWatchHr

    var sysInput by remember { mutableStateOf("") }
    var diaInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Calibrate", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How calibration works", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Sit quietly for 5 minutes, cuff on your arm.\n" +
                            "2. Tap Measure on your Galaxy Watch.\n" +
                            "3. Take your cuff reading right away and enter it below.\n" +
                            "4. Repeat at least ${CalibrationEngine.MIN_POINTS} times — " +
                            "ideally at different times of day (morning, evening, after a walk).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a calibration point", style = MaterialTheme.typography.titleSmall)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Watch heart rate: " + (watchHr?.let { "${it.toInt()} bpm" } ?: "none yet"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.refreshLatestWatchHr() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh heart rate")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sysInput,
                            onValueChange = { sysInput = it.filter(Char::isDigit).take(3) },
                            label = { Text("Systolic") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = diaInput,
                            onValueChange = { diaInput = it.filter(Char::isDigit).take(3) },
                            label = { Text("Diastolic") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }

                    val sys = sysInput.toIntOrNull()
                    val dia = diaInput.toIntOrNull()
                    val valid = sys != null && dia != null &&
                        sys in 70..260 && dia in 40..160 &&
                        watchHr != null && watchHr > 0f

                    Button(
                        onClick = {
                            viewModel.addCalibrationPoint(sys!!, dia!!, watchHr!!)
                            sysInput = ""
                            diaInput = ""
                        },
                        enabled = valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save calibration point")
                    }
                    if (!valid) {
                        Text(
                            "Enter a valid cuff reading and make sure the watch has sent a heart rate first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Points (${points.size})" + if (model != null) " — calibrated ✓" else " — need ${CalibrationEngine.MIN_POINTS - points.size} more",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (points.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.clearCalibration() }) {
                        Text("Clear all")
                    }
                }
            }
        }

        itemsIndexed(points) { index, p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${p.sys}/${p.dia} mmHg  @  ${p.heartRate.toInt()} bpm")
                    IconButton(onClick = { viewModel.removeCalibrationPoint(index) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete point")
                    }
                }
            }
        }

        item {
            Text(
                "Estimates are only as good as your calibration. Recalibrate every few weeks, " +
                    "or whenever medication, fitness, or stress levels change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

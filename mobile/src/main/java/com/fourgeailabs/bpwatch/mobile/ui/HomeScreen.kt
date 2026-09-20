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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val latest by viewModel.latest.collectAsState()
    val readings by viewModel.readings.collectAsState()
    val model by viewModel.calibrationModel.collectAsState()
    val spo2 = viewModel.spo2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BPWatch", style = MaterialTheme.typography.headlineMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                val sys = latest?.sysEstimate ?: latest?.sysCuff
                val dia = latest?.diaEstimate ?: latest?.diaCuff
                if (sys != null && dia != null) {
                    val isEstimate = latest?.sysEstimate != null
                    Text(
                        text = "$sys / $dia",
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(
                        text = if (isEstimate) "mmHg (estimated from your calibration)"
                        else "mmHg (cuff reading)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    latest?.let {
                        Text(
                            text = "Measured ${formatTime(it.timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        text = "No readings yet",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = if (model == null)
                            "Head to Calibrate and pair your cuff with the watch first."
                        else
                            "Tap Measure on your Galaxy Watch to take a reading.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Heart rate", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = latest?.heartRate?.let { "${it.toInt()} bpm" } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Blood oxygen", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = spo2?.let { "$it %" } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }

        ManualSpo2Card(viewModel)

        Text("Recent trend", style = MaterialTheme.typography.titleMedium)
        BpSparkline(readings)

        Text(
            text = "BPWatch gives wellness estimates based on your own cuff calibration. " +
                "It is not a medical device — always confirm with a cuff before making health decisions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ManualSpo2Card(viewModel: MainViewModel) {
    var input by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Log blood oxygen manually, or connect Health Connect in Settings " +
                    "to pull it in automatically from Samsung Health.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text("SpO2 %") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        input.toIntOrNull()?.let { v ->
                            if (v in 50..100) {
                                viewModel.addManualSpo2(v)
                                input = ""
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}

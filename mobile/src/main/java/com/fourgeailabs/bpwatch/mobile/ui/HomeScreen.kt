package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(viewModel: MainViewModel, onOpenCalibrate: () -> Unit) {
    val latest by viewModel.latest.collectAsState()
    val readings by viewModel.readings.collectAsState()
    val model by viewModel.calibrationModel.collectAsState()
    val spo2 = viewModel.spo2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("BPWatch", style = MaterialTheme.typography.headlineMedium)

        // Hero: latest blood pressure.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TintedIcon(
                    icon = Icons.Filled.MonitorHeart,
                    contentDescription = null,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    size = 56.dp,
                )
                val sys = latest?.sysEstimate ?: latest?.sysCuff
                val dia = latest?.diaEstimate ?: latest?.diaCuff
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (sys != null && dia != null) {
                        val isEstimate = latest?.sysEstimate != null
                        Text(
                            text = "$sys / $dia",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (isEstimate) "mmHg · estimated from your calibration"
                            else "mmHg · cuff reading",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        latest?.let {
                            Text(
                                text = "Measured ${formatTime(it.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    } else {
                        Text(
                            text = "No readings yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (model == null)
                                "Head to Calibrate and pair your cuff with the watch first."
                            else
                                "Tap Measure on your Galaxy Watch to take a reading.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        // Heart rate + SpO2 metric cards.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Filled.Favorite,
                label = "Heart rate",
                value = latest?.heartRate?.let { "${it.toInt()} bpm" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                icon = Icons.Filled.Air,
                label = "Blood oxygen",
                value = spo2?.let { "$it %" } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        // Calibrate entry point.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Calibrate", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pair cuff readings with your watch to personalise your estimates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onOpenCalibrate) {
                    Icon(Icons.Filled.MonitorHeart, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Calibrate")
                }
            }
        }

        ManualSpo2Card(viewModel)

        // Recent trend.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent trend", style = MaterialTheme.typography.titleLarge)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    BpSparkline(readings)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "BPWatch gives wellness estimates based on your own cuff calibration. " +
                    "It is not a medical device — always confirm with a cuff before making health decisions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TintedIcon(icon = icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun ManualSpo2Card(viewModel: MainViewModel) {
    var input by remember { mutableStateOf("") }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Log blood oxygen", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Log it manually, or connect Health Connect in Settings " +
                    "to pull it in automatically from Samsung Health.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                FilledTonalButton(
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

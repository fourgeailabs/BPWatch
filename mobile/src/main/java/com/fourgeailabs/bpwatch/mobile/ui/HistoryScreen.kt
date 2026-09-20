package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val readings by viewModel.readings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("History", style = MaterialTheme.typography.headlineMedium)

        if (readings.isEmpty()) {
            Text("Nothing here yet. Your readings will land in this list.")
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(readings) { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val sys = r.sysEstimate ?: r.sysCuff
                            val dia = r.diaEstimate ?: r.diaCuff
                            Text(
                                text = if (sys != null && dia != null) "$sys/$dia mmHg" else "—",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = sourceLabel(r.source, r.sysEstimate != null),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val details = buildList {
                            r.heartRate?.let { add("${it.toInt()} bpm") }
                            r.spo2?.let { add("SpO2 $it%") }
                            r.stress?.let { add("stress $it/100") }
                            add(formatDateTime(r.timestamp))
                        }.joinToString("  ·  ")
                        Text(details, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun sourceLabel(source: String, isEstimate: Boolean): String = when (source) {
    "watch" -> if (isEstimate) "watch estimate" else "watch"
    "cuff" -> "cuff calibration"
    "manual" -> "manual"
    "health_connect" -> "health connect"
    else -> source
}

private fun formatDateTime(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}

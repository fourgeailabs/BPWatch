package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            val sys = r.sysEstimate ?: r.sysCuff
                            val dia = r.diaEstimate ?: r.diaCuff
                            Text(
                                text = if (sys != null && dia != null) "$sys/$dia mmHg" else "—",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            val details = buildList {
                                r.heartRate?.let { add("${it.toInt()} bpm") }
                                r.stress?.let { add("stress $it/100") }
                                add(formatDateTime(r.timestamp))
                            }.joinToString("  ·  ")
                            Text(details, style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = sourceLabel(r.source, r.sysEstimate != null),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
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

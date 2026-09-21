package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepDiagnosis
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * v2.3.2: answers "why is my sleep not populating?" with facts from Health
 * Connect itself — the grant it reports, the raw sessions it holds (with
 * stage counts and the app that wrote each one), and any read error.
 * Used in Settings and in the Trends sleep empty state.
 */
@Composable
fun SleepDiagnosticsCard(
    onDiagnose: suspend () -> SleepDiagnosis,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SleepDiagnosis?>(null) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon = Icons.Filled.Bedtime, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Sleep diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shows exactly what Health Connect holds for sleep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            result?.let { SleepDiagnosisResult(it) }

            Button(
                onClick = {
                    scope.launch {
                        running = true
                        try {
                            result = onDiagnose()
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (result == null) "Check sleep data" else "Check again")
                }
            }
        }
    }
}

@Composable
private fun SleepDiagnosisResult(d: SleepDiagnosis) {
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember {
        DateTimeFormatter.ofPattern("EEE HH:mm").withZone(zone)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (d.error != null) {
            Text(
                "Couldn't read Health Connect: ${d.error}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        Text(
            "Health Connect: ${d.sdkStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Read-sleep permission: " +
                if (d.readSleepGranted) "granted ✓" else "NOT granted ✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (d.readSleepGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Text(
            "Sleep sessions in the last 36 hours: ${d.sessions36h.size}",
            style = MaterialTheme.typography.bodyMedium,
        )
        d.sessions36h.forEach { s ->
            val hours = s.minutesCounted / 60.0
            val hoursText = if (hours == hours.toLong().toDouble()) {
                hours.toLong().toString()
            } else {
                String.format("%.1f", hours)
            }
            Text(
                "• ${fmt.format(s.start)} → ${fmt.format(s.end)}: " +
                    "${hoursText}h counted, ${s.stageCount} stages" +
                    (s.originPackage?.let { ", via $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Sleep sessions in the last 7 days: ${d.sessions7d}",
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            !d.readSleepGranted -> Text(
                "Grant the sleep permission, then check again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.sessions36h.isEmpty() && d.sessions7d == 0 -> Text(
                "Health Connect holds no sleep sessions at all, so there is " +
                    "nothing for the app to show. In Samsung Health: Settings → " +
                    "Health Connect → make sure Sleep is allowed (and sync is " +
                    "switched on there).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.sessions36h.isEmpty() -> Text(
                "Sessions exist in the last 7 days but none in the last 36 " +
                    "hours — the Home tile only shows last night's sleep.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

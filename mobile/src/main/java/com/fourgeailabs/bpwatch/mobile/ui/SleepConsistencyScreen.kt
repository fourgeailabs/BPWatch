package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager.SleepNightSummary
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardBg = Color(0xFF1A1A2E)
private val DeepPurple = Color(0xFF7B61FF)
private val Crimson = Color(0xFFDC143C)

/**
 * v2.4.6 sleep consistency: bedtime/wake-time bars across the last 7
 * nights against a target window, modelled on the Samsung Health
 * consistency screen from Eric's screenshots.
 */
@Composable
fun SleepConsistencyScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var week by remember { mutableStateOf<List<SleepNightSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        week = viewModel.getSleepWeek(LocalDate.now())
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Sleep consistency",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }

        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Crimson)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val first = week.firstOrNull()?.wakeDate
                    val last = week.lastOrNull()?.wakeDate
                    if (first != null && last != null) {
                        Text(
                            "${first.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))} – " +
                                    last.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ConsistencyChart(week)
                    Spacer(Modifier.height(8.dp))
                    // Target legend.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Text(
                            "Bedtime target 11:00 PM",
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepPurple,
                        )
                        Text(
                            "Wake target 7:00 AM",
                            style = MaterialTheme.typography.labelSmall,
                            color = DeepPurple,
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Why a consistent wake-up time matters",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Consistent wake-up times can help you set your body's " +
                                "rhythm so you're awake during the day and sleepy " +
                                "at night. They can also help you to feel tired " +
                                "enough by nighttime that you're ready to sleep " +
                                "when you want to.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Even if you feel tired or didn't get enough sleep the " +
                                "night before, it's best to get up at your regular " +
                                "time. In the long run, that consistency can pay " +
                                "off with better sleep.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Horizontal bars per night from bedtime to wake time, plotted on a
 * 24h axis centred on the night (6pm–2pm). Target bed/wake guides drawn
 * as vertical lines.
 */
@Composable
private fun ConsistencyChart(week: List<SleepNightSummary>) {
    val zone = ZoneId.systemDefault()
    // Axis: 18:00 -> 38:00 (2pm next day), in minutes.
    val axisStart = 18 * 60
    val axisEnd = 38 * 60
    val axisSpan = (axisEnd - axisStart).toFloat()
    // Targets: 23:00 bed, 07:00 wake (31:00 on axis).
    val targetBed = 23 * 60
    val targetWake = 31 * 60

    fun minutesOnAxis(t: LocalTime): Int {
        val m = t.hour * 60 + t.minute
        return if (m < 12 * 60) m + 24 * 60 else m
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (night in week) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    night.wakeDate.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (night.wakeDate == LocalDate.now()) Crimson
                    else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.width(28.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                ) {
                    val bed = night.bedtime?.atZone(zone)?.toLocalTime()
                    val wake = night.wakeTime?.atZone(zone)?.toLocalTime()
                    if (bed != null && wake != null) {
                        val b = minutesOnAxis(bed).coerceIn(axisStart, axisEnd)
                        val w = minutesOnAxis(wake).coerceIn(axisStart, axisEnd)
                        if (w > b) {
                            val startFrac = (b - axisStart) / axisSpan
                            val widthFrac = (w - b) / axisSpan
                            val isToday = night.wakeDate == LocalDate.now()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(widthFrac)
                                    .height(22.dp)
                                    .padding(
                                        start = (startFrac * 300).dp,
                                    )
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (isToday) DeepPurple
                                        else Color.White.copy(alpha = 0.35f)
                                    ),
                            )
                        }
                    }
                    // Target guides.
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val wPx = size.width
                        fun xFor(m: Int) = wPx * (m - axisStart) / axisSpan
                        drawLine(
                            DeepPurple.copy(alpha = 0.6f),
                            Offset(xFor(targetBed), 0f),
                            Offset(xFor(targetBed), size.height),
                            strokeWidth = 2f,
                        )
                        drawLine(
                            DeepPurple.copy(alpha = 0.6f),
                            Offset(xFor(targetWake), 0f),
                            Offset(xFor(targetWake), size.height),
                            strokeWidth = 2f,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (h in listOf("6pm", "10pm", "2am", "6am", "10am", "2pm")) {
                Text(
                    h,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}

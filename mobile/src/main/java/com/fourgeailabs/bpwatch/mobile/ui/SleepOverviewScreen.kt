package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager.SleepNightSummary
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardBg = Color(0xFF1A1A2E)
private val DeepPurple = Color(0xFF7B61FF)
private val LightPurple = Color(0xFFB39DDB)
private val Crimson = Color(0xFFDC143C)
private val AwakePink = Color(0xFFFF6B9D)
private val DeepBlue = Color(0xFF4A3AFF)

/**
 * v2.4.6 sleep overview: 7-day sleep time bars, sleep score trend,
 * consistency view, and bedtime guidance. Modelled on the Samsung Health
 * sleep screens from Eric's screenshots, in the BPWatch theme.
 */
@Composable
fun SleepOverviewScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenConsistency: () -> Unit,
    onSelectNight: (LocalDate) -> Unit,
) {
    var week by remember { mutableStateOf<List<SleepNightSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showScore by remember { mutableStateOf(true) }

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
                "Sleep",
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
            // --- 7-day chart card.
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
                        Spacer(Modifier.height(8.dp))
                    }
                    // Toggle: Sleep score / Sleep time.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewToggle(
                            "Sleep score",
                            selected = showScore,
                            onClick = { showScore = true },
                        )
                        OverviewToggle(
                            "Sleep time",
                            selected = !showScore,
                            onClick = { showScore = false },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (showScore) {
                        val scores = week.mapNotNull { it.sleepScore }
                        if (scores.isNotEmpty()) {
                            val avg = scores.average().toInt()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Average sleep score",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$avg",
                                    style = MaterialTheme.typography.displayMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    scoreLabel(avg),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(scoreColor(avg).copy(alpha = 0.25f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            SleepScoreLineChart(week)
                        } else {
                            Text(
                                "No sleep scores this week.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    } else {
                        val times = week.mapNotNull { it.actualSleepMinutes }
                        if (times.isNotEmpty()) {
                            val avgMin = times.average().toLong()
                            Text(
                                "Sleep time",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatDuration(avgMin),
                                style = MaterialTheme.typography.displayMedium,
                                color = Color.White,
                            )
                            val nights = week.filter { it.bedtime != null && it.wakeTime != null }
                            if (nights.isNotEmpty()) {
                                val zone = ZoneId.systemDefault()
                                val bedtimes = nights.mapNotNull { it.bedtime?.atZone(zone)?.toLocalTime() }
                                val wakes = nights.mapNotNull { it.wakeTime?.atZone(zone)?.toLocalTime() }
                                if (bedtimes.isNotEmpty() && wakes.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Average bedtime ${bedtimes.sorted()[bedtimes.size / 2].format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                    Text(
                                        "Average wake-up time ${wakes.sorted()[wakes.size / 2].format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SleepTimeBarChart(week, onSelectNight = onSelectNight)
                        } else {
                            Text(
                                "No sleep time this week.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            // --- Bedtime guidance.
            BedtimeGuidanceCard(week)

            // --- Sleep consistency preview.
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenConsistency),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Sleep consistency",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    val withData = week.count { it.bedtime != null }
                    Text(
                        if (withData > 0) "$withData of 7 nights tracked — tap for the full view."
                        else "No nights tracked yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OverviewToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) Color.White.copy(alpha = 0.2f)
                else Color.White.copy(alpha = 0.06f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 7-day stacked sleep-time bars (deep/light/rem/awake). Tapping a bar opens that night. */
@Composable
private fun SleepTimeBarChart(
    week: List<SleepNightSummary>,
    onSelectNight: (LocalDate) -> Unit,
) {
    val maxMin = (week.mapNotNull { it.timeInBedMinutes }.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            for (night in week) {
                val tib = night.timeInBedMinutes
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            enabled = tib != null,
                            onClick = { onSelectNight(night.wakeDate) },
                        ),
                ) {
                    if (tib != null && tib > 0) {
                        val h = (tib.toFloat() / maxMin * 120).dp
                        // Stacked: deep, light, rem, awake (top).
                        Column(
                            modifier = Modifier
                                .width(28.dp)
                                .height(h)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val total = tib.toFloat()
                            @Composable
                            fun seg(mins: Long, color: Color) {
                                if (mins > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(h * (mins / total))
                                            .background(color),
                                    )
                                }
                            }
                            seg(night.awakeMinutes, AwakePink)
                            seg(night.remMinutes, LightPurple)
                            seg(night.lightMinutes, DeepPurple)
                            seg(night.deepMinutes, DeepBlue)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.1f)),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        night.wakeDate.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (night.wakeDate == LocalDate.now()) Crimson
                        else Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/** 7-day sleep score line chart. */
@Composable
private fun SleepScoreLineChart(week: List<SleepNightSummary>) {
    val points = week.mapNotNull { n ->
        n.sleepScore?.let { n.wakeDate to it }
    }
    if (points.isEmpty()) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(8.dp),
    ) {
        val w = size.width
        val h = size.height
        val min = 0f
        val max = 100f
        fun x(i: Int) = w * i / (points.size - 1).coerceAtLeast(1).toFloat()
        fun y(v: Int) = h - (v - min) / (max - min) * h
        // Gridlines at 50 and 100.
        for (g in listOf(50, 100)) {
            drawLine(
                Color.White.copy(alpha = 0.12f),
                Offset(0f, y(g)),
                Offset(w, y(g)),
                strokeWidth = 1f,
            )
        }
        val path = Path()
        points.forEachIndexed { i, (_, s) ->
            if (i == 0) path.moveTo(x(i), y(s)) else path.lineTo(x(i), y(s))
        }
        drawPath(path, Color.White.copy(alpha = 0.8f), style = Stroke(width = 3f))
        // Dots coloured by score band.
        points.forEachIndexed { i, (_, s) ->
            drawCircle(scoreColor(s), radius = 10f, center = Offset(x(i), y(s)))
            drawCircle(Color.White, radius = 4f, center = Offset(x(i), y(s)))
        }
    }
    // Day labels.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for ((date, _) in points) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Bedtime guidance based on recent sleep debt. */
@Composable
private fun BedtimeGuidanceCard(week: List<SleepNightSummary>) {
    val recent = week.mapNotNull { it.actualSleepMinutes }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Bedtime guidance",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            if (recent.size >= 3) {
                val avgMin = recent.average()
                val targetMin = 7.5 * 60
                if (avgMin < targetMin - 30) {
                    val debtH = ((targetMin - avgMin) / 60).toInt()
                    Text(
                        "You haven't gotten quite enough sleep lately " +
                                "(averaging ${formatDuration(avgMin.toLong())}). " +
                                "Try heading to bed about $debtH hour${if (debtH == 1) "" else "s"} " +
                                "earlier tonight to start catching up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                } else {
                    Text(
                        "You're averaging ${formatDuration(avgMin.toLong())} — " +
                                "right in the healthy range. Keep your bedtime " +
                                "consistent to hold onto it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            } else {
                Text(
                    "Track a few more nights and I'll suggest a bedtime " +
                            "that gets you to 7–8 hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Get a recommendation for the best time to go to bed each " +
                        "night to wake up feeling refreshed.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

private fun formatDuration(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun scoreLabel(score: Int): String = when {
    score >= 90 -> "Excellent"
    score >= 80 -> "Good"
    score >= 60 -> "Fair"
    else -> "Attention"
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> Color(0xFF34A853)
    score >= 80 -> DeepPurple
    score >= 60 -> Color(0xFFFFB300)
    else -> Crimson
}

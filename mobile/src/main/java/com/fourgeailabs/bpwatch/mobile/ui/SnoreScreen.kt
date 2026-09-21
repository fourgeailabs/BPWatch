package com.fourgeailabs.bpwatch.mobile.ui

import android.graphics.Paint
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.data.SnoreEvent
import com.fourgeailabs.bpwatch.mobile.snore.SnoreScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private enum class SnoreRange(val label: String, val nights: Int) {
    NIGHT("Night", 1),
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365),
}

private data class SnoreBucket(val label: String, val count: Int)

/**
 * Snore detail screen (v2.3): per-night snore counts over Night / Week /
 * Month / Year, plus last night's events with playable clips. Everything is
 * local — Room + the phone's files dir — no network or upload code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoreScreen(viewModel: MainViewModel) {
    var range by remember { mutableStateOf(SnoreRange.WEEK) }
    val zone = remember { ZoneId.systemDefault() }
    val (lastNightStart, _) = remember { SnoreScheduler.lastNightWindow() }
    val now = remember { System.currentTimeMillis() }

    // Reactive events across the whole visible range.
    val rangeStart = remember(range, lastNightStart) {
        lastNightStart - (range.nights - 1) * 24 * 3_600_000L
    }
    val events by remember(range) { viewModel.observeSnoreEvents(rangeStart, now) }
        .collectAsState(initial = emptyList())

    // Last night's events, for the playable list below the chart.
    var lastNightEvents by remember { mutableStateOf(emptyList<SnoreEvent>()) }
    LaunchedEffect(Unit) {
        lastNightEvents = viewModel.snoreEventsLastNight()
    }

    val buckets = remember(events, range, lastNightStart, zone) {
        buildBuckets(events, range, lastNightStart, zone)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Overnight 22:00–07:00. Tap a clip to listen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SnoreRange.entries.forEachIndexed { index, r ->
                SegmentedButton(
                    selected = range == r,
                    onClick = { range = r },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SnoreRange.entries.size,
                    ),
                    label = { Text(r.label) },
                )
            }
        }

        SnoreBarChart(buckets = buckets)

        Text("Last night", style = MaterialTheme.typography.titleMedium)
        if (lastNightEvents.isEmpty()) {
            Text(
                "No snoring detected last night.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    lastNightEvents.forEach { event ->
                        SnoreEventRow(event = event, zone = zone)
                    }
                }
            }
        }
    }
}

/**
 * Buckets events into chart columns: 9 hourly buckets for Night, one
 * 22:00–07:00 bucket per evening for Week/Month, and one bucket per month
 * for Year (an event belongs to the evening it starts after).
 */
private fun buildBuckets(
    events: List<SnoreEvent>,
    range: SnoreRange,
    lastNightStart: Long,
    zone: ZoneId,
): List<SnoreBucket> {
    val windowMs = SnoreScheduler.WINDOW_HOURS * 3_600_000L
    return when (range) {
        SnoreRange.NIGHT -> {
            val hourFmt = DateTimeFormatter.ofPattern("ha").withZone(zone)
            (0 until SnoreScheduler.WINDOW_HOURS).map { h ->
                val start = lastNightStart + h * 3_600_000L
                val count = events.count { it.timestamp >= start && it.timestamp < start + 3_600_000L }
                SnoreBucket(hourFmt.format(Instant.ofEpochMilli(start)).lowercase(), count)
            }
        }

        SnoreRange.WEEK, SnoreRange.MONTH -> {
            val dayFmt = if (range == SnoreRange.WEEK) {
                DateTimeFormatter.ofPattern("EEE").withZone(zone)
            } else {
                DateTimeFormatter.ofPattern("d MMM").withZone(zone)
            }
            ((range.nights - 1) downTo 0).map { back ->
                val start = lastNightStart - back * 24 * 3_600_000L
                val count = events.count { it.timestamp >= start && it.timestamp < start + windowMs }
                SnoreBucket(dayFmt.format(Instant.ofEpochMilli(start)), count)
            }
        }

        SnoreRange.YEAR -> {
            val monthFmt = DateTimeFormatter.ofPattern("MMM").withZone(zone)
            val lastEvening = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastNightStart), zone)
                .toLocalDate().withDayOfMonth(1)
            (11 downTo 0).map { back ->
                val month: LocalDate = lastEvening.minusMonths(back.toLong())
                val start = month.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                // Attribute to the evening the event belongs to: events after
                // 07:00 with an evening before midnight stay in the right
                // month by shifting the timestamp back past the window end.
                val count = events.count { e ->
                    val evening = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp), zone)
                        .minusHours(SnoreScheduler.WINDOW_END_HOUR.toLong())
                        .toInstant().toEpochMilli()
                    evening >= start && evening < end
                }
                SnoreBucket(monthFmt.format(month), count)
            }
        }
    }
}

@Composable
private fun SnoreBarChart(buckets: List<SnoreBucket>) {
    val barColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier = Modifier.fillMaxWidth()) {
        if (buckets.all { it.count == 0 }) {
            Text(
                "No snoring detected in this range.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(12.dp)
            ) {
                val max = buckets.maxOf { it.count }.coerceAtLeast(1)
                val n = buckets.size
                val gap = 4.dp.toPx()
                val barW = ((size.width - 8.dp.toPx()) / n - gap).coerceAtLeast(1f)
                val plotH = size.height - 26.dp.toPx()
                val paint = Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val labelEvery = if (n <= 12) 1 else (n / 10) + 1
                buckets.forEachIndexed { i, bucket ->
                    val barH = plotH * bucket.count / max
                    val x = 4.dp.toPx() + i * (barW + gap)
                    if (barH > 0) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, 4.dp.toPx() + plotH - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                    if (i % labelEvery == 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            bucket.label,
                            x + barW / 2,
                            size.height - 6.dp.toPx(),
                            paint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnoreEventRow(event: SnoreEvent, zone: ZoneId) {
    // One MediaPlayer per row, released when the row leaves the composition.
    // Rows are few (a night of events), so per-row players are the simple,
    // correct choice over a shared one.
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }
    var playing by remember { mutableStateOf(false) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a").withZone(zone) }

    fun togglePlay() {
        try {
            if (playing) {
                player.reset()
                playing = false
            } else {
                val path = event.clipPath ?: return
                player.reset()
                player.setDataSource(path)
                player.prepare()
                player.start()
                playing = true
                player.setOnCompletionListener { playing = false }
            }
        } catch (_: Exception) {
            playing = false
        }
    }

    ListItem(
        headlineContent = {
            Text(timeFmt.format(Instant.ofEpochMilli(event.timestamp)))
        },
        supportingContent = {
            Text("${event.durationMs / 1000} sec")
        },
        trailingContent = {
            IconButton(onClick = { togglePlay() }, enabled = event.clipPath != null) {
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Play clip",
                )
            }
        },
    )
}

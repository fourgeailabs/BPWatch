package com.fourgeailabs.bpwatch.mobile.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.Spo2Sample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TrendMetric(val label: String) {
    HEART_RATE("Heart rate"),
    STRESS("Stress"),
    BLOOD_PRESSURE("Blood pressure"),
    SPO2("Blood oxygen"),
}

private enum class TrendRange(val label: String, val millis: Long) {
    DAY("Day", 24L * 3600_000L),
    WEEK("Week", 7L * 24L * 3600_000L),
    MONTH("Month", 30L * 24L * 3600_000L),
    YEAR("Year", 365L * 24L * 3600_000L),
}

private data class ChartPoint(val x: Long, val y: Float)

private data class ChartSeries(
    val label: String,
    val color: Color,
    val unit: String,
    val points: List<ChartPoint>,
)

/**
 * v2.0 Trends: history graphs for heart rate, stress, blood pressure and
 * SpO2, with a metric switcher, a range switcher, and min/max/avg summary.
 * Charts are hand-rolled on Compose Canvas — no chart dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    viewModel: MainViewModel,
    onRequestHcPermissions: (Set<String>) -> Unit,
) {
    var metric by remember { mutableStateOf(TrendMetric.HEART_RATE) }
    var range by remember { mutableStateOf(TrendRange.WEEK) }

    val now = remember(range) { System.currentTimeMillis() }
    val start = now - range.millis

    val hrSamples by remember(range) {
        viewModel.observeHrRange(start, now)
    }.collectAsState(initial = emptyList())
    val stressSamples by remember(range) {
        viewModel.observeStressRange(start, now)
    }.collectAsState(initial = emptyList())
    val readings by viewModel.readings.collectAsState()

    var spo2 by remember { mutableStateOf<List<Spo2Sample>>(emptyList()) }
    var hcAvailable by remember { mutableStateOf(false) }
    var spo2Loading by remember { mutableStateOf(false) }
    LaunchedEffect(range, metric) {
        if (metric == TrendMetric.SPO2) {
            spo2Loading = true
            hcAvailable = viewModel.isHcSpO2Available()
            spo2 = viewModel.loadSpo2Range(
                Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(now),
            )
            spo2Loading = false
        }
    }

    val series: List<ChartSeries> = remember(metric, hrSamples, stressSamples, readings, spo2, start, now) {
        when (metric) {
            TrendMetric.HEART_RATE -> listOf(
                ChartSeries(
                    label = "Heart rate",
                    color = Color(0xFFD93025),
                    unit = "bpm",
                    points = hrSamples.map { ChartPoint(it.timestamp, it.bpm) },
                )
            )
            TrendMetric.STRESS -> listOf(
                ChartSeries(
                    label = "Stress",
                    color = Color(0xFF9334E6),
                    unit = "",
                    points = stressSamples.map { ChartPoint(it.timestamp, it.score.toFloat()) },
                )
            )
            TrendMetric.BLOOD_PRESSURE -> {
                val inRange = readings.filter { it.timestamp in start..now }
                listOf(
                    ChartSeries(
                        label = "Systolic",
                        color = Color(0xFFD93025),
                        unit = "mmHg",
                        points = inRange.mapNotNull { r ->
                            (r.sysEstimate ?: r.sysCuff)?.let {
                                ChartPoint(r.timestamp, it.toFloat())
                            }
                        },
                    ),
                    ChartSeries(
                        label = "Diastolic",
                        color = Color(0xFF1A73E8),
                        unit = "mmHg",
                        points = inRange.mapNotNull { r ->
                            (r.diaEstimate ?: r.diaCuff)?.let {
                                ChartPoint(r.timestamp, it.toFloat())
                            }
                        },
                    ),
                )
            }
            TrendMetric.SPO2 -> listOf(
                ChartSeries(
                    label = "Blood oxygen",
                    color = Color(0xFF0B8043),
                    unit = "%",
                    points = spo2.map { ChartPoint(it.timestamp, it.spo2.toFloat()) },
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Trends", style = MaterialTheme.typography.headlineMedium)

        // Metric switcher.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrendMetric.entries.forEach { m ->
                FilterChip(
                    selected = metric == m,
                    onClick = { metric = m },
                    label = { Text(m.label) },
                )
            }
        }

        // Range switcher.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TrendRange.entries.forEachIndexed { index, r ->
                SegmentedButton(
                    selected = range == r,
                    onClick = { range = r },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TrendRange.entries.size,
                    ),
                    label = { Text(r.label) },
                )
            }
        }

        val hasData = series.any { it.points.isNotEmpty() }
        when {
            metric == TrendMetric.SPO2 && !hcAvailable && !spo2Loading -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Blood oxygen history lives in Health Connect",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Connect Health Connect to pull it in from Samsung Health.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onRequestHcPermissions(viewModel.hcReadPermissions) }) {
                            Text("Connect Health Connect")
                        }
                    }
                }
            }
            !hasData -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (metric) {
                            TrendMetric.HEART_RATE, TrendMetric.STRESS ->
                                "No recordings yet. Turn on \"Record heart rate continuously\" " +
                                    "in Settings to start building history."
                            else -> "No data for this range yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            else -> {
                TrendChart(
                    series = series,
                    xMin = start,
                    xMax = now,
                    range = range,
                )
                // Min / max / avg summary.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    series.forEach { s ->
                        if (s.points.isNotEmpty()) {
                            val ys = s.points.map { it.y }
                            val avg = ys.average()
                            val min = ys.min()
                            val max = ys.max()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Canvas(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .width(24.dp)
                                        .height(4.dp)
                                ) {
                                    drawLine(
                                        s.color,
                                        Offset(0f, size.height / 2),
                                        Offset(size.width, size.height / 2),
                                        strokeWidth = size.height,
                                        cap = StrokeCap.Round,
                                    )
                                }
                                Text(
                                    "${s.label}: avg ${fmt(avg)}${s.unit} · " +
                                        "min ${fmt(min)} · max ${fmt(max)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (metric == TrendMetric.HEART_RATE || metric == TrendMetric.STRESS) {
            Text(
                "Recorded by your watch every 10 minutes while continuous " +
                    "recording is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(Locale.US, "%.1f", v)

private fun fmt(v: Float): String = fmt(v.toDouble())

/**
 * Hand-rolled line chart: axes, gridlines, line + fill. Downsamples to at
 * most 240 points per series so a year of 10-minute samples stays smooth.
 */
@Composable
private fun TrendChart(
    series: List<ChartSeries>,
    xMin: Long,
    xMax: Long,
    range: TrendRange,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val drawn = remember(series) {
        series.map { s -> s.copy(points = downsample(s.points)) }
    }
    val allY = drawn.flatMap { s -> s.points.map { it.y } }
    val rawMin = allY.minOrNull() ?: 0f
    val rawMax = allY.maxOrNull() ?: 1f
    val pad = (rawMax - rawMin).takeIf { it > 0 }?.times(0.12f) ?: 1f
    val yMin = rawMin - pad
    val yMax = rawMax + pad

    val zone = remember { ZoneId.systemDefault() }
    val xFmt = remember(range) {
        when (range) {
            TrendRange.DAY -> DateTimeFormatter.ofPattern("ha")
            TrendRange.WEEK -> DateTimeFormatter.ofPattern("EEE")
            TrendRange.MONTH -> DateTimeFormatter.ofPattern("d MMM")
            TrendRange.YEAR -> DateTimeFormatter.ofPattern("MMM")
        }.withZone(zone)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(8.dp)
        ) {
            val padL = 40.dp.toPx()
            val padB = 26.dp.toPx()
            val padT = 10.dp.toPx()
            val padR = 10.dp.toPx()
            val w = (size.width - padL - padR).coerceAtLeast(1f)
            val h = (size.height - padT - padB).coerceAtLeast(1f)

            fun xToPx(t: Long): Float =
                padL + w * ((t - xMin).toFloat() / (xMax - xMin).toFloat().coerceAtLeast(1f))
            fun yToPx(v: Float): Float =
                padT + h * (1f - (v - yMin) / (yMax - yMin).coerceAtLeast(0.001f))

            val paint = Paint().apply {
                color = labelColor.toArgb()
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }

            // Horizontal gridlines + y labels.
            for (i in 0..4) {
                val y = padT + h * i / 4f
                val value = yMax - (yMax - yMin) * i / 4f
                drawLine(
                    gridColor,
                    Offset(padL, y),
                    Offset(padL + w, y),
                    strokeWidth = 1.dp.toPx(),
                )
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    fmt(value),
                    padL - 6.dp.toPx(),
                    y + 4.dp.toPx(),
                    paint,
                )
            }
            // X ticks.
            paint.textAlign = Paint.Align.CENTER
            for (i in 0..4) {
                val t = xMin + (xMax - xMin) * i / 4
                drawContext.canvas.nativeCanvas.drawText(
                    xFmt.format(Instant.ofEpochMilli(t)),
                    xToPx(t).coerceIn(padL, padL + w),
                    padT + h + 18.dp.toPx(),
                    paint,
                )
            }

            // Series: line, plus a soft fill under a lone series.
            drawn.forEach { s ->
                if (s.points.size < 2) return@forEach
                val path = Path()
                s.points.forEachIndexed { pi, p ->
                    val x = xToPx(p.x)
                    val y = yToPx(p.y)
                    if (pi == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (drawn.size == 1) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(xToPx(s.points.last().x), padT + h)
                        lineTo(xToPx(s.points.first().x), padT + h)
                        close()
                    }
                    drawPath(fill, s.color.copy(alpha = 0.16f))
                }
                drawPath(
                    path,
                    s.color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
                // Dots only when sparse enough to read.
                if (s.points.size <= 60) {
                    s.points.forEach { p ->
                        drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(xToPx(p.x), yToPx(p.y)))
                    }
                }
            }
        }

        // Legend.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            drawn.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .width(20.dp)
                            .height(4.dp)
                    ) {
                        drawLine(
                            s.color,
                            Offset(0f, size.height / 2),
                            Offset(size.width, size.height / 2),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                    }
                    Text(s.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Bucket-average downsampling so dense ranges stay smooth. */
private fun downsample(points: List<ChartPoint>, max: Int = 240): List<ChartPoint> {
    if (points.size <= max) return points
    val bucket = points.size / max.toFloat()
    return (0 until max).map { i ->
        val from = (i * bucket).toInt()
        val to = ((i + 1) * bucket).toInt().coerceAtMost(points.size)
        val slice = points.subList(from, to)
        ChartPoint(
            x = slice.map { it.x }.average().toLong(),
            y = slice.map { it.y }.average().toFloat(),
        )
    }
}

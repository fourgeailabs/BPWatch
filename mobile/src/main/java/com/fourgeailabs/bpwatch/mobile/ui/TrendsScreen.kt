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
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendMetric
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trends metrics. The first four are the v2.0 set; the rest (v2.1) give
 * every home tile a landing spot; the v2.2 additions round out Health
 * Connect coverage. Public so HomeScreen can deep-link here.
 */
enum class TrendMetric(val label: String) {
    HEART_RATE("Heart rate"),
    STRESS("Stress"),
    BLOOD_PRESSURE("Blood pressure"),
    STEPS("Steps"),
    DISTANCE("Distance"),
    CALORIES("Calories"),
    WEIGHT("Weight"),
    SLEEP("Sleep"),
    HYDRATION("Hydration"),
    RESTING_HR("Resting HR"),
    HRV("HRV"),
    BODY_FAT("Body fat"),
    BMI("BMI"),
}

/** True for the Health Connect-backed metrics (STEPS onwards, except BMI which is computed from weight). */
private fun TrendMetric.isHcTrend(): Boolean = when (this) {
    TrendMetric.STEPS, TrendMetric.DISTANCE, TrendMetric.CALORIES,
    TrendMetric.WEIGHT, TrendMetric.SLEEP, TrendMetric.HYDRATION,
    TrendMetric.RESTING_HR, TrendMetric.HRV, TrendMetric.BODY_FAT -> true
    else -> false
}

private fun TrendMetric.toHcTrendMetric(): HcTrendMetric = when (this) {
    TrendMetric.STEPS -> HcTrendMetric.STEPS
    TrendMetric.DISTANCE -> HcTrendMetric.DISTANCE
    TrendMetric.CALORIES -> HcTrendMetric.CALORIES
    TrendMetric.WEIGHT -> HcTrendMetric.WEIGHT
    TrendMetric.SLEEP -> HcTrendMetric.SLEEP
    TrendMetric.HYDRATION -> HcTrendMetric.HYDRATION
    TrendMetric.RESTING_HR -> HcTrendMetric.RESTING_HR
    TrendMetric.HRV -> HcTrendMetric.HRV
    TrendMetric.BODY_FAT -> HcTrendMetric.BODY_FAT
    else -> error("not a Health Connect trend metric: $this")
}

private enum class TrendRange(val label: String, val millis: Long) {
    HOUR("Hour", 24L * 3600_000L),
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
 * v2.0 Trends: history graphs for heart rate, stress and blood pressure,
 * with a metric switcher, a range switcher, and min/max/avg summary.
 * v2.1: every home tile deep-links here (initialMetric), an Hour range
 * shows hourly buckets over the last 24h, and steps/distance/calories/
 * weight/sleep/hydration metrics pull bucketed history from Health Connect.
 * Charts are hand-rolled on Compose Canvas — no chart dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    viewModel: MainViewModel,
    onRequestHcPermissions: (Set<String>) -> Unit,
    initialMetric: TrendMetric? = null,
    onInitialMetricConsumed: () -> Unit = {},
) {
    var metric by remember { mutableStateOf(TrendMetric.HEART_RATE) }
    var range by remember { mutableStateOf(TrendRange.WEEK) }

    // A home tile deep-link: preselect the metric, then clear the request.
    LaunchedEffect(initialMetric) {
        if (initialMetric != null) {
            metric = initialMetric
            onInitialMetricConsumed()
        }
    }

    val now = remember(range) { System.currentTimeMillis() }
    val start = now - range.millis

    val hrSamples by remember(range) {
        viewModel.observeHrRange(start, now)
    }.collectAsState(initial = emptyList())
    val stressSamples by remember(range) {
        viewModel.observeStressRange(start, now)
    }.collectAsState(initial = emptyList())
    val readings by viewModel.readings.collectAsState()
    val profile by viewModel.userProfile.collectAsState()

    var hcAvailable by remember { mutableStateOf(false) }
    var hcTrend by remember { mutableStateOf<List<HcTrendPoint>>(emptyList()) }
    var hcTrendLoading by remember { mutableStateOf(false) }
    LaunchedEffect(range, metric) {
        if (metric.isHcTrend()) {
            // (J) Reset at the start of every load: a fast metric/range
            // switch must never flash the previous metric's stale data while
            // the new load is in flight.
            hcTrend = emptyList()
            hcTrendLoading = true
            hcAvailable = viewModel.isHcTrendsAvailable()
            hcTrend = viewModel.loadHcTrendRange(
                metric.toHcTrendMetric(),
                Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(now),
                // Hourly buckets for the short ranges, daily beyond that.
                bucketHours = if (range == TrendRange.HOUR || range == TrendRange.DAY) 1L else 24L,
            )
            hcTrendLoading = false
        } else if (metric == TrendMetric.BMI) {
            // BMI isn't a Health Connect trend: it's computed from the weight
            // trend plus the profile height, so only the weight trend loads here.
            // (J) Same reset as above — no stale weight data on fast switches.
            hcTrend = emptyList()
            hcTrendLoading = true
            hcAvailable = viewModel.isHcTrendsAvailable()
            hcTrend = viewModel.loadHcTrendRange(
                HcTrendMetric.WEIGHT,
                Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(now),
                // Hourly buckets for the short ranges, daily beyond that.
                bucketHours = if (range == TrendRange.HOUR || range == TrendRange.DAY) 1L else 24L,
            )
            hcTrendLoading = false
        }
    }

    val series: List<ChartSeries> = remember(metric, range, hrSamples, stressSamples, readings, hcTrend, profile, start, now) {
        // Hour range: dense watch samples collapse to hourly averages;
        // sparse metrics just show their sparse points.
        val hrPoints = hrSamples.map { ChartPoint(it.timestamp, it.bpm) }
            .let { if (range == TrendRange.HOUR) bucketHourly(it, start) else it }
        // v2.3.1: stress history merges the 10-minute recorded samples with
        // the per-check stress scores stored on readings (every BP check
        // estimates stress too) — the graph no longer needs continuous
        // recording switched on to show history.
        val stressPoints = (
            stressSamples.map { ChartPoint(it.timestamp, it.score.toFloat()) } +
                readings.mapNotNull { r ->
                    r.stress?.takeIf { it >= 0 && r.timestamp in start..now }
                        ?.let { ChartPoint(r.timestamp, it.toFloat()) }
                }
            ).sortedBy { it.x }
            .let { if (range == TrendRange.HOUR) bucketHourly(it, start) else it }
        when (metric) {
            TrendMetric.HEART_RATE -> listOf(
                ChartSeries(
                    label = "Heart rate",
                    color = Color(0xFFD93025),
                    unit = "bpm",
                    points = hrPoints,
                )
            )
            TrendMetric.STRESS -> listOf(
                ChartSeries(
                    label = "Stress",
                    color = Color(0xFF9334E6),
                    unit = "",
                    points = stressPoints,
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
            TrendMetric.STEPS -> hcSeries("Steps", Color(0xFF1A73E8), "", hcTrend)
            TrendMetric.DISTANCE -> hcSeries("Distance", Color(0xFF9334E6), "mi", hcTrend)
            TrendMetric.CALORIES -> hcSeries("Calories", Color(0xFFEA8600), "kcal", hcTrend)
            TrendMetric.WEIGHT -> hcSeries("Weight", Color(0xFF0B8043), "lb", hcTrend)
            TrendMetric.SLEEP -> hcSeries("Sleep", Color(0xFF3949AB), "h", hcTrend)
            TrendMetric.HYDRATION -> hcSeries("Hydration", Color(0xFF039BE5), "L", hcTrend)
            TrendMetric.RESTING_HR -> hcSeries("Resting HR", Color(0xFFD81B60), "bpm", hcTrend)
            TrendMetric.HRV -> hcSeries("HRV", Color(0xFF00897B), "ms", hcTrend)
            TrendMetric.BODY_FAT -> hcSeries("Body fat", Color(0xFF6D4C41), "%", hcTrend)
            TrendMetric.BMI -> {
                // Computed from the loaded weight trend (lb) and the profile
                // height. No height or no weight data means empty points,
                // which shows the standard "No data" state — nothing faked.
                val heightM = profile.heightCm?.takeIf { it > 0f }?.div(100f)
                val bmiPoints = if (heightM != null) {
                    hcTrend.map { p ->
                        val kg = p.value / 2.20462f
                        ChartPoint(p.timestamp, kg / (heightM * heightM))
                    }
                } else {
                    emptyList()
                }
                listOf(
                    ChartSeries(
                        label = "BMI",
                        color = Color(0xFF5E35B1),
                        unit = "",
                        points = bmiPoints,
                    )
                )
            }
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
        val hcLoading = hcTrendLoading
        when {
            metric.isHcTrend() && !hcAvailable && !hcLoading -> {
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
                            "${metric.label} history lives in Health Connect",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Connect Health Connect to pull it in from Samsung Health.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = {
                            onRequestHcPermissions(viewModel.hcPermissions)
                        }) {
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
                            TrendMetric.HEART_RATE ->
                                "No recordings yet. Turn on \"Record heart rate continuously\" " +
                                    "in Settings to start building history."
                            // v2.3.1: stress history also comes from BP checks,
                            // so the copy no longer points only at recording.
                            TrendMetric.STRESS ->
                                "No stress data yet. Take a BP check on the watch, or turn on " +
                                    "\"Record heart rate continuously\" in Settings for regular samples."
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
        if (metric.isHcTrend()) {
            Text(
                "Pulled from Health Connect. Samsung Health can share these " +
                    "if sync is switched on there.",
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
            TrendRange.HOUR, TrendRange.DAY -> DateTimeFormatter.ofPattern("ha")
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

/** Wraps Health Connect trend points in a ChartSeries. */
private fun hcSeries(
    label: String,
    color: Color,
    unit: String,
    points: List<HcTrendPoint>,
): List<ChartSeries> = listOf(
    ChartSeries(
        label = label,
        color = color,
        unit = unit,
        points = points.map { ChartPoint(it.timestamp, it.value) },
    )
)

/** Averages points into hour buckets anchored at [start] (Hour range). */
private fun bucketHourly(points: List<ChartPoint>, start: Long): List<ChartPoint> {
    val hourMs = 3600_000L
    return points.groupBy { ((it.x - start).coerceAtLeast(0) / hourMs) }
        .map { (idx, ps) ->
            ChartPoint(
                x = start + idx * hourMs,
                y = ps.map { it.y }.average().toFloat(),
            )
        }
        .sortedBy { it.x }
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

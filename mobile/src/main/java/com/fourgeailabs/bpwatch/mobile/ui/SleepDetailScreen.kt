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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
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
import androidx.health.connect.client.records.SleepSessionRecord
import com.fourgeailabs.bpwatch.mobile.MainViewModel
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepDetail
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepStageSegment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Navy = Color(0xFF0A1A33)
private val Crimson = Color(0xFFDC143C)
private val CardBg = Color(0xFF1A1A2E)
private val DeepPurple = Color(0xFF7B61FF)
private val LightPurple = Color(0xFFB39DDB)

/**
 * v2.4.6 sleep detail screen, modelled on the Samsung Health sleep view but
 * in the BPWatch theme. Shows the night's sleep score, stage hypnogram,
 * stage breakdown, and per-metric cards (HR, respiratory, skin temp,
 * snoring). Every card is tappable and opens its detail.
 */
@Composable
fun SleepDetailScreen(
    viewModel: MainViewModel,
    initialDate: LocalDate = LocalDate.now(),
    onBack: () -> Unit,
    onOpenSnore: () -> Unit,
) {
    var wakeDate by remember(initialDate) { mutableStateOf(initialDate) }
    var detail by remember { mutableStateOf<SleepDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var expandedCard by remember { mutableStateOf<String?>(null) }
    // v2.4.6: which sleep factor detail is open (null = main detail).
    var selectedFactor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(wakeDate) {
        loading = true
        detail = viewModel.getSleepDetail(wakeDate)
        loading = false
        selectedFactor = null
        expandedCard = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top bar: back, date navigation.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { wakeDate = wakeDate.minusDays(1); expandedCard = null }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous night", tint = Color.White)
            }
            Text(
                if (wakeDate == LocalDate.now()) "Today" else wakeDate.format(
                    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
                ),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = {
                    if (wakeDate.isBefore(LocalDate.now())) {
                        wakeDate = wakeDate.plusDays(1); expandedCard = null
                    }
                }
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next night",
                    tint = if (wakeDate.isBefore(LocalDate.now())) Color.White
                    else Color.White.copy(alpha = 0.3f),
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
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

        val d = detail
        if (d == null) {
            // Empty state: no sleep data for this night.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No sleep data",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If Samsung Health is tracking your sleep, make sure " +
                            "it is set to share with Health Connect, then " +
                            "check back after your next night.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        // Factor detail sub-screen (tapped from the factors card).
        val factor = selectedFactor
        if (factor != null) {
            SleepFactorDetail(
                factor = factor,
                detail = d,
                onBack = { selectedFactor = null },
            )
            return@Column
        }

        val score = sleepScore(d)
        val scoreLabel = scoreLabel(score)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Score header.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$score",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        scoreLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = scoreColor(score),
                    )
                    Text(
                        "${formatDuration(d.timeInBedMinutes)} in bed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                "${formatDuration(d.actualSleepMinutes)} actual sleep",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
            )

            // --- Sleep score factors.
            ExpandableSleepCard(
                title = "Sleep score factors",
                expanded = expandedCard == "factors",
                onToggle = { expandedCard = if (expandedCard == "factors") null else "factors" },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SleepFactorRow(
                        "Actual sleep time",
                        formatDuration(d.actualSleepMinutes),
                        factorLabel(d.actualSleepMinutes, 360L, 540L),
                        onClick = { selectedFactor = "actual" },
                    )
                    SleepFactorRow(
                        "Deep sleep",
                        formatDuration(d.deepMinutes),
                        factorLabel(d.deepMinutes, 60L, 120L),
                        onClick = { selectedFactor = "deep" },
                    )
                    SleepFactorRow(
                        "REM sleep",
                        formatDuration(d.remMinutes),
                        factorLabel(d.remMinutes, 60L, 150L),
                        onClick = { selectedFactor = "rem" },
                    )
                    SleepFactorRow(
                        "Awake",
                        formatDuration(d.awakeMinutes),
                        if (d.awakeMinutes <= 30) "Excellent" else if (d.awakeMinutes <= 60) "Good" else "Attention",
                        onClick = { selectedFactor = "awake" },
                    )
                    SleepFactorRow(
                        "Sleep latency",
                        d.sleepLatencyMinutes?.let { formatDuration(it) } ?: "No data",
                        d.sleepLatencyMinutes?.let {
                            if (it <= 30) "Excellent" else if (it <= 45) "Good" else "Attention"
                        } ?: "No data",
                        onClick = { selectedFactor = "latency" },
                    )
                    if (expandedCard == "factors") {
                        Text(
                            "Tap a factor for the full breakdown. " +
                                    "Deep and REM targets follow typical adult ranges.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            // --- Sleep stages hypnogram.
            ExpandableSleepCard(
                title = "Sleep stages",
                expanded = expandedCard == "stages",
                onToggle = { expandedCard = if (expandedCard == "stages") null else "stages" },
            ) {
                Column {
                    if (d.stages.isNotEmpty()) {
                        HypnogramChart(
                            stages = d.stages,
                            start = d.sessionStart,
                            end = d.sessionEnd,
                        )
                        Spacer(Modifier.height(8.dp))
                        // Time axis labels.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatTime(d.sessionStart),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                            Text(
                                formatTime(d.sessionEnd),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        Text(
                            "No stage data for this night.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // Stage breakdown bars.
                    val total = (d.awakeMinutes + d.remMinutes + d.lightMinutes + d.deepMinutes)
                        .takeIf { it > 0 } ?: 1L
                    StageBar("Awake", d.awakeMinutes, total, Color(0xFFFF6B9D))
                    StageBar("REM", d.remMinutes, total, LightPurple)
                    StageBar("Light", d.lightMinutes, total, DeepPurple)
                    StageBar("Deep", d.deepMinutes, total, Color(0xFF4A3AFF))
                    if (expandedCard == "stages") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Typical range",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The typical range for your time awake is based on " +
                                    "your sleep time. Your REM, light sleep, and deep " +
                                    "sleep times are based on your actual sleep time. " +
                                    "The typical ranges are only for your reference — " +
                                    "your sleep needs may be different.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        StageDetailText(
                            "Awake",
                            "Waking during sleep is normal as long as you can " +
                                    "easily fall asleep again. This includes waking up " +
                                    "fully, as well as brief awakenings like when " +
                                    "changing position in your sleep.",
                        )
                        StageDetailText(
                            "REM",
                            "During REM sleep, you have very low muscle tone and " +
                                    "your pulse and breathing are more irregular than " +
                                    "in deep sleep. You may have vivid dreams during " +
                                    "this phase. Most REM sleep happens in the second " +
                                    "half of your sleep, so not getting enough sleep " +
                                    "time can cut your REM sleep short.",
                        )
                        StageDetailText(
                            "Light",
                            "This refers to the time when you drift from being " +
                                    "awake to being asleep and then into a steadier " +
                                    "sleep. During this stage, some body movement is " +
                                    "normal, and you can be easily awakened. It's " +
                                    "typical to spend much of your night in this stage.",
                        )
                        StageDetailText(
                            "Deep",
                            "During deep sleep, your breathing, heartbeat, and " +
                                    "brain waves are at their slowest levels, and your " +
                                    "body goes through important restoration and " +
                                    "physical recovery. For some people, especially " +
                                    "older adults, little or no deep sleep is normal.",
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your total sleep time must be at least 4 hours for " +
                                    "a typical range to be shown for each sleep stage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                    if (expandedCard == "stages" && d.originPackage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Source: ${d.originPackage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            // --- Heart rate during sleep.
            val avgHr = d.avgHeartRateBpm?.toInt()
            val minHr = d.minHeartRateBpm?.toInt()
            val maxHr = d.maxHeartRateBpm?.toInt()
            SleepMetricCard(
                title = "Heart rate",
                subtitle = if (avgHr != null) "Average: $avgHr bpm" else "No data",
                expanded = expandedCard == "hr",
                onToggle = { expandedCard = if (expandedCard == "hr") null else "hr" },
            ) {
                if (avgHr != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = Crimson,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "$avgHr bpm",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (minHr != null && maxHr != null) {
                            Text(
                                "Min $minHr | Max $maxHr",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                    if (expandedCard == "hr") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "About heart rate",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your heart rate during sleep can reveal how your " +
                                    "health and lifestyle choices are affecting you. " +
                                    "Late meals, evening exercise, alcohol, a " +
                                    "circadian rhythm that's out of alignment, " +
                                    "illness, and more can all impact your heart " +
                                    "rate while you're asleep.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "What heart rate does during sleep",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Within a few minutes of falling asleep, your heart " +
                                    "rate gradually slows to its resting rate. As you " +
                                    "move into deep sleep, it slows to about 20 to 30% " +
                                    "below your resting heart rate. During REM sleep, " +
                                    "it can vary depending on your dreams.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    Text(
                        "No heart rate data overlapped this sleep session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            // --- Respiratory rate.
            val avgRr = d.avgRespiratoryRate
            SleepMetricCard(
                title = "Respiratory rate",
                subtitle = if (avgRr != null) "Average: ${"%.1f".format(avgRr)} breaths/min"
                else "No data",
                expanded = expandedCard == "rr",
                onToggle = { expandedCard = if (expandedCard == "rr") null else "rr" },
            ) {
                if (avgRr != null && expandedCard == "rr") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "About respiratory rate",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your respiratory rate during sleep can give you crucial " +
                                "insight into your overall health and well-being. A " +
                                "typical nighttime respiratory rate for healthy " +
                                "adults is 12 to 20 breaths per minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "However, your respiratory rate is highly individualized " +
                                "and may change over time. Track and compare it only " +
                                "with your own averages, not those of others. If you're " +
                                "concerned about a recent change, talk to your doctor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // --- Skin temperature.
            val skinDelta = d.avgSkinTempDeltaC
            SleepMetricCard(
                title = "Skin temperature",
                subtitle = if (skinDelta != null) {
                    val sign = if (skinDelta >= 0) "+" else ""
                    "$sign${"%.1f".format(skinDelta * 9 / 5)}°F vs baseline"
                } else "No data",
                expanded = expandedCard == "skin",
                onToggle = { expandedCard = if (expandedCard == "skin") null else "skin" },
            ) {
                if (skinDelta != null && expandedCard == "skin") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "About skin temperature",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your skin temperature during sleep is shown relative to " +
                                "your recent average, so you can easily see if today's " +
                                "temperature is higher or lower than usual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Why your skin temperature during sleep is important",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your skin is part of your body's temperature regulation " +
                                "system. Before you fall asleep, your core body " +
                                "temperature decreases, and your skin temperature " +
                                "increases to release body heat. Changes in " +
                                "temperature are important for sleep-wake regulation. " +
                                "Skin temperature is influenced by sleep environment, " +
                                "stress, hormonal changes, and illness — if yours is " +
                                "unusually high or low, consider whether your sleep " +
                                "environment is optimal or whether you might have a " +
                                "health condition, such as a cold.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // --- Snoring (our own detection).
            SleepMetricCard(
                title = "Snoring",
                subtitle = "From BPWatch detection",
                expanded = false,
                onToggle = onOpenSnore,
                chevronOnly = true,
            ) {
                Text(
                    "Open the snore screen for last night's episodes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Expandable card used for the sleep factor/stage/metric sections. */
@Composable
private fun ExpandableSleepCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Non-expandable metric card with a chevron that navigates elsewhere. */
@Composable
private fun SleepMetricCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    chevronOnly: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Open",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            if (!chevronOnly) {
                Spacer(Modifier.height(8.dp))
                content()
            } else {
                // Still show the inline hint for the snore card.
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun SleepFactorRow(
    label: String,
    value: String,
    rating: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        Text(
            rating,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ratingColor(rating).copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = "Open detail",
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StageDetailText(stage: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            stage,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun StageBar(label: String, minutes: Long, total: Long, color: Color) {
    val fraction = (minutes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val pct = (fraction * 100).toInt()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$label $pct%",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Hypnogram: sleep stages over time. Rows top to bottom are
 * Awake, REM, Light, Deep.
 */
@Composable
private fun HypnogramChart(
    stages: List<SleepStageSegment>,
    start: Instant,
    end: Instant,
) {
    val totalMs = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(8.dp),
    ) {
        val w = size.width
        val h = size.height
        // 4 lanes: awake, rem, light, deep.
        fun laneY(stage: Int): Float {
            val lane = when (stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE -> 0
                SleepSessionRecord.STAGE_TYPE_REM -> 1
                SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_UNKNOWN -> 2
                SleepSessionRecord.STAGE_TYPE_DEEP -> 3
                else -> 2
            }
            return h * (lane + 0.5f) / 4f
        }
        fun xFor(t: Instant): Float =
            ((t.toEpochMilli() - start.toEpochMilli()).toFloat() / totalMs) * w

        // Lane separators.
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(
                Color.White.copy(alpha = 0.08f),
                Offset(0f, y),
                Offset(w, y),
                strokeWidth = 1f,
            )
        }
        // Stage blocks.
        val path = Path()
        var first = true
        for (s in stages) {
            val x0 = xFor(s.start)
            val x1 = xFor(s.end)
            val y = laneY(s.stage)
            if (first) {
                path.moveTo(x0, y)
                first = false
            } else {
                path.lineTo(x0, y)
            }
            path.lineTo(x1, y)
        }
        drawPath(
            path,
            color = DeepPurple,
            style = Stroke(width = 5f),
        )
        // Fill under the line lightly.
        val fillPath = Path().apply {
            addPath(path)
            lineTo(xFor(end), h)
            lineTo(xFor(start), h)
            close()
        }
        drawPath(fillPath, color = DeepPurple.copy(alpha = 0.15f))
    }
}

// --- Helpers.

private fun formatDuration(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatTime(instant: Instant): String {
    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
    return fmt.format(instant)
}

/** Simple 0-100 sleep score from stage composition. */
private fun sleepScore(d: SleepDetail): Int {
    var score = 0
    // Duration: 7-9h ideal (30 pts).
    val hours = d.actualSleepMinutes / 60.0
    score += when {
        hours >= 7 && hours <= 9 -> 30
        hours >= 6 && hours < 7 -> 22
        hours > 9 && hours <= 10 -> 22
        hours >= 5 && hours < 6 -> 14
        else -> 6
    }
    // Efficiency: actual / time in bed (25 pts).
    val eff = if (d.timeInBedMinutes > 0)
        d.actualSleepMinutes.toDouble() / d.timeInBedMinutes else 0.0
    score += when {
        eff >= 0.9 -> 25
        eff >= 0.85 -> 20
        eff >= 0.8 -> 15
        eff >= 0.7 -> 10
        else -> 5
    }
    // Deep: 13-23% typical (20 pts).
    val deepPct = if (d.actualSleepMinutes > 0)
        d.deepMinutes.toDouble() / d.actualSleepMinutes else 0.0
    score += when {
        deepPct in 0.13..0.30 -> 20
        deepPct in 0.08..0.13 -> 14
        deepPct > 0.30 -> 14
        else -> 7
    }
    // REM: 20-25% typical (15 pts).
    val remPct = if (d.actualSleepMinutes > 0)
        d.remMinutes.toDouble() / d.actualSleepMinutes else 0.0
    score += when {
        remPct in 0.18..0.30 -> 15
        remPct in 0.12..0.18 -> 10
        else -> 5
    }
    // Awake time (10 pts).
    score += when {
        d.awakeMinutes <= 20 -> 10
        d.awakeMinutes <= 40 -> 7
        d.awakeMinutes <= 60 -> 4
        else -> 2
    }
    return score.coerceIn(0, 100)
}

private fun scoreLabel(score: Int): String = when {
    score >= 90 -> "Excellent"
    score >= 80 -> "Good"
    score >= 60 -> "Fair"
    else -> "Attention"
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> Color(0xFF34A853)
    score >= 80 -> Color(0xFF7B61FF)
    score >= 60 -> Color(0xFFFFB300)
    else -> Crimson
}

private fun ratingColor(rating: String): Color = when (rating) {
    "Excellent" -> Color(0xFF34A853)
    "Good" -> Color(0xFF7B61FF)
    "Fair" -> Color(0xFFFFB300)
    else -> Crimson
}

private fun factorLabel(minutes: Long, lowOk: Long, highOk: Long): String = when {
    minutes in lowOk..highOk -> "Good"
    minutes < lowOk -> "Attention"
    else -> "Fair"
}

/**
 * v2.4.6: detail view for one sleep factor, matching the Samsung Health
 * factor pages. Tapped from the Sleep score factors card.
 */
@Composable
private fun SleepFactorDetail(
    factor: String,
    detail: SleepDetail,
    onBack: () -> Unit,
) {
    val efficiency = if (detail.timeInBedMinutes > 0)
        (detail.actualSleepMinutes.toDouble() / detail.timeInBedMinutes * 100).toInt()
    else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Back row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }

        when (factor) {
            "actual" -> FactorDetailContent(
                title = "Actual sleep time",
                rating = factorLabel(detail.actualSleepMinutes, 360L, 540L),
                valueLine = "• ${formatDuration(detail.actualSleepMinutes)}",
                efficiencyLine = "• Sleep efficiency: $efficiency%",
                visual = null,
                body = "How much sleep we each need varies from person to " +
                        "person, but most adults need 7 to 8 hours to feel " +
                        "refreshed the next day and to keep their bodies " +
                        "healthy. Getting the right amount of total sleep " +
                        "helps you get enough deep and REM sleep.",
            )
            "deep" -> FactorDetailContent(
                title = "Deep sleep",
                rating = factorLabel(detail.deepMinutes, 60L, 120L),
                valueLine = "• ${formatDuration(detail.deepMinutes)}/" +
                        formatDuration(detail.actualSleepMinutes),
                efficiencyLine = "• Sleep efficiency: $efficiency%",
                visual = {
                    HypnogramChart(
                        stages = detail.stages,
                        start = detail.sessionStart,
                        end = detail.sessionEnd,
                    )
                },
                body = "During deep sleep, our bodies regenerate cells and " +
                        "tissues to recover from daytime fatigue. Generally, " +
                        "10-20% of adults' sleep time is spent in deep sleep, " +
                        "which occurs mainly at the beginning of a sleep period.",
            )
            "rem" -> FactorDetailContent(
                title = "REM sleep",
                rating = factorLabel(detail.remMinutes, 60L, 150L),
                valueLine = "• ${formatDuration(detail.remMinutes)}/" +
                        formatDuration(detail.actualSleepMinutes),
                efficiencyLine = "• Sleep efficiency: $efficiency%",
                visual = {
                    HypnogramChart(
                        stages = detail.stages,
                        start = detail.sessionStart,
                        end = detail.sessionEnd,
                    )
                },
                body = "REM sleep is a vital part of your body's recovery " +
                        "process. It relieves mental fatigue, helps improve " +
                        "learning and memory, and stabilises emotions. REM " +
                        "sleep accounts for 20-30% of the sleep period.",
            )
            "awake" -> FactorDetailContent(
                title = "Awake",
                rating = if (detail.awakeMinutes <= 30) "Excellent"
                else if (detail.awakeMinutes <= 60) "Good" else "Attention",
                valueLine = "• ${formatDuration(detail.awakeMinutes)}/" +
                        formatDuration(detail.timeInBedMinutes),
                efficiencyLine = null,
                visual = {
                    HypnogramChart(
                        stages = detail.stages,
                        start = detail.sessionStart,
                        end = detail.sessionEnd,
                    )
                },
                body = "It is natural to wake up or toss and turn during " +
                        "sleep. However, if you often cannot fall back " +
                        "asleep after waking up, see if there is something " +
                        "you can do to get better sleep.",
            )
            "latency" -> {
                val latency = detail.sleepLatencyMinutes
                FactorDetailContent(
                    title = "Sleep latency",
                    rating = latency?.let {
                        if (it <= 30) "Excellent"
                        else if (it <= 45) "Good" else "Attention"
                    } ?: "No data",
                    valueLine = latency?.let { "• ${formatDuration(it)}" }
                        ?: "• No data",
                    efficiencyLine = null,
                    visual = {
                        if (latency != null) {
                            LatencyRangeBar(latency)
                        }
                    },
                    body = "Sleep latency is the amount of time it takes " +
                            "for you to fall asleep after going to bed. " +
                            "While an average sleep latency of 5-30 minutes " +
                            "is considered typical, if it often takes you " +
                            "longer than 30 minutes to fall asleep, it's a " +
                            "good idea to see if there are things you can do " +
                            "to fall asleep faster. For example, using a phone " +
                            "in bed right before going to sleep can make it " +
                            "take longer for you to fall asleep.",
                    extraSections = listOf(
                        "Do you use your phone a lot before bed?" to
                                "It's best to keep your sleeping environment " +
                                "just for sleeping and to go to bed only when " +
                                "you're feeling drowsy. If you have trouble " +
                                "falling asleep, why not try a relaxing " +
                                "breathing exercise?",
                        "About breathing exercises" to
                                "On the other hand, if you often fall asleep " +
                                "within 5 minutes of going to bed, it could be " +
                                "a sign that you're overtired. Consider going " +
                                "to bed earlier or getting up later to make " +
                                "sure you get the total amount of sleep you " +
                                "need to feel refreshed when you wake up.",
                        "Caffeine" to
                                "Caffeine can cause you to wake up more often " +
                                "during the night. Generally, try to avoid " +
                                "caffeine 6 to 9 hours before bedtime. The " +
                                "more sensitive you are to caffeine's effects, " +
                                "the earlier in the day you should stop having " +
                                "caffeine.",
                        "Sleep-related breathing disorders" to
                                "Health conditions like sleep apnea can cause " +
                                "disrupted breathing and make it hard to get " +
                                "restful sleep. Snoring, pauses in breathing, " +
                                "and gasping awake are common symptoms of sleep " +
                                "apnea, which can be evaluated and treated with " +
                                "help from a qualified healthcare provider.",
                    ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FactorDetailContent(
    title: String,
    rating: String,
    valueLine: String,
    efficiencyLine: String?,
    visual: (@Composable () -> Unit)?,
    body: String,
    extraSections: List<Pair<String, String>> = emptyList(),
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                rating,
                style = MaterialTheme.typography.headlineSmall,
                color = ratingColor(rating),
            )
            if (visual != null) {
                Spacer(Modifier.height(12.dp))
                visual()
            }
            Spacer(Modifier.height(12.dp))
            Text(
                valueLine,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            if (efficiencyLine != null) {
                Text(
                    efficiencyLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            for ((heading, text) in extraSections) {
                Spacer(Modifier.height(16.dp))
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Optimal-range bar for sleep latency (5m-30m typical). */
@Composable
private fun LatencyRangeBar(latencyMinutes: Long) {
    val max = 60L
    val fraction = (latencyMinutes.toFloat() / max).coerceIn(0f, 1f)
    // Optimal zone: 5-30m.
    val optStart = 5f / max
    val optEnd = 30f / max
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            // Optimal range highlight.
            Box(
                modifier = Modifier
                    .fillMaxWidth(optEnd - optStart)
                    .height(14.dp)
                    .padding(start = (optStart * 100).dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            )
            // Marker.
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(20.dp)
                    .padding(start = (fraction * 100).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DeepPurple)
                    .align(Alignment.CenterStart),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "5m",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = (optStart * 100).dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "30m",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(end = ((1 - optEnd) * 100).dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Optimal range",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

package com.fourgeailabs.bpwatch.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.HealthConnectClient
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import com.fourgeailabs.bpwatch.mobile.data.HealthLog
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendMetric
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendPoint
import com.fourgeailabs.bpwatch.mobile.healthconnect.TodayMetrics
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs
import com.fourgeailabs.bpwatch.mobile.profile.ProfileStore
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile
import com.fourgeailabs.bpwatch.mobile.wearable.WatchConfigSender
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BpRepository.get(app)
    private val hc = HealthConnectManager(app)
    private val profileStore = ProfileStore(app)
    private val monitoringPrefs = MonitoringPrefs(app)

    val readings: StateFlow<List<Reading>> =
        repo.readings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest: StateFlow<Reading?> =
        repo.latest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val calibrationPoints: StateFlow<List<CalibrationPoint>> =
        repo.calibrationPoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calibrationModel: StateFlow<CalibrationModel?> =
        repo.calibrationModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val userProfile: StateFlow<UserProfile> = profileStore.profile
    val monitoringConfig: StateFlow<MonitoringConfig> = monitoringPrefs.config

    var spo2: Int? by mutableStateOf(null)
        private set
    var hcAvailable: Boolean by mutableStateOf(hc.isAvailable)
        private set
    var hcGranted: Boolean by mutableStateOf(false)
        private set
    /** Raw Health Connect status for diagnostics, e.g. "needs Health Connect update". */
    var hcStatusText: String by mutableStateOf(hc.sdkStatusText())
        private set
    /** True when Health Connect needs a Play Store update before it can work. */
    var hcNeedsUpdate: Boolean by mutableStateOf(
        hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
    )
        private set
    /** Per-permission Android runtime status, for diagnostics. */
    /** Permission strings Health Connect itself reports as granted (v2.2). */
    var hcGrantedSet: Set<String> by mutableStateOf(emptySet())
        private set
    /**
     * SpO2 diagnostic line for Settings, e.g. "SpO2 records (7d): 14 ·
     * newest 2h ago" or why there are none. Empty when Health Connect isn't
     * readable yet.
     */
    var spo2Diagnostic: String by mutableStateOf("")
        private set
    var latestWatchHr: Float? by mutableStateOf(null)
        private set

    // ------------------------------------------------------------------
    // v2.0 dashboard: Health Connect tiles + day-grouped timeline.
    // ------------------------------------------------------------------

    private val _dashboard = MutableStateFlow(DashboardMetrics())
    /** Today's headline metrics; refreshed on launch, on resume of Home,
     * and after every log. Nulls mean "no data". */
    val dashboard: StateFlow<DashboardMetrics> = _dashboard

    /** BP readings + manual logs, newest first, grouped by day. */
    val timelineDays: StateFlow<List<TimelineDay>> =
        combine(readings, repo.healthLogs) { rs, logs -> buildTimeline(rs, logs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hcPermissions: Set<String> get() = hc.permissions
    val hcReadPermissions: Set<String> get() = hc.readPermissions

    init {
        refreshHealthConnect()
        refreshLatestWatchHr()
        refreshDashboard()
    }

    fun refreshHealthConnect() {
        hcStatusText = hc.sdkStatusText()
        hcNeedsUpdate =
            hc.sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        viewModelScope.launch {
            hcGranted = try {
                hc.isAvailable && hc.hasPermissions()
            } catch (_: Exception) {
                false
            }
            hcGrantedSet = try {
                if (hc.isAvailable) hc.grantedPermissions() else emptySet()
            } catch (_: Exception) {
                emptySet()
            }
            // SpO2 reads only need the read grant — never the BP write one.
            val readGranted = try {
                hc.isAvailable && hc.hasReadPermissions()
            } catch (_: Exception) {
                false
            }
            spo2 = try {
                if (readGranted) hc.readLatestSpo2() else null
            } catch (_: Exception) {
                null
            }
            spo2Diagnostic = try {
                when {
                    !hc.isAvailable -> ""
                    !readGranted -> "SpO2: read permission not granted yet."
                    else -> {
                        val stats = hc.readSpo2Stats()
                        when {
                            stats == null -> "SpO2: query timed out — Health Connect isn't responding."
                            stats.count == 0 -> "SpO2: no records in the last 7 days — " +
                                "check Samsung Health → Settings → Health Connect sharing, " +
                                "and turn on “Blood oxygen during sleep”."
                            else -> "SpO2 records (7d): ${stats.count} · " +
                                "newest ${stats.newest?.let { timeAgo(it) } ?: "?"}"
                        }
                    }
                }
            } catch (_: Exception) {
                "SpO2: couldn't read records."
            }
        }
    }

    fun onHcPermissionResult() = refreshHealthConnect()

    /**
     * (Re)loads today's Health Connect metrics for the dashboard tiles.
     * Safe to call often: failures just leave the previous values in place.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            val readGranted = try {
                hc.isAvailable && hc.hasDashboardReads()
            } catch (_: Exception) {
                false
            }
            if (!readGranted) {
                _dashboard.value = DashboardMetrics(hcReadGranted = false)
                return@launch
            }
            val t: TodayMetrics? = try {
                hc.readTodayMetrics()
            } catch (_: Exception) {
                null
            }
            val spo2Now = try {
                hc.readLatestSpo2()
            } catch (_: Exception) {
                null
            }
            // v2.2: prefer the watch's own step count when it reported today;
            // fall back to Health Connect steps otherwise.
            val watchStepsToday = try {
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                AppDatabase.get(getApplication()).watchStepsDao().stepsForDate(today)
            } catch (_: Exception) {
                null
            }
            // v2.2 BMI: profile height + latest weight (Health Connect first,
            // profile fallback). Nothing faked — null when either is missing.
            val profile = userProfile.value
            val heightCm = profile.heightCm
            val weightKg = t?.weightKg ?: profile.weightKg?.toDouble()
            val bmi = if (heightCm != null && heightCm > 0f &&
                weightKg != null && weightKg > 0
            ) {
                val m = heightCm / 100.0
                weightKg / (m * m)
            } else {
                null
            }
            val bmiLabel = when {
                bmi == null -> null
                bmi < 18.5 -> "Underweight"
                bmi < 25.0 -> "Healthy"
                bmi < 30.0 -> "Overweight"
                else -> "Obese"
            }
            _dashboard.value = DashboardMetrics(
                steps = watchStepsToday ?: t?.steps,
                distanceMi = t?.distanceMeters?.let { it / 1609.344 },
                caloriesKcal = t?.caloriesKcal,
                heartRateBpm = t?.heartRateBpm?.toInt(),
                weightLb = t?.weightKg?.let { it * 2.20462 },
                sleepHours = t?.sleepHours,
                hydrationMl = t?.hydrationLiters?.let { it * 1000.0 },
                spo2 = spo2Now,
                bmi = bmi,
                bmiLabel = bmiLabel,
                hcReadGranted = true,
            )
        }
    }

    /** Logs weight (lb): Health Connect first, Room always (timeline source). */
    fun logWeightLb(lb: Double, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val now = Instant.now()
            val kg = lb / 2.20462
            val hcOk = try {
                hc.writeWeight(kg, now); true
            } catch (_: Exception) {
                false
            }
            repo.addHealthLog(
                HealthLog(
                    timestamp = now.toEpochMilli(),
                    kind = HealthLogKind.WEIGHT,
                    value = lb,
                    label = "${trimNum(lb)} lb",
                )
            )
            refreshDashboard()
            onDone(hcOk)
        }
    }

    /** Logs hydration (ml): Health Connect first, Room always. */
    fun logHydrationMl(ml: Double, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val now = Instant.now()
            val hcOk = try {
                hc.writeHydration(ml / 1000.0, now); true
            } catch (_: Exception) {
                false
            }
            repo.addHealthLog(
                HealthLog(
                    timestamp = now.toEpochMilli(),
                    kind = HealthLogKind.HYDRATION,
                    value = ml,
                    label = "${trimNum(ml)} ml",
                )
            )
            refreshDashboard()
            onDone(hcOk)
        }
    }

    /** Logs food (kcal + meal type): Health Connect first, Room always. */
    fun logFoodKcal(kcal: Double, mealType: Int, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val now = Instant.now()
            val hcOk = try {
                hc.writeFood(kcal, mealType, now); true
            } catch (_: Exception) {
                false
            }
            repo.addHealthLog(
                HealthLog(
                    timestamp = now.toEpochMilli(),
                    kind = HealthLogKind.FOOD,
                    value = kcal,
                    label = "${trimNum(kcal)} kcal · ${mealName(mealType)}",
                )
            )
            refreshDashboard()
            onDone(hcOk)
        }
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

    private fun mealName(mealType: Int): String = when (mealType) {
        HealthConnectManager.FoodMeal.BREAKFAST -> "Breakfast"
        HealthConnectManager.FoodMeal.LUNCH -> "Lunch"
        HealthConnectManager.FoodMeal.DINNER -> "Dinner"
        else -> "Snack"
    }

    /**
     * Merges BP readings and manual logs into day groups, newest first.
     * Only readings with an actual BP value appear (pure HR/SpO2 rows live
     * in the History tab).
     */
    private fun buildTimeline(
        readings: List<Reading>,
        logs: List<HealthLog>,
    ): List<TimelineDay> {
        val zone = ZoneId.systemDefault()
        val entries = mutableListOf<TimelineEntry>()
        readings
            .filter { it.sysEstimate != null || it.sysCuff != null }
            .forEach { r ->
                val sys = r.sysEstimate ?: r.sysCuff!!
                val dia = r.diaEstimate ?: r.diaCuff!!
                val estimated = r.sysEstimate != null
                entries += TimelineEntry(
                    kind = TimelineKind.BP,
                    title = "Blood pressure $sys/$dia",
                    detail = (if (estimated) "Estimated" else "Cuff reading") +
                        (r.heartRate?.let { " · ${it.toInt()} bpm" } ?: ""),
                    timestamp = r.timestamp,
                )
            }
        logs.forEach { l ->
            entries += TimelineEntry(
                kind = when (l.kind) {
                    HealthLogKind.WEIGHT -> TimelineKind.WEIGHT
                    HealthLogKind.HYDRATION -> TimelineKind.HYDRATION
                    else -> TimelineKind.FOOD
                },
                title = when (l.kind) {
                    HealthLogKind.WEIGHT -> "You logged weight"
                    HealthLogKind.HYDRATION -> "You logged hydration"
                    else -> "You logged food"
                },
                detail = l.label,
                timestamp = l.timestamp,
            )
        }
        val sorted = entries.sortedByDescending { it.timestamp }.take(60)
        if (sorted.isEmpty()) return emptyList()
        val today = LocalDate.now(zone)
        val dayFmt = DateTimeFormatter.ofPattern("d MMM").withZone(zone)
        return sorted.groupBy {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
        }.map { (date, dayEntries) ->
            val label = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> dayFmt.format(date.atStartOfDay(zone))
            }
            TimelineDay(label, dayEntries)
        }
    }

    fun refreshLatestWatchHr() {
        viewModelScope.launch {
            latestWatchHr = try {
                repo.dao.latestWatchReading()?.heartRate
            } catch (_: Exception) {
                null
            }
        }
    }

    fun addCalibrationPoint(sys: Int, dia: Int, hr: Float) {
        viewModelScope.launch {
            repo.addCalibrationPoint(CalibrationPoint(hr, sys, dia))
            refreshLatestWatchHr()
        }
    }

    fun removeCalibrationPoint(index: Int) {
        viewModelScope.launch { repo.calibrationStore.removePoint(index) }
    }

    fun clearCalibration() {
        viewModelScope.launch { repo.clearCalibration() }
    }

    fun addManualSpo2(value: Int) {
        viewModelScope.launch {
            repo.addManualSpo2(value)
            spo2 = value
        }
    }

    fun saveProfile(profile: UserProfile) {
        profileStore.save(profile)
    }

    /**
     * Applies a monitoring-settings change, persists it, and pushes it to
     * every connected watch immediately.
     */
    fun updateMonitoring(transform: (MonitoringConfig) -> MonitoringConfig) {
        val updated = monitoringPrefs.update(transform)
        viewModelScope.launch {
            WatchConfigSender.sendToAll(getApplication(), updated)
        }
    }

    /** Re-sends the current monitoring config (e.g. manual refresh). */
    fun pushMonitoringConfig() {
        viewModelScope.launch {
            WatchConfigSender.sendToAll(getApplication(), monitoringPrefs.config.value)
        }
    }

    fun isMonitoringConfigured(): Boolean = monitoringPrefs.isConfigured()

    // ------------------------------------------------------------------
    // Continuous HR + stress recording (v2.0, opt-in, default off).
    // ------------------------------------------------------------------

    val recordHr: StateFlow<Boolean> = monitoringPrefs.recordHr

    /** Persists the toggle and pushes it to the watch immediately. */
    fun setRecordHr(enabled: Boolean) {
        monitoringPrefs.setRecordHr(enabled)
        viewModelScope.launch {
            try {
                WatchConfigSender.sendRecordSet(getApplication(), enabled)
            } catch (_: Exception) {
                // The flag is re-sent on the next full sync (peer connect),
                // so the watch converges even if it's unreachable right now.
            }
        }
    }

    /** True when Health Connect is available and the SpO2 read is granted. */
    suspend fun isHcSpO2Available(): Boolean = try {
        hc.isAvailable && hc.hasReadPermissions()
    } catch (_: Exception) {
        false
    }

    /** SpO2 samples over [start, end] for the Trends chart. Empty when Health
     * Connect isn't usable — never throws. */
    suspend fun loadSpo2Range(
        start: java.time.Instant,
        end: java.time.Instant,
    ): List<com.fourgeailabs.bpwatch.mobile.healthconnect.Spo2Sample> {
        return try {
            if (!isHcSpO2Available()) emptyList()
            else hc.readSpo2Range(start, end) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Trends (v2.0): recorded-sample ranges for the history graphs.
    // ------------------------------------------------------------------

    fun observeHrRange(start: Long, end: Long) =
        repo.sampleDao.observeHrRange(start, end)

    fun observeStressRange(start: Long, end: Long) =
        repo.sampleDao.observeStressRange(start, end)

    /**
     * True when Health Connect is available and every read the v2.1 Trends
     * graphs need is granted. Uses the dashboard read set — no new
     * permissions were added for the new metrics.
     */
    suspend fun isHcTrendsAvailable(): Boolean = try {
        hc.isAvailable && hc.hasDashboardReads()
    } catch (_: Exception) {
        false
    }

    /**
     * Bucketed Health Connect history for a Trends metric. Empty when Health
     * Connect isn't usable — never throws.
     */
    suspend fun loadHcTrendRange(
        metric: HcTrendMetric,
        start: java.time.Instant,
        end: java.time.Instant,
        bucketHours: Long,
    ): List<HcTrendPoint> {
        return try {
            if (!isHcTrendsAvailable()) emptyList()
            else hc.readHcTrend(metric, start, end, bucketHours) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun timeAgo(instant: java.time.Instant): String {
        val mins = java.time.Duration.between(instant, java.time.Instant.now())
            .toMinutes().coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}

/** HealthLog kinds. */
object HealthLogKind {
    const val WEIGHT = "weight"
    const val HYDRATION = "hydration"
    const val FOOD = "food"
}

/**
 * v2.0 dashboard state. Nulls mean "no data" — the tiles show "No data"
 * rather than guessing.
 */
data class DashboardMetrics(
    val steps: Long? = null,
    val distanceMi: Double? = null,
    val caloriesKcal: Double? = null,
    val heartRateBpm: Int? = null,
    val weightLb: Double? = null,
    val sleepHours: Double? = null,
    val hydrationMl: Double? = null,
    val spo2: Int? = null,
    /** v2.2: derived from profile height + latest weight (HC preferred). */
    val bmi: Double? = null,
    val bmiLabel: String? = null,
    val hcReadGranted: Boolean = false,
)

/** Timeline entry kinds; the UI maps these to icons. */
enum class TimelineKind { BP, WEIGHT, HYDRATION, FOOD }

/** One row in the Home timeline. */
data class TimelineEntry(
    val kind: TimelineKind,
    val title: String,
    val detail: String,
    val timestamp: Long,
)

/** One day group in the Home timeline ("Today", "Yesterday", "18 Sep"...). */
data class TimelineDay(
    val label: String,
    val entries: List<TimelineEntry>,
)

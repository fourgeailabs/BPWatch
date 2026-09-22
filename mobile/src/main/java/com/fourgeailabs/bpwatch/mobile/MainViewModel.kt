package com.fourgeailabs.bpwatch.mobile

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import com.fourgeailabs.bpwatch.mobile.data.SnoreEvent
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendMetric
import com.fourgeailabs.bpwatch.mobile.healthconnect.HcTrendPoint
import com.fourgeailabs.bpwatch.mobile.healthconnect.SleepDiagnosis
import com.fourgeailabs.bpwatch.mobile.healthconnect.TodayMetrics
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringConfig
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs
import com.fourgeailabs.bpwatch.mobile.profile.ProfileStore
import com.fourgeailabs.bpwatch.mobile.profile.UserProfile
import com.fourgeailabs.bpwatch.mobile.snore.SnoreScheduler
import com.fourgeailabs.bpwatch.mobile.snore.SnoreService
import com.fourgeailabs.bpwatch.mobile.snore.SnoreState
import com.fourgeailabs.bpwatch.mobile.snore.SnoreStorage
import com.fourgeailabs.bpwatch.mobile.wearable.BpCheckState
import com.fourgeailabs.bpwatch.mobile.wearable.WatchConfigSender
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    var latestWatchHr: Float? by mutableStateOf(null)
        private set

    // ------------------------------------------------------------------
    // v2.3 snore detection: phone microphone, overnight 22:00–07:00 window.
    // ------------------------------------------------------------------
    val snoreEnabled: StateFlow<Boolean> = monitoringPrefs.snoreDetection
    val snoreStatus: StateFlow<String?> = SnoreState.status
    val snoreListening: StateFlow<Boolean> = SnoreState.listening
    /** Last night's (22:00–07:00) snore count; null = not loaded yet. */
    var lastNightSnoreCount: Int? by mutableStateOf(null)
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
        // v2.3 snore detection: prune old clips on app start, keep the
        // 22:00/07:00 alarms armed (the OS clears them on reboot), and start
        // listening when the feature is on and we're inside the window.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SnoreStorage.prune(getApplication())
            } catch (_: Exception) {
            }
        }
        SnoreScheduler.ensureScheduled(getApplication())
        maybeStartSnoreService()
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
        }
    }

    fun onHcPermissionResult() = refreshHealthConnect()

    // ------------------------------------------------------------------
    // v2.3 snore detection controls.
    // ------------------------------------------------------------------

    fun isMicGranted(): Boolean =
        try {
            ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /**
     * Called from the Settings toggle. Turning on requires microphone
     * permission and hardware; when the permission isn't granted yet the
     * toggle stays off and MainActivity launches the runtime request —
     * [onMicPermissionResult] retries the toggle on grant.
     */
    fun onSnoreToggle(want: Boolean) {
        val app = getApplication<Application>()
        if (!want) {
            monitoringPrefs.setSnoreDetection(false)
            SnoreScheduler.cancel(app)
            try {
                app.stopService(Intent(app, SnoreService::class.java))
            } catch (_: Exception) {
            }
            SnoreState.post(null)
            return
        }
        val hasMic = try {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        } catch (_: Exception) {
            false
        }
        if (!hasMic) {
            SnoreState.post("Microphone unavailable on this device.")
            return
        }
        if (!isMicGranted()) {
            SnoreState.post("Microphone permission needed — allow it to start listening.")
            return
        }
        monitoringPrefs.setSnoreDetection(true)
        SnoreScheduler.schedule(app)
        SnoreState.post(
            if (SnoreScheduler.inWindow()) "Listening now — stops at 07:00."
            else "Scheduled — listening starts at 22:00."
        )
        maybeStartSnoreService()
    }

    /** Result of the runtime microphone permission request from MainActivity. */
    fun onMicPermissionResult(granted: Boolean) {
        if (granted) {
            onSnoreToggle(true)
        } else {
            SnoreState.post("Microphone permission denied — snore detection stays off.")
        }
    }

    /**
     * Starts listening right now, from a user action (Settings "Start
     * listening now" or opening the app during the window). A start from a
     * visible activity is the one path the OS always allows on Android 14+,
     * where background microphone service starts are blocked.
     */
    fun startSnoreNow() {
        val app = getApplication<Application>()
        if (!monitoringPrefs.snoreDetection.value) return
        if (SnoreService.isRunning) return
        if (!SnoreService.canRun(app)) {
            SnoreState.post("Microphone unavailable.")
            return
        }
        try {
            val intent = Intent(app, SnoreService::class.java).setAction(SnoreScheduler.ACTION_START)
            ContextCompat.startForegroundService(app, intent)
            SnoreState.post("Listening now — stops at 07:00.")
        } catch (_: Exception) {
            SnoreState.post("Couldn't start listening — try again.")
        }
    }

    /**
     * App-open auto-start: when the feature is on and we're inside the
     * overnight window, begin listening immediately.
     */
    private fun maybeStartSnoreService() {
        try {
            if (!monitoringPrefs.snoreDetection.value) return
            if (!SnoreScheduler.inWindow()) return
            startSnoreNow()
        } catch (_: Exception) {
        }
    }

    /** Last night's snore events, oldest first — for the Snore detail screen. */
    suspend fun snoreEventsLastNight(): List<SnoreEvent> =
        try {
            val (nightStart, nightEnd) = SnoreScheduler.lastNightWindow()
            AppDatabase.get(getApplication()).snoreDao().eventsBetween(nightStart, nightEnd)
        } catch (_: Exception) {
            emptyList()
        }

    /** Reactive snore events in [start, end) for the Snore charts. */
    fun observeSnoreEvents(start: Long, end: Long) =
        repo.snoreDao.observeBetween(start, end)

    private val _refreshing = MutableStateFlow(false)
    /**
     * True while a dashboard refresh is running (e.g. pull-to-refresh on
     * Home). Guards against overlapping refreshes.
     */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * (Re)loads today's Health Connect metrics for the dashboard tiles.
     * Safe to call often: failures just leave the previous values in place.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            if (_refreshing.value) return@launch
            _refreshing.value = true
            try {
                refreshDashboardNow()
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** The actual reload behind [refreshDashboard]; runs inside a coroutine. */
    private suspend fun refreshDashboardNow() {
        // v2.3 Home snoring card: last night's 22:00–07:00 snore count.
        // Room-local, so it loads whether or not Health Connect is set up.
        lastNightSnoreCount = try {
            val (nightStart, nightEnd) = SnoreScheduler.lastNightWindow()
            AppDatabase.get(getApplication()).snoreDao()
                .countBetween(nightStart, nightEnd)
        } catch (_: Exception) {
            null
        }
        val readGranted = try {
            hc.isAvailable && hc.hasDashboardReads()
        } catch (_: Exception) {
            false
        }
        if (!readGranted) {
            _dashboard.value = DashboardMetrics(hcReadGranted = false)
            return
        }
        val t: TodayMetrics? = try {
            hc.readTodayMetrics()
        } catch (_: Exception) {
            null
        }
        // v2.3.1: the watch's own step count for today, used as a fallback
        // when Health Connect has no merged steps to offer.
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
        // v2.3 Home stress tile: the newest available score between the
        // recorded stress samples and the newest stress-bearing Reading.
        // Nothing faked — null when both are empty.
        val stress = try {
            val db = AppDatabase.get(getApplication())
            val sample = db.sampleDao().latestStressSample()
            val reading = db.readingDao().latestStressReading()
            when {
                sample == null && reading == null -> null
                reading == null -> sample?.score
                sample == null -> reading.stress
                else -> if (sample.timestamp >= reading.timestamp) {
                    sample.score
                } else {
                    reading.stress
                }
            }
        } catch (_: Exception) {
            null
        }
        _dashboard.value = DashboardMetrics(
            // v2.3.1: prefer Health Connect's merged cross-device steps
            // (phone + watch, matches Samsung Health); the watch-only
            // count is the fallback.
            steps = t?.steps ?: watchStepsToday,
            distanceMi = t?.distanceMeters?.let { it / 1609.344 },
            caloriesKcal = t?.caloriesKcal,
            heartRateBpm = t?.heartRateBpm?.toInt(),
            weightLb = t?.weightKg?.let { it * 2.20462 },
            sleepHours = t?.sleepHours,
            hydrationMl = t?.hydrationLiters?.let { it * 1000.0 },
            stress = stress,
            bmi = bmi,
            bmiLabel = bmiLabel,
            hcReadGranted = true,
        )
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
     * Only readings with an actual BP value appear (pure HR rows live
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

    /**
     * v2.3: phone-triggered BP check. Asks every connected watch to run its
     * normal 30-second HR sampling headlessly (PATH_BP_REQUEST); the
     * resulting HR reading flows through PhoneListenerService as usual and
     * ends the Measuring state. No watch connected: NoWatch immediately.
     * Never throws from the call site.
     */
    fun requestBpCheck() {
        val now = System.currentTimeMillis()
        BpCheckState.toMeasuring(now)
        viewModelScope.launch {
            try {
                val nodes = Wearable.getNodeClient(getApplication())
                    .connectedNodes.await()
                if (nodes.isEmpty()) {
                    BpCheckState.toNoWatch()
                    return@launch
                }
                val payload = DataMap().apply {
                    putLong(Link.KEY_TIMESTAMP, now)
                }.toByteArray()
                nodes.forEach { node ->
                    Wearable.getMessageClient(getApplication())
                        .sendMessage(node.id, Link.PATH_BP_REQUEST, payload)
                        .await()
                }
            } catch (_: Exception) {
                BpCheckState.toFailed("Couldn't reach the watch.")
            }
        }
        // Timeout guard: if the watch never answered, say so instead of
        // spinning the button forever.
        viewModelScope.launch {
            delay(90_000)
            if (BpCheckState.status.value is BpCheckState.Status.Measuring &&
                BpCheckState.requestTs == now
            ) {
                BpCheckState.toFailed("The watch didn't respond in time.")
            }
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
    } catch (e: CancellationException) {
        // (J) Never swallow coroutine cancellation. The TrendsScreen load
        // effect relaunches on every metric/range switch; a cancelled load
        // must die here instead of returning a stale value that the dead
        // effect would then assign over the fresh load's UI state.
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Bucketed Health Connect history for a Trends metric. Empty when Health
     * Connect isn't usable — never throws on data errors (coroutine
     * cancellation still propagates; see (J) below).
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
        } catch (e: CancellationException) {
            // (J) Never swallow coroutine cancellation. The TrendsScreen
            // LaunchedEffect(range, metric) relaunches on every switch,
            // cancelling the in-flight load; without this rethrow the dead
            // effect's late emptyList() wins the race against the
            // replacement load's real data and the chart goes blank.
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * v2.3.2: raw sleep facts from Health Connect for the sleep diagnostic
     * card (grant state, sessions held, origins, errors).
     */
    suspend fun diagnoseSleep(): SleepDiagnosis = hc.diagnoseSleep()

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
    /** v2.3: latest stress score 0-100 (samples or a stress-bearing reading). */
    val stress: Int? = null,
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

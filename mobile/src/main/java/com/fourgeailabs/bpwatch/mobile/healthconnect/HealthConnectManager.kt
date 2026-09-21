package com.fourgeailabs.bpwatch.mobile.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Volume
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Health Connect bridge — this is also the Samsung Health path.
 *
 * Samsung's own Health SDK needs Samsung's partner approval per app, which we
 * don't have. The supported route: Samsung Health (on the phone) can sync its
 * data — including SpO2 from the Galaxy Watch — into Health Connect, and this
 * class reads it from there. So "connect Samsung Health" really means:
 *   1. In Samsung Health: Settings → Health Connect → allow sharing.
 *   2. In BPWatch: grant Health Connect permissions below.
 * It also publishes our BP estimates as BloodPressureRecords so other health
 * apps can see them.
 */
class HealthConnectManager(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    /**
     * Never throws: a Health Connect hiccup must not take the app down.
     * (Called during ViewModel construction, before any try/catch can help.)
     */
    val isAvailable: Boolean
        get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    /**
     * Raw SDK status: SDK_AVAILABLE, SDK_UNAVAILABLE, or
     * SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. Surfaced in Settings so a
     * silent failure is diagnosable instead of mysterious.
     */
    val sdkStatus: Int
        get() = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (_: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }

    fun sdkStatusText(): String = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "needs Health Connect update (Play Store)"
        else -> "not available on this device"
    }

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        // Dashboard tiles (v2.0).
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        // "+ Log" sheet writes (v2.0).
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class),
    )

    /**
     * Read-only subset, for A/B diagnosis: if the full set is cancelled by the
     * system but this one shows the dialog, the WRITE permission is the poison.
     */
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
    )

    /**
     * Every read the v2.0 dashboard needs. The tiles degrade gracefully when
     * these aren't granted (they show "No data" plus a nudge to Settings),
     * so a denied write grant never blanks the dashboard.
     */
    val dashboardReadPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
    )

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    /**
     * Read-permissions only. SpO2 reads must never depend on the unrelated
     * blood-pressure WRITE grant — a user who denies writing BP would
     * otherwise silently lose SpO2 reads too.
     */
    suspend fun hasReadPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(readPermissions)

    /** True when every read the v2.0 dashboard needs has been granted. */
    suspend fun hasDashboardReads(): Boolean =
        try {
            client.permissionController.getGrantedPermissions()
                .containsAll(dashboardReadPermissions)
        } catch (_: Exception) {
            false
        }

    /**
     * Per-permission Android runtime status (granted/denied), for diagnostics.
     * Short names keep the Settings card readable.
     */
    fun permissionStatusLines(): List<String> = permissions.map { perm ->
        val short = perm.substringAfterLast('.')
        val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        "$short: ${if (granted) "granted ✓" else "not granted"}"
    }

    /**
     * Latest SpO2 % from the last 7 days, or null. Wrapped in a timeout:
     * Health Connect queries against Samsung Health can hang indefinitely.
     */
    suspend fun readLatestSpo2(): Int? = withTimeoutOrNull(SPO2_QUERY_TIMEOUT_MS) {
        spo2RecordsLast7Days()
            .maxByOrNull { it.time }
            ?.percentage
            ?.let { pct: Percentage -> pct.value.toInt() }
    }

    /** Count + newest timestamp of SpO2 records in the last 7 days, for diagnostics. */
    suspend fun readSpo2Stats(): Spo2Stats? = withTimeoutOrNull(SPO2_QUERY_TIMEOUT_MS) {
        val records = spo2RecordsLast7Days()
        Spo2Stats(count = records.size, newest = records.maxByOrNull { it.time }?.time)
    }

    /**
     * SpO2 samples in [start, end], for the Trends chart. Follows the same
     * read path as [readLatestSpo2]; null on any failure (callers show the
     * connect prompt instead of a dead chart).
     */
    suspend fun readSpo2Range(start: Instant, end: Instant): List<Spo2Sample>? =
        withTimeoutOrNull(SPO2_QUERY_TIMEOUT_MS) {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            ).records.mapNotNull { record ->
                record.percentage?.let { pct ->
                    Spo2Sample(
                        timestamp = record.time.toEpochMilli(),
                        spo2 = pct.value.toInt(),
                    )
                }
            }
        }

    private suspend fun spo2RecordsLast7Days(): List<OxygenSaturationRecord> {
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        return client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        ).records
    }

    /** Publishes an estimated BP reading so Health Connect apps can use it. */
    suspend fun writeBloodPressure(sys: Int, dia: Int, time: Instant) {
        val record = BloodPressureRecord(
            time = time,
            zoneOffset = java.time.ZoneId.systemDefault().rules.getOffset(time),
            systolic = Pressure.millimetersOfMercury(sys.toDouble()),
            diastolic = Pressure.millimetersOfMercury(dia.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
        )
        client.insertRecords(listOf(record))
    }

    /**
     * Today's headline metrics for the v2.0 dashboard, or null on any
     * failure. Aggregates are cheap single calls; the record reads are
     * bounded to sensible windows. Never throws (callers wrap in timeout).
     */
    suspend fun readTodayMetrics(): TodayMetrics? = withTimeoutOrNull(SPO2_QUERY_TIMEOUT_MS) {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val dayFilter = TimeRangeFilter.between(startOfDay, now)

        val agg = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    HydrationRecord.VOLUME_TOTAL,
                ),
                timeRangeFilter = dayFilter,
            )
        )

        val heartRateBpm = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = dayFilter,
            )
        ).records
            .flatMap { it.samples }
            .maxByOrNull { it.time }
            ?.beatsPerMinute

        val weightKg = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    now.minus(30, ChronoUnit.DAYS), now
                ),
            )
        ).records
            .maxByOrNull { it.time }
            ?.weight?.let { HcUnitReaders.kilograms(it) }

        // Last night's sleep: sessions ending in the last 36 hours.
        val sleepCutoff = now.minus(36, ChronoUnit.HOURS)
        val sleepMinutes = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(sleepCutoff, now),
            )
        ).records
            .filter { it.endTime.isAfter(sleepCutoff) }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

        TodayMetrics(
            steps = agg.get(StepsRecord.COUNT_TOTAL),
            distanceMeters = agg.get(DistanceRecord.DISTANCE_TOTAL)?.let { HcUnitReaders.meters(it) },
            caloriesKcal = agg.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { HcUnitReaders.kilocalories(it) },
            heartRateBpm = heartRateBpm,
            weightKg = weightKg,
            sleepHours = (sleepMinutes / 60.0).takeIf { sleepMinutes > 0 },
            hydrationLiters = agg.get(HydrationRecord.VOLUME_TOTAL)?.let { HcUnitReaders.liters(it) },
        )
    }

    /**
     * Bucketed history for a Trends metric over [start, end], or null on any
     * failure. Steps, distance, calories and hydration are sums per bucket;
     * weight is the latest reading per bucket; sleep is total hours per
     * bucket (each session attributed to the bucket its end falls in).
     * Empty buckets are dropped, so sparse metrics show sparse points.
     * Callers pass 1-hour buckets for the Hour/Day ranges, 24-hour buckets
     * for Week/Month/Year. Never throws (callers wrap in a timeout).
     */
    suspend fun readHcTrend(
        metric: HcTrendMetric,
        start: Instant,
        end: Instant,
        bucketHours: Long,
    ): List<HcTrendPoint>? = withTimeoutOrNull(HC_TREND_TIMEOUT_MS) {
        val filter = TimeRangeFilter.between(start, end)
        val slicer = Duration.ofHours(bucketHours)
        when (metric) {
            HcTrendMetric.STEPS -> aggregateTrend(
                setOf(StepsRecord.COUNT_TOTAL), filter, slicer,
            ) { agg -> agg.get(StepsRecord.COUNT_TOTAL)?.toFloat() }
            HcTrendMetric.DISTANCE -> aggregateTrend(
                setOf(DistanceRecord.DISTANCE_TOTAL), filter, slicer,
            ) { agg ->
                agg.get(DistanceRecord.DISTANCE_TOTAL)
                    ?.let { (HcUnitReaders.meters(it) / 1609.344).toFloat() }
            }
            HcTrendMetric.CALORIES -> aggregateTrend(
                setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), filter, slicer,
            ) { agg ->
                agg.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                    ?.let { HcUnitReaders.kilocalories(it).toFloat() }
            }
            HcTrendMetric.HYDRATION -> aggregateTrend(
                setOf(HydrationRecord.VOLUME_TOTAL), filter, slicer,
            ) { agg ->
                agg.get(HydrationRecord.VOLUME_TOTAL)
                    ?.let { HcUnitReaders.liters(it).toFloat() }
            }
            HcTrendMetric.WEIGHT -> {
                val records = client.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = filter,
                    )
                ).records
                records.groupBy { bucketStart(it.time, start, slicer) }
                    .mapNotNull { (bucket, rs) ->
                        rs.maxByOrNull { it.time }?.weight?.let { w ->
                            HcTrendPoint(
                                bucket.toEpochMilli(),
                                (HcUnitReaders.kilograms(w) * 2.20462).toFloat(),
                            )
                        }
                    }
                    .sortedBy { it.timestamp }
            }
            HcTrendMetric.SLEEP -> {
                val records = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = filter,
                    )
                ).records
                records.groupBy { bucketStart(it.endTime, start, slicer) }
                    .map { (bucket, rs) ->
                        val hours =
                            rs.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } / 60f
                        HcTrendPoint(bucket.toEpochMilli(), hours)
                    }
                    .sortedBy { it.timestamp }
            }
        }
    }

    /**
     * One aggregateGroupByDuration call, mapped to chart points. Buckets with
     * no data come back with an empty result and are dropped.
     */
    private suspend fun aggregateTrend(
        metrics: Set<AggregateMetric<*>>,
        filter: TimeRangeFilter,
        slicer: Duration,
        extract: (AggregationResult) -> Float?,
    ): List<HcTrendPoint> =
        client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = metrics,
                timeRangeFilter = filter,
                timeRangeSlicer = slicer,
            )
        ).mapNotNull { group ->
            extract(group.result)?.let { v ->
                HcTrendPoint(group.startTime.toEpochMilli(), v)
            }
        }

    /** Start of the [slicer]-sized bucket containing [t], anchored at [rangeStart]. */
    private fun bucketStart(t: Instant, rangeStart: Instant, slicer: Duration): Instant {
        val idx = (Duration.between(rangeStart, t).toMillis() / slicer.toMillis())
            .coerceAtLeast(0)
        return rangeStart.plus(slicer.multipliedBy(idx))
    }

    /** Logs a weight entry to Health Connect. Throws on failure. */
    suspend fun writeWeight(weightKg: Double, time: Instant) {
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = time,
                    zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
                    weight = Mass.kilograms(weightKg),
                )
            )
        )
    }

    /** Logs a hydration entry to Health Connect. Throws on failure. */
    suspend fun writeHydration(liters: Double, time: Instant) {
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(time)
        client.insertRecords(
            listOf(
                HydrationRecord(
                    startTime = time,
                    startZoneOffset = zoneOffset,
                    endTime = time,
                    endZoneOffset = zoneOffset,
                    volume = Volume.liters(liters),
                )
            )
        )
    }

    /**
     * Logs a food entry (calories + meal type) to Health Connect.
     * Throws on failure.
     */
    suspend fun writeFood(kcal: Double, mealType: Int, time: Instant) {
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(time)
        client.insertRecords(
            listOf(
                NutritionRecord(
                    startTime = time,
                    startZoneOffset = zoneOffset,
                    endTime = time,
                    endZoneOffset = zoneOffset,
                    energy = Energy.kilocalories(kcal),
                    mealType = mealType,
                )
            )
        )
    }

    /** Re-exported so the UI layer doesn't need the HC import for meal types. */
    object FoodMeal {
        const val BREAKFAST = MealType.MEAL_TYPE_BREAKFAST
        const val LUNCH = MealType.MEAL_TYPE_LUNCH
        const val DINNER = MealType.MEAL_TYPE_DINNER
        const val SNACK = MealType.MEAL_TYPE_SNACK
    }

    companion object {
        private const val SAMSUNG_HEALTH_PKG = "com.sec.android.app.shealth"
        private const val HC_PLAY_STORE_PKG = "com.google.android.apps.healthdata"

        /** SpO2 queries against Samsung Health can hang — never wait forever. */
        private const val SPO2_QUERY_TIMEOUT_MS = 15_000L

        /** Trend range queries can span a year of buckets — allow longer. */
        private const val HC_TREND_TIMEOUT_MS = 30_000L

        /** True when the Health Connect app / system component can handle intents. */
        fun isHealthConnectInstalled(context: Context): Boolean {
            return try {
                val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                intent.setPackage("com.android.settings")
                context.packageManager.resolveActivity(intent, 0) != null ||
                    isPackageInstalled(context, HC_PLAY_STORE_PKG) ||
                    Build.VERSION.SDK_INT >= 34
            } catch (_: Exception) {
                false
            }
        }

        fun isSamsungHealthInstalled(context: Context): Boolean =
            isPackageInstalled(context, SAMSUNG_HEALTH_PKG)

        private fun isPackageInstalled(context: Context, pkg: String): Boolean =
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        /** Opens Health Connect in the Play Store. */
        fun openHealthConnectInPlayStore(context: Context) {
            openUrl(context, "https://play.google.com/store/apps/details?id=$HC_PLAY_STORE_PKG")
        }

        /** Opens Samsung Health so the user can enable Health Connect sync. */
        fun openSamsungHealth(context: Context): Boolean {
            return try {
                val launch = context.packageManager.getLaunchIntentForPackage(SAMSUNG_HEALTH_PKG)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    true
                } else false
            } catch (_: Exception) {
                false
            }
        }

        /** Opens the system Health Connect settings screen. */
        fun openHealthConnectSettings(context: Context): Boolean {
            // The Jetpack action doesn't resolve on all devices (notably some
            // Android 16 builds), so try the platform Home settings action too.
            val actions = listOf(
                "android.health.connect.action.HEALTH_HOME_SETTINGS",
                "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
            )
            return actions.any { action ->
                try {
                    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
        }

        /**
         * Opens Health Connect's per-app permission screen for BPWatch, where
         * the user can flip the permission toggles directly. This is the
         * manual fallback for devices where the automatic permission dialog
         * never appears (the request is cancelled by the system with zero
         * grants). Falls back to the general Health Connect settings screen.
         * Returns true if something was launched.
         */
        fun openAppHealthPermissions(context: Context): Boolean {
            val launched = try {
                // Platform action, API 34+: manage health permissions for one app.
                // The constant is inlined at compile time, so referencing it is
                // safe even on older devices (the intent simply won't resolve).
                val intent = Intent(
                    android.health.connect.HealthConnectManager
                        .ACTION_MANAGE_HEALTH_PERMISSIONS
                )
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            return launched || openHealthConnectSettings(context)
        }

        private fun openUrl(context: Context, url: String) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}

/** SpO2 diagnostic: how many records Health Connect returned in the last 7 days. */
data class Spo2Stats(val count: Int, val newest: java.time.Instant?)

/** One SpO2 sample for the Trends chart. */
data class Spo2Sample(val timestamp: Long, val spo2: Int)

/** Health Connect-backed Trends metrics (v2.1): every home tile's landing spot. */
enum class HcTrendMetric { STEPS, DISTANCE, CALORIES, WEIGHT, SLEEP, HYDRATION }

/** One bucketed point of a Health Connect Trends metric. */
data class HcTrendPoint(val timestamp: Long, val value: Float)

/**
 * Headline metrics for the v2.0 dashboard. Every field is null when Health
 * Connect has no data (or isn't readable); the tiles show "No data" then.
 */
data class TodayMetrics(
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val caloriesKcal: Double? = null,
    val heartRateBpm: Long? = null,
    val weightKg: Double? = null,
    val sleepHours: Double? = null,
    val hydrationLiters: Double? = null,
)

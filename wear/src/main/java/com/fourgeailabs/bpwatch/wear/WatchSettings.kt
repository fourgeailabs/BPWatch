package com.fourgeailabs.bpwatch.wear

import android.content.Context
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap

/**
 * Watch-side settings and persisted state (SharedPreferences).
 *
 * The last BP estimate is persisted because the hourly background check
 * delivers it while the app UI may not be running — without persistence the
 * estimate would die with the listener's process.
 */
object WatchSettings {
    enum class CheckMode { MANUAL, HOURLY }

    private const val PREFS = "bpwatch_watch"
    private const val KEY_MODE = "check_mode"
    private const val KEY_EST_SYS = "est_sys"
    private const val KEY_EST_DIA = "est_dia"
    private const val KEY_EST_TS = "est_ts"
    private const val KEY_CALIBRATED = "calibrated"
    private const val KEY_RESTING_HR = "resting_hr"
    /** Fallback resting HR (bpm) until the phone sends the real baseline. */
    const val DEFAULT_RESTING_HR = 70f

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCheckMode(context: Context): CheckMode =
        when (prefs(context).getString(KEY_MODE, CheckMode.MANUAL.name)) {
            CheckMode.HOURLY.name -> CheckMode.HOURLY
            else -> CheckMode.MANUAL
        }

    fun setCheckMode(context: Context, mode: CheckMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun saveEstimate(context: Context, sys: Int, dia: Int, timestamp: Long) {
        prefs(context).edit()
            .putInt(KEY_EST_SYS, sys)
            .putInt(KEY_EST_DIA, dia)
            .putLong(KEY_EST_TS, timestamp)
            .apply()
    }

    fun loadEstimate(context: Context): BpEstimate? {
        val p = prefs(context)
        val ts = p.getLong(KEY_EST_TS, 0L)
        if (ts == 0L) return null
        return BpEstimate(
            sys = p.getInt(KEY_EST_SYS, 0),
            dia = p.getInt(KEY_EST_DIA, 0),
            timestamp = ts,
        )
    }

    fun saveCalibrated(context: Context, calibrated: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALIBRATED, calibrated).apply()
    }

    fun isCalibrated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CALIBRATED, false)

    /**
     * Resting-HR baseline for stress estimation, pushed from the phone
     * (lowest HR across calibration points). Falls back to a generic 70.
     */
    fun saveRestingHr(context: Context, restingHr: Float) {
        prefs(context).edit().putFloat(KEY_RESTING_HR, restingHr).apply()
    }

    fun getRestingHr(context: Context): Float =
        prefs(context).getFloat(KEY_RESTING_HR, DEFAULT_RESTING_HR)

    // ------------------------------------------------------------------
    // Monitoring & alerts config (pushed from the phone, v1.13+).
    // ------------------------------------------------------------------

    private const val KEY_MON_CONTINUOUS_HR = "mon_continuous_hr"
    private const val KEY_MON_HR_HIGH_ENABLED = "mon_hr_high_enabled"
    private const val KEY_MON_HR_HIGH_THRESHOLD = "mon_hr_high_threshold"
    private const val KEY_MON_INTERVAL = "mon_bp_interval_min"
    private const val KEY_MON_BP_HIGH_ENABLED = "mon_bp_high_enabled"
    private const val KEY_MON_SYS_HIGH = "mon_sys_high"
    private const val KEY_MON_DIA_HIGH = "mon_dia_high"
    private const val KEY_MON_BP_LOW_ENABLED = "mon_bp_low_enabled"
    private const val KEY_MON_SYS_LOW = "mon_sys_low"
    private const val KEY_MON_DIA_LOW = "mon_dia_low"

    /** Alert types for the per-type cooldown. */
    const val ALERT_HR_HIGH = "alert_hr_high"
    const val ALERT_BP_HIGH = "alert_bp_high"
    const val ALERT_BP_LOW = "alert_bp_low"
    private const val KEY_LAST_ALERT_PREFIX = "last_alert_"

    fun getMonitorConfig(context: Context): MonitorConfig {
        val p = prefs(context)
        // One-time migration from the pre-v1.13 hourly toggle: HOURLY becomes
        // a 60-minute interval, MANUAL becomes on-demand.
        val interval = if (p.contains(KEY_MON_INTERVAL)) {
            p.getInt(KEY_MON_INTERVAL, 0)
        } else {
            val migrated = if (getCheckMode(context) == CheckMode.HOURLY) 60 else 0
            p.edit().putInt(KEY_MON_INTERVAL, migrated).apply()
            migrated
        }
        return MonitorConfig(
            continuousHr = p.getBoolean(KEY_MON_CONTINUOUS_HR, false),
            hrHighEnabled = p.getBoolean(KEY_MON_HR_HIGH_ENABLED, false),
            hrHighThreshold = p.getInt(KEY_MON_HR_HIGH_THRESHOLD, 120),
            bpIntervalMinutes = interval,
            bpHighEnabled = p.getBoolean(KEY_MON_BP_HIGH_ENABLED, false),
            sysHigh = p.getInt(KEY_MON_SYS_HIGH, 140),
            diaHigh = p.getInt(KEY_MON_DIA_HIGH, 90),
            bpLowEnabled = p.getBoolean(KEY_MON_BP_LOW_ENABLED, false),
            sysLow = p.getInt(KEY_MON_SYS_LOW, 90),
            diaLow = p.getInt(KEY_MON_DIA_LOW, 60),
        )
    }

    fun saveMonitorConfig(context: Context, config: MonitorConfig) {
        prefs(context).edit()
            .putBoolean(KEY_MON_CONTINUOUS_HR, config.continuousHr)
            .putBoolean(KEY_MON_HR_HIGH_ENABLED, config.hrHighEnabled)
            .putInt(KEY_MON_HR_HIGH_THRESHOLD, config.hrHighThreshold)
            .putInt(KEY_MON_INTERVAL, config.bpIntervalMinutes)
            .putBoolean(KEY_MON_BP_HIGH_ENABLED, config.bpHighEnabled)
            .putInt(KEY_MON_SYS_HIGH, config.sysHigh)
            .putInt(KEY_MON_DIA_HIGH, config.diaHigh)
            .putBoolean(KEY_MON_BP_LOW_ENABLED, config.bpLowEnabled)
            .putInt(KEY_MON_SYS_LOW, config.sysLow)
            .putInt(KEY_MON_DIA_LOW, config.diaLow)
            .apply()
    }

    /** Parses a PATH_MONITORING_CONFIG DataMap; unknown version → defaults. */
    fun parseMonitorConfig(map: DataMap): MonitorConfig {
        if (!map.containsKey(Link.KEY_CONFIG_V)) return MonitorConfig()
        return MonitorConfig(
            continuousHr = map.getBoolean(Link.KEY_CONTINUOUS_HR),
            hrHighEnabled = map.getBoolean(Link.KEY_HR_HIGH_ENABLED),
            hrHighThreshold = map.getInt(Link.KEY_HR_HIGH_THRESHOLD, 120),
            bpIntervalMinutes = map.getInt(Link.KEY_BP_INTERVAL_MIN),
            bpHighEnabled = map.getBoolean(Link.KEY_BP_HIGH_ENABLED),
            sysHigh = map.getInt(Link.KEY_SYS_HIGH, 140),
            diaHigh = map.getInt(Link.KEY_DIA_HIGH, 90),
            bpLowEnabled = map.getBoolean(Link.KEY_BP_LOW_ENABLED),
            sysLow = map.getInt(Link.KEY_SYS_LOW, 90),
            diaLow = map.getInt(Link.KEY_DIA_LOW, 60),
        )
    }

    /** Timestamp of the last fired alert of [alertType], 0 if never. */
    fun getLastAlert(context: Context, alertType: String): Long =
        prefs(context).getLong(KEY_LAST_ALERT_PREFIX + alertType, 0L)

    fun setLastAlert(context: Context, alertType: String, timestamp: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_ALERT_PREFIX + alertType, timestamp)
            .apply()
    }
}

/**
 * Monitoring & alerts configuration, owned by the phone's Settings and
 * pushed to the watch over the Data Layer.
 *
 * @param bpIntervalMinutes 10 / 15 / 30 / 45 / 60 / 360 (every 6 hours) /
 *   1440 (once a day) / 0 (on demand only).
 */
data class MonitorConfig(
    val continuousHr: Boolean = false,
    val hrHighEnabled: Boolean = false,
    val hrHighThreshold: Int = 120,
    val bpIntervalMinutes: Int = 0,
    val bpHighEnabled: Boolean = false,
    val sysHigh: Int = 140,
    val diaHigh: Int = 90,
    val bpLowEnabled: Boolean = false,
    val sysLow: Int = 90,
    val diaLow: Int = 60,
)

/** Interval choices offered by the watch's check-frequency picker, in order. */
val WATCH_INTERVAL_CHOICES = listOf(10, 15, 30, 45, 60, 360, 1440, 0)

/** Human label for a BP-check interval, shared by the watch UI. */
fun bpIntervalLabel(minutes: Int): String = when (minutes) {
    10 -> "Every 10 minutes"
    15 -> "Every 15 minutes"
    30 -> "Every 30 minutes"
    45 -> "Every 45 minutes"
    60 -> "Every hour"
    360 -> "Every 6 hours"
    1440 -> "Once a day"
    else -> "Off"
}

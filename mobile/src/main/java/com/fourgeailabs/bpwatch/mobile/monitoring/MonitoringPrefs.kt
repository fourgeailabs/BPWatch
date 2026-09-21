package com.fourgeailabs.bpwatch.mobile.monitoring

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-side "Monitoring & alerts" settings, pushed to the watch over the
 * Data Layer whenever they change (see WatchConfigSender).
 *
 * bpIntervalMinutes: 10 / 15 / 30 / 45 / 60 / 360 (every 6 hours) /
 * 1440 (once a day) / 0 (on demand).
 */
data class MonitoringConfig(
    val continuousHr: Boolean = false,
    val hrHighEnabled: Boolean = false,
    val hrHighThreshold: Int = 120,
    val bpIntervalMinutes: Int = BP_ON_DEMAND,
    val bpHighEnabled: Boolean = false,
    val sysHigh: Int = 140,
    val diaHigh: Int = 90,
    val bpLowEnabled: Boolean = false,
    val sysLow: Int = 90,
    val diaLow: Int = 60,
) {
    companion object {
        const val BP_ON_DEMAND = 0
        const val BP_ONCE_A_DAY = 1440

        /** The frequency options shown in Settings, in display order. */
        val INTERVAL_OPTIONS = listOf(10, 15, 30, 45, 60, 360, BP_ONCE_A_DAY, BP_ON_DEMAND)

        fun intervalLabel(minutes: Int): String = when (minutes) {
            10 -> "Every 10 minutes"
            15 -> "Every 15 minutes"
            30 -> "Every 30 minutes"
            45 -> "Every 45 minutes"
            60 -> "Every hour"
            360 -> "Every 6 hours"
            BP_ONCE_A_DAY -> "Once a day"
            else -> "On demand only"
        }

        const val HR_THRESHOLD_MIN = 80
        const val HR_THRESHOLD_MAX = 220
        const val BP_SYS_MIN = 70
        const val BP_SYS_MAX = 250
        const val BP_DIA_MIN = 40
        const val BP_DIA_MAX = 150
    }
}

class MonitoringPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<MonitoringConfig> = _config.asStateFlow()

    // ------------------------------------------------------------------
    // Continuous HR + stress recording (v2.0, opt-in). Kept OUT of
    // MonitoringConfig on purpose: it syncs over its own Data Layer path
    // (PATH_HR_RECORD_SET) with the phone as source of truth, so a phone
    // config rebroadcast can never clobber it. Default OFF — sampling the
    // HR sensor every 10 minutes costs battery, and the user should opt in.
    // ------------------------------------------------------------------
    private val _recordHr = MutableStateFlow(prefs.getBoolean(KEY_RECORD_HR, false))
    val recordHr: StateFlow<Boolean> = _recordHr.asStateFlow()

    fun setRecordHr(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECORD_HR, enabled).apply()
        _recordHr.value = enabled
    }

    /**
     * True once the user has explicitly saved monitoring settings. The phone
     * only re-sends config to the watch on incoming readings after this —
     * until then the watch keeps whatever schedule it already had (including
     * the pre-v1.13 hourly-toggle migration), so a fresh upgrade never
     * silently clobbers the watch's existing behaviour.
     */
    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    private fun load(): MonitoringConfig = MonitoringConfig(
        continuousHr = prefs.getBoolean(KEY_CONTINUOUS_HR, false),
        hrHighEnabled = prefs.getBoolean(KEY_HR_HIGH_ENABLED, false),
        hrHighThreshold = prefs.getIntOrDefault(KEY_HR_HIGH_THRESHOLD, 120),
        bpIntervalMinutes = prefs.getInt(KEY_BP_INTERVAL_MIN, MonitoringConfig.BP_ON_DEMAND),
        bpHighEnabled = prefs.getBoolean(KEY_BP_HIGH_ENABLED, false),
        sysHigh = prefs.getIntOrDefault(KEY_SYS_HIGH, 140),
        diaHigh = prefs.getIntOrDefault(KEY_DIA_HIGH, 90),
        bpLowEnabled = prefs.getBoolean(KEY_BP_LOW_ENABLED, false),
        sysLow = prefs.getIntOrDefault(KEY_SYS_LOW, 90),
        diaLow = prefs.getIntOrDefault(KEY_DIA_LOW, 60),
    )

    private fun android.content.SharedPreferences.getIntOrDefault(
        key: String,
        default: Int,
    ): Int = if (contains(key)) getInt(key, default) else default

    /** Applies [transform], persists, marks configured, returns the new config. */
    fun update(transform: (MonitoringConfig) -> MonitoringConfig): MonitoringConfig {
        val sanitised = sanitise(transform(_config.value))
        prefs.edit()
            .putBoolean(KEY_CONTINUOUS_HR, sanitised.continuousHr)
            .putBoolean(KEY_HR_HIGH_ENABLED, sanitised.hrHighEnabled)
            .putInt(KEY_HR_HIGH_THRESHOLD, sanitised.hrHighThreshold)
            .putInt(KEY_BP_INTERVAL_MIN, sanitised.bpIntervalMinutes)
            .putBoolean(KEY_BP_HIGH_ENABLED, sanitised.bpHighEnabled)
            .putInt(KEY_SYS_HIGH, sanitised.sysHigh)
            .putInt(KEY_DIA_HIGH, sanitised.diaHigh)
            .putBoolean(KEY_BP_LOW_ENABLED, sanitised.bpLowEnabled)
            .putInt(KEY_SYS_LOW, sanitised.sysLow)
            .putInt(KEY_DIA_LOW, sanitised.diaLow)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
        _config.value = sanitised
        return sanitised
    }

    private fun sanitise(c: MonitoringConfig): MonitoringConfig = c.copy(
        hrHighThreshold = c.hrHighThreshold.coerceIn(
            MonitoringConfig.HR_THRESHOLD_MIN,
            MonitoringConfig.HR_THRESHOLD_MAX,
        ),
        bpIntervalMinutes = c.bpIntervalMinutes.takeIf {
            it in MonitoringConfig.INTERVAL_OPTIONS
        } ?: MonitoringConfig.BP_ON_DEMAND,
        sysHigh = c.sysHigh.coerceIn(MonitoringConfig.BP_SYS_MIN, MonitoringConfig.BP_SYS_MAX),
        diaHigh = c.diaHigh.coerceIn(MonitoringConfig.BP_DIA_MIN, MonitoringConfig.BP_DIA_MAX),
        sysLow = c.sysLow.coerceIn(MonitoringConfig.BP_SYS_MIN, MonitoringConfig.BP_SYS_MAX),
        diaLow = c.diaLow.coerceIn(MonitoringConfig.BP_DIA_MIN, MonitoringConfig.BP_DIA_MAX),
    )

    companion object {
        private const val PREFS = "bpwatch_monitoring"
        private const val KEY_CONFIGURED = "configured"
        private const val KEY_CONTINUOUS_HR = "continuous_hr"
        private const val KEY_RECORD_HR = "record_hr_continuous"
        private const val KEY_HR_HIGH_ENABLED = "hr_high_enabled"
        private const val KEY_HR_HIGH_THRESHOLD = "hr_high_threshold"
        private const val KEY_BP_INTERVAL_MIN = "bp_interval_min"
        private const val KEY_BP_HIGH_ENABLED = "bp_high_enabled"
        private const val KEY_SYS_HIGH = "sys_high"
        private const val KEY_DIA_HIGH = "dia_high"
        private const val KEY_BP_LOW_ENABLED = "bp_low_enabled"
        private const val KEY_SYS_LOW = "sys_low"
        private const val KEY_DIA_LOW = "dia_low"
    }
}

package com.fourgeailabs.bpwatch

/**
 * Data Layer contract shared by the watch and phone apps.
 * These two files must stay identical.
 */
object Link {
    const val PATH_HR_READING = "/bpwatch/hr_reading"
    const val PATH_BP_ESTIMATE = "/bpwatch/bp_estimate"
    const val PATH_CALIBRATION = "/bpwatch/calibration"
    const val PATH_MONITORING_CONFIG = "/bpwatch/monitoring_config"
    /**
     * Watch → phone: the user picked a new BP-check interval on the watch.
     * Payload is a DataMap with KEY_BP_INTERVAL_MIN. The phone persists it
     * so both sides stay in sync.
     */
    const val PATH_INTERVAL_SET = "/bpwatch/interval_set"

    const val KEY_HEART_RATE = "heart_rate"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SYS = "sys"
    const val KEY_DIA = "dia"
    const val KEY_SPO2 = "spo2"
    const val KEY_STRESS = "stress"
    const val KEY_RESTING_HR = "resting_hr"
    const val KEY_CALIBRATED = "calibrated"

    // Monitoring-config payload (PATH_MONITORING_CONFIG). Versioned with
    // KEY_CONFIG_V so the watch can ignore fields from newer phone builds.
    const val KEY_CONFIG_V = "v"
    const val KEY_CONTINUOUS_HR = "continuous_hr"
    const val KEY_HR_HIGH_ENABLED = "hr_high_enabled"
    const val KEY_HR_HIGH_THRESHOLD = "hr_high_threshold"
    const val KEY_BP_INTERVAL_MIN = "bp_interval_min"
    const val KEY_BP_HIGH_ENABLED = "bp_high_enabled"
    const val KEY_SYS_HIGH = "sys_high"
    const val KEY_DIA_HIGH = "dia_high"
    const val KEY_BP_LOW_ENABLED = "bp_low_enabled"
    const val KEY_SYS_LOW = "sys_low"
    const val KEY_DIA_LOW = "dia_low"
}

package com.fourgeailabs.bpwatch

/**
 * Data Layer contract shared by the watch and phone apps.
 * These two files must stay identical.
 */
object Link {
    const val PATH_HR_READING = "/bpwatch/hr_reading"
    const val PATH_BP_ESTIMATE = "/bpwatch/bp_estimate"
    const val PATH_CALIBRATION = "/bpwatch/calibration"

    const val KEY_HEART_RATE = "heart_rate"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SYS = "sys"
    const val KEY_DIA = "dia"
    const val KEY_SPO2 = "spo2"
    const val KEY_STRESS = "stress"
    const val KEY_RESTING_HR = "resting_hr"
    const val KEY_CALIBRATED = "calibrated"
}

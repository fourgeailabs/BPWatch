package com.fourgeailabs.bpwatch.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One blood-pressure tracking record.
 *
 * A record can come from several sources:
 * - "watch": heart rate measured on the Galaxy Watch, BP estimated from calibration
 * - "cuff":  a calibration point (cuff reading + simultaneous watch heart rate)
 * - "manual": hand-entered BP / SpO2
 * - "health_connect": SpO2 imported from Health Connect
 */
@Entity(tableName = "readings")
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val heartRate: Float? = null,
    val sysCuff: Int? = null,
    val diaCuff: Int? = null,
    val sysEstimate: Int? = null,
    val diaEstimate: Int? = null,
    val spo2: Int? = null,
    /** Experimental stress score 0-100 from the watch (-1/NULL = unknown). */
    val stress: Int? = null,
    val source: String = "manual",
)

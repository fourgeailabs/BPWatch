package com.fourgeailabs.bpwatch.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One manual health log written from the Home tab's "+ Log" sheet.
 *
 * [kind] is one of "weight" | "hydration" | "food". [value] is in lb for
 * weight, ml for hydration, kcal for food; [label] is the pre-formatted
 * display string (e.g. "203 lb"). Logs are written to Health Connect too
 * (best effort), but this table is the timeline's source of truth so the
 * timeline works instantly and offline.
 */
@Entity(tableName = "health_logs")
data class HealthLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val value: Double,
    val label: String,
)

package com.fourgeailabs.bpwatch.mobile.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Daily step total reported by the watch (PATH_STEPS_DAILY, v2.2).
 * Date-keyed ("yyyy-MM-dd" in the watch's zone); later reports for the same
 * day overwrite via REPLACE. When no row exists for today the dashboard
 * falls back to Health Connect steps.
 */
@Entity(tableName = "watch_steps")
data class WatchSteps(
    @PrimaryKey val date: String,
    val steps: Long,
    val updatedAt: Long,
)

@Dao
interface WatchStepsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: WatchSteps)

    @Query("SELECT steps FROM watch_steps WHERE date = :date LIMIT 1")
    suspend fun stepsForDate(date: String): Long?
}

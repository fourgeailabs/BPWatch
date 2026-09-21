package com.fourgeailabs.bpwatch.mobile.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A detected snore event: one burst of loud sustained sound in the overnight
 * window, with an optional WAV clip kept in the app's private files dir
 * (filesDir/snore_clips/<timestamp>.wav). Clips never leave the phone — no
 * upload code exists anywhere in this app.
 */
@Entity(tableName = "snore_events")
data class SnoreEvent(
    @PrimaryKey val timestamp: Long,
    /** How long the event lasted, milliseconds (roughly 1.5–10 s). */
    val durationMs: Int,
    /** Absolute path of the WAV clip, or null if the clip was pruned. */
    val clipPath: String? = null,
)

@Dao
interface SnoreDao {
    /** Idempotent insert — a re-recorded timestamp replaces, never duplicates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SnoreEvent)

    @Query("SELECT * FROM snore_events WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    suspend fun eventsBetween(start: Long, end: Long): List<SnoreEvent>

    @Query("SELECT * FROM snore_events WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC")
    fun observeBetween(start: Long, end: Long): Flow<List<SnoreEvent>>

    @Query("SELECT COUNT(*) FROM snore_events WHERE timestamp >= :start AND timestamp < :end")
    suspend fun countBetween(start: Long, end: Long): Int

    @Query("SELECT * FROM snore_events WHERE timestamp < :before ORDER BY timestamp ASC")
    suspend fun eventsOlderThan(before: Long): List<SnoreEvent>

    @Query("SELECT * FROM snore_events ORDER BY timestamp ASC")
    suspend fun allOrdered(): List<SnoreEvent>

    @Query("DELETE FROM snore_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM snore_events WHERE timestamp = :timestamp")
    suspend fun deleteByTimestamp(timestamp: Long)
}

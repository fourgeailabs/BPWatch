package com.fourgeailabs.bpwatch.mobile.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One recorded heart-rate sample (v2.0 continuous recording).
 *
 * The timestamp is the primary key: the watch samples on a fixed 10-minute
 * cadence, and REPLACE makes batch re-sends (after a missed ACK) idempotent.
 */
@Entity(tableName = "hr_samples")
data class HrSample(
    @PrimaryKey val timestamp: Long,
    val bpm: Float,
    /** "watch" for recorded batches; "phone" reserved for future use. */
    val source: String,
)

/** One recorded stress sample (0-100, same scale as the live estimate). */
@Entity(tableName = "stress_samples")
data class StressSample(
    @PrimaryKey val timestamp: Long,
    val score: Int,
)

@Dao
interface SampleDao {
    @Query(
        "SELECT * FROM hr_samples WHERE timestamp >= :start AND timestamp <= :end " +
            "ORDER BY timestamp ASC"
    )
    fun observeHrRange(start: Long, end: Long): Flow<List<HrSample>>

    @Query(
        "SELECT * FROM stress_samples WHERE timestamp >= :start AND timestamp <= :end " +
            "ORDER BY timestamp ASC"
    )
    fun observeStressRange(start: Long, end: Long): Flow<List<StressSample>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHr(samples: List<HrSample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStress(samples: List<StressSample>)

    @Query("SELECT COUNT(*) FROM hr_samples")
    suspend fun hrCount(): Int
}

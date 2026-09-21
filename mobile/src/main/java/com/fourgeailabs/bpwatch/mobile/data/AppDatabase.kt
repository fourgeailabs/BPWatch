package com.fourgeailabs.bpwatch.mobile.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Reading>>

    @Query("SELECT * FROM readings ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<Reading?>

    @Query("SELECT * FROM readings WHERE source = 'watch' AND heartRate IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestWatchReading(): Reading?

    @Insert
    suspend fun insert(reading: Reading): Long

    @Update
    suspend fun update(reading: Reading)

    @Query("DELETE FROM readings")
    suspend fun clearAll()
}

@Dao
interface HealthLogDao {
    @Query("SELECT * FROM health_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HealthLog>>

    @Insert
    suspend fun insert(log: HealthLog): Long

    @Query("DELETE FROM health_logs")
    suspend fun clearAll()
}

@Database(
    entities = [Reading::class, HealthLog::class, HrSample::class, StressSample::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun healthLogDao(): HealthLogDao
    abstract fun sampleDao(): SampleDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN stress INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS health_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "value REAL NOT NULL, " +
                        "label TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS hr_samples (" +
                        "timestamp INTEGER PRIMARY KEY NOT NULL, " +
                        "bpm REAL NOT NULL, " +
                        "source TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS stress_samples (" +
                        "timestamp INTEGER PRIMARY KEY NOT NULL, " +
                        "score INTEGER NOT NULL)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bpwatch.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}

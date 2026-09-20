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

@Database(entities = [Reading::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN stress INTEGER")
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
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}

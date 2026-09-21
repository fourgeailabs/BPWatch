package com.fourgeailabs.bpwatch.mobile

import android.content.Context
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import com.fourgeailabs.bpwatch.mobile.data.CalibrationStore
import com.fourgeailabs.bpwatch.mobile.data.HealthLog
import com.fourgeailabs.bpwatch.mobile.data.HrSample
import com.fourgeailabs.bpwatch.mobile.data.Reading
import com.fourgeailabs.bpwatch.mobile.data.StressSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BpRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    val dao = AppDatabase.get(appContext).readingDao()
    val logDao = AppDatabase.get(appContext).healthLogDao()
    val sampleDao = AppDatabase.get(appContext).sampleDao()
    val calibrationStore = CalibrationStore(appContext)

    val readings: Flow<List<Reading>> = dao.observeAll()
    val latest: Flow<Reading?> = dao.observeLatest()
    val healthLogs: Flow<List<HealthLog>> = logDao.observeAll()

    val calibrationPoints: Flow<List<CalibrationPoint>> = calibrationStore.points
    val calibrationModel: Flow<CalibrationModel?> =
        calibrationStore.points.map { CalibrationEngine.fit(it) }

    suspend fun addCalibrationPoint(point: CalibrationPoint) {
        calibrationStore.addPoint(point)
        // Also keep a record of the cuff reading in history.
        dao.insert(
            Reading(
                timestamp = System.currentTimeMillis(),
                heartRate = point.heartRate,
                sysCuff = point.sys,
                diaCuff = point.dia,
                source = "cuff",
            )
        )
    }

    suspend fun clearCalibration() = calibrationStore.clear()

    suspend fun addManualSpo2(spo2: Int) {
        dao.insert(
            Reading(
                timestamp = System.currentTimeMillis(),
                spo2 = spo2,
                source = "manual",
            )
        )
    }

    suspend fun addHealthLog(log: HealthLog) {
        logDao.insert(log)
    }

    /**
     * Stores one history batch from the watch. REPLACE keeps re-sends
     * (after a missed ACK) idempotent.
     */
    suspend fun insertHistorySamples(hr: List<HrSample>, stress: List<StressSample>) {
        if (hr.isNotEmpty()) sampleDao.insertHr(hr)
        if (stress.isNotEmpty()) sampleDao.insertStress(stress)
    }

    companion object {
        @Volatile
        private var INSTANCE: BpRepository? = null

        fun get(context: Context): BpRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BpRepository(context).also { INSTANCE = it }
            }
    }
}

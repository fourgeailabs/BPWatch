package com.fourgeailabs.bpwatch.mobile

import android.content.Context
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationEngine
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationModel
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import com.fourgeailabs.bpwatch.mobile.data.AppDatabase
import com.fourgeailabs.bpwatch.mobile.data.CalibrationStore
import com.fourgeailabs.bpwatch.mobile.data.Reading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BpRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    val dao = AppDatabase.get(appContext).readingDao()
    val calibrationStore = CalibrationStore(appContext)

    val readings: Flow<List<Reading>> = dao.observeAll()
    val latest: Flow<Reading?> = dao.observeLatest()

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

    companion object {
        @Volatile
        private var INSTANCE: BpRepository? = null

        fun get(context: Context): BpRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BpRepository(context).also { INSTANCE = it }
            }
    }
}

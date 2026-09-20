package com.fourgeailabs.bpwatch.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fourgeailabs.bpwatch.mobile.calibration.CalibrationPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.calibrationDataStore by preferencesDataStore("calibration")

/** Persists calibration points (cuff sys/dia + simultaneous watch heart rate). */
class CalibrationStore(private val context: Context) {

    private val pointsKey = stringPreferencesKey("points_json")

    val points: Flow<List<CalibrationPoint>> =
        context.calibrationDataStore.data.map { prefs ->
            prefs[pointsKey]?.let { decode(it) } ?: emptyList()
        }

    suspend fun addPoint(point: CalibrationPoint) {
        context.calibrationDataStore.edit { prefs ->
            val current = prefs[pointsKey]?.let { decode(it) } ?: emptyList()
            prefs[pointsKey] = encode(current + point)
        }
    }

    suspend fun removePoint(index: Int) {
        context.calibrationDataStore.edit { prefs ->
            val current = (prefs[pointsKey]?.let { decode(it) } ?: emptyList()).toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                prefs[pointsKey] = encode(current)
            }
        }
    }

    suspend fun clear() {
        context.calibrationDataStore.edit { prefs -> prefs.remove(pointsKey) }
    }

    private fun encode(points: List<CalibrationPoint>): String =
        JSONArray(points.map { p ->
            JSONObject().apply {
                put("hr", p.heartRate.toDouble())
                put("sys", p.sys)
                put("dia", p.dia)
            }
        }).toString()

    private fun decode(json: String): List<CalibrationPoint> =
        try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CalibrationPoint(
                    heartRate = o.getDouble("hr").toFloat(),
                    sys = o.getInt("sys"),
                    dia = o.getInt("dia"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
}

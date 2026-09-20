package com.fourgeailabs.bpwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/**
 * Reads heart rate from the watch's PPG sensor via SensorManager.
 * (Health Services MeasureClient is the fancier path; SensorManager is used
 * here because its API surface is stable and it works on Galaxy Watch.)
 */
class HeartRateMonitor(context: Context) : SensorEventListener {

    val appContext: Context = context.applicationContext

    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val hrSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    val available: Boolean get() = hrSensor != null

    var onSample: ((Float) -> Unit)? = null

    fun start() {
        start(null)
    }

    /**
     * @param handler optional handler whose Looper receives sensor callbacks.
     * Pass one when measuring off the main thread (e.g. the hourly check).
     */
    fun start(handler: Handler?) {
        hrSensor?.let {
            if (handler != null) {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            } else {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        onSample = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            onSample?.invoke(event.values[0])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for a simple average.
    }
}

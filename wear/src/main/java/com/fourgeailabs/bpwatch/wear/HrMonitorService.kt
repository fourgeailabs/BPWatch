package com.fourgeailabs.bpwatch.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Continuous heart-rate monitoring, enabled from the phone's
 * "Monitoring & alerts" settings.
 *
 * Runs as a foreground service (health type) with a persistent low-priority
 * notification, so the system doesn't kill the sensor listener. Every
 * reading is checked against the high-HR alert threshold, and every
 * [UPLOAD_INTERVAL_MS] an averaged reading is uploaded to the phone through
 * the normal pipeline (estimate → phone store → watch display).
 *
 * A partial wake lock is held only around each periodic upload, never
 * continuously.
 */
class HrMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: HeartRateMonitor? = null
    private var sensorThread: HandlerThread? = null
    private var uploadHandler: Handler? = null

    /** Timestamped samples for the current upload window. */
    private val window = ArrayDeque<Pair<Long, Float>>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithType()
        beginMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
        sensorThread?.quitSafely()
        sensorThread = null
        uploadHandler?.removeCallbacksAndMessages(null)
        uploadHandler = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithType() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Monitoring heart rate")
            .setContentText("BPWatch is keeping an eye on your heart rate.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginMonitoring() {
        if (monitor != null) return // already running
        val thread = HandlerThread("bpwatch-continuous-hr").apply { start() }
        sensorThread = thread
        val mon = HeartRateMonitor(this)
        if (!mon.available) {
            stopSelf()
            return
        }
        mon.onSample = { hr ->
            if (hr in 25f..250f) {
                val now = System.currentTimeMillis()
                synchronized(window) {
                    window.addLast(now to hr)
                    while (window.isNotEmpty() && now - window.first().first > UPLOAD_INTERVAL_MS) {
                        window.removeFirst()
                    }
                }
                WatchState.onLiveHr(hr)
                try {
                    AlertManager.checkHeartRate(applicationContext, hr)
                } catch (_: Exception) {
                    // Alerting must never kill monitoring.
                }
            }
        }
        mon.start(Handler(thread.looper))
        monitor = mon

        val handler = Handler(thread.looper)
        uploadHandler = handler
        handler.postDelayed(object : Runnable {
            override fun run() {
                uploadWindowAverage()
                handler.postDelayed(this, UPLOAD_INTERVAL_MS)
            }
        }, UPLOAD_INTERVAL_MS)
    }

    private fun uploadWindowAverage() {
        val samples: List<Float> = synchronized(window) { window.map { it.second } }
        if (samples.isEmpty()) return
        val avg = samples.average().toFloat()
        // Wake lock only for the duration of the upload.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BPWatch:HrUpload")
        scope.launch {
            try {
                wakeLock.acquire(60_000L)
                val stress = try {
                    StressEstimator.estimate(samples, WatchSettings.getRestingHr(this@HrMonitorService))
                } catch (_: Exception) {
                    -1
                }
                DataLayer.sendHrReading(
                    applicationContext,
                    avg,
                    System.currentTimeMillis(),
                    stress,
                )
            } catch (_: Exception) {
            } finally {
                if (wakeLock.isHeld) {
                    try {
                        wakeLock.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Heart-rate monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while continuous heart-rate monitoring is on."
            }
        )
    }

    companion object {
        private const val ACTION_START = "com.fourgeailabs.bpwatch.wear.action.MONITOR_START"
        private const val ACTION_STOP = "com.fourgeailabs.bpwatch.wear.action.MONITOR_STOP"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "bpwatch_hr_monitor"

        /** How often the continuous stream uploads an averaged reading. */
        private const val UPLOAD_INTERVAL_MS = 15 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, HrMonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HrMonitorService::class.java))
        }

        /**
         * Makes the service match the persisted config: starts it when
         * continuous monitoring is on (and BODY_SENSORS is granted), stops
         * it otherwise. Call from MainActivity.onCreate and whenever a new
         * config arrives.
         *
         * Note: starting a foreground service from the background is blocked
         * on Android 12+, so a config pushed while the UI is dead only takes
         * full effect when the app next opens — the config itself is always
         * persisted immediately.
         */
        fun ensureRunning(context: Context) {
            val config = WatchSettings.getMonitorConfig(context)
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS,
            ) == PackageManager.PERMISSION_GRANTED
            if (config.continuousHr && granted) {
                start(context)
            } else {
                stop(context)
            }
        }
    }
}

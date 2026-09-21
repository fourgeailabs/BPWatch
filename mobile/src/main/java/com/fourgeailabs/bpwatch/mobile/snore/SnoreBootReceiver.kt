package com.fourgeailabs.bpwatch.mobile.snore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs

/**
 * The OS clears AlarmManager alarms on reboot. This receiver re-arms the
 * 22:00/07:00 snore schedule after boot, and resumes listening if the phone
 * happens to boot inside the overnight window (starting a foreground service
 * from boot may be blocked by the OS; that failure is swallowed and the next
 * app open or the 22:00 alarm starts listening instead).
 */
class SnoreBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread({
            try {
                val app = context.applicationContext
                SnoreScheduler.ensureScheduled(app)
                val prefs = MonitoringPrefs(app)
                if (prefs.snoreDetection.value && SnoreScheduler.inWindow() && SnoreService.canRun(app)) {
                    try {
                        val service = Intent(app, SnoreService::class.java)
                            .setAction(SnoreScheduler.ACTION_START)
                        ContextCompat.startForegroundService(app, service)
                    } catch (_: Exception) {
                        // Blocked from a boot context; the user opens the app
                        // and the status line explains the state.
                    }
                }
            } catch (_: Exception) {
                // Never throw out of a broadcast receiver.
            } finally {
                pending.finish()
            }
        }, "SnoreBoot").apply { isDaemon = true }.start()
    }
}

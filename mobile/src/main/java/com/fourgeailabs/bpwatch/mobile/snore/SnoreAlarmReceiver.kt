package com.fourgeailabs.bpwatch.mobile.snore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fourgeailabs.bpwatch.mobile.monitoring.MonitoringPrefs

/**
 * Fires at 22:00 (start listening) and 07:00 (stop listening, prune clips)
 * while snore detection is enabled. The start path is defensive by design:
 * it checks the persisted opt-in toggle, microphone permission, and
 * microphone hardware before doing anything.
 *
 * Platform note: on Android 14+ the OS refuses microphone foreground-service
 * starts from the background, so the 22:00 attempt may fail with a
 * SecurityException even though the alarm is a valid trigger. That is
 * expected, not an error: the toggle stays on (it retries nightly), the
 * status line says to open the app, and opening the app during the window
 * starts listening immediately. A failed automatic start never disables the
 * feature — only a genuine microphone failure (see SnoreService.fail) does.
 */
class SnoreAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread({
            try {
                when (intent.action) {
                    SnoreScheduler.ACTION_START -> {
                        val prefs = MonitoringPrefs(context.applicationContext)
                        if (!prefs.snoreDetection.value) return@Thread
                        val app = context.applicationContext
                        if (!SnoreService.canRun(app)) {
                            SnoreState.post("Couldn't start listening — the microphone permission is missing.")
                            return@Thread
                        }
                        if (SnoreService.isRunning) return@Thread
                        try {
                            val service = Intent(app, SnoreService::class.java)
                                .setAction(SnoreScheduler.ACTION_START)
                            ContextCompat.startForegroundService(app, service)
                        } catch (_: Exception) {
                            // Expected on Android 14+ when the app is in the
                            // background (microphone FGS starts are blocked
                            // there). The toggle stays on; opening the app
                            // during the window starts listening.
                            SnoreState.post("Couldn't start automatically — open the app to begin tonight's listening.")
                        }
                    }

                    SnoreScheduler.ACTION_STOP -> {
                        try {
                            val app = context.applicationContext
                            val service = Intent(app, SnoreService::class.java)
                                .setAction(SnoreScheduler.ACTION_STOP)
                            app.startService(service)
                        } catch (_: Exception) {
                            // Already stopped or otherwise unavailable; nothing to do.
                        }
                        // Prune is suspend (Room DAO calls); we're on a worker
                        // thread, so bridge with runBlocking.
                        kotlinx.coroutines.runBlocking {
                            SnoreStorage.prune(context.applicationContext)
                        }
                    }
                }
            } catch (_: Exception) {
                // Never throw out of a broadcast receiver.
            } finally {
                pending.finish()
            }
        }, "SnoreAlarm").apply { isDaemon = true }.start()
    }
}

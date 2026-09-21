package com.fourgeailabs.bpwatch.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

/**
 * Installs a watch APK update from within the watch app itself — the other
 * half of the one-tap updater (v1.15+). The phone beams the APK over the
 * Data Layer; this hands it to Android's PackageInstaller, which shows the
 * standard "Do you want to update BPWatch?" confirmation.
 *
 * Updating your own package needs no special permission and, crucially,
 * preserves all app data (SharedPreferences, schedules, cooldowns) —
 * unlike an uninstall/reinstall, which wipes everything.
 */
object ApkSelfUpdater {
    private const val TAG = "ApkSelfUpdater"

    /**
     * Streams [apkFile] into a PackageInstaller session and commits it.
     * Returns false if the session couldn't even be created; once committed,
     * the system takes over (confirmation UI, install, app restart).
     */
    fun installUpdate(context: Context, apkFile: File): Boolean {
        try {
            require(apkFile.exists() && apkFile.length() > 0) {
                "APK file missing: ${apkFile.absolutePath}"
            }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            // Pin the session to our own package: a self-update must never
            // be allowed to resolve to anything else.
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { ins ->
                    session.openWrite("bpwatch_update", 0, apkFile.length()).use { outs ->
                        ins.copyTo(outs)
                        session.fsync(outs)
                    }
                }
                val resultIntent = Intent(
                    context,
                    UpdateResultReceiver::class.java,
                ).setAction(UpdateResultReceiver.ACTION_UPDATE_RESULT)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "Update session $sessionId committed for ${apkFile.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Self-update failed", e)
            return false
        }
    }
}

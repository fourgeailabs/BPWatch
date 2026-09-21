package com.fourgeailabs.bpwatch.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives the PackageInstaller result for a self-update ([ApkSelfUpdater]).
 * The app process is usually killed during the update, so this only fires
 * when something goes wrong early — success needs no handling.
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_RESULT) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "Self-update installed successfully")
        } else {
            Log.w(TAG, "Self-update failed: status=$status message=$message")
            Toast.makeText(
                context,
                "Watch update failed: ${message ?: "unknown error"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        const val ACTION_UPDATE_RESULT =
            "com.fourgeailabs.bpwatch.wear.UPDATE_RESULT"
        private const val TAG = "UpdateResultReceiver"
    }
}

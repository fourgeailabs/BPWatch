package com.fourgeailabs.bpwatch.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives the PackageInstaller result for a self-update ([ApkSelfUpdater]).
 * The app process is usually killed during the update, so this only fires
 * when something goes wrong early — success needs no handling.
 *
 * On failure the toast names the numeric status code (the system's
 * EXTRA_STATUS_MESSAGE is often null, which used to surface as the
 * unhelpful "unknown error"), and the outcome is sent back to the phone on
 * Link.PATH_APK_RESULT so the phone's updater card shows the real reason
 * instead of hanging at "installing".
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_RESULT) return
        val pending = goAsync()
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "Self-update installed successfully")
            pending.finish()
            return
        }
        val detail = statusText(status, message)
        Log.w(TAG, "Self-update failed: status=$status message=$message")
        // v2.4.0: when the install is blocked because "Install unknown apps"
        // isn't allowed for BPWatch, say exactly where to flip the toggle —
        // this is the most common reason a sideloaded self-update fails.
        val guidance = if (!context.packageManager.canRequestPackageInstalls()) {
            " On the watch: Settings → Apps → Special app access → " +
                "Install unknown apps → allow BPWatch, then try again."
        } else {
            ""
        }
        Toast.makeText(
            context,
            "Watch update failed: $detail (code $status)$guidance",
            Toast.LENGTH_LONG,
        ).show()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val payload = DataMap().apply {
                    putString(Link.KEY_APK_RESULT, "failed")
                    putString(Link.KEY_APK_MESSAGE, "Watch update failed: $detail (code $status)$guidance")
                }.toByteArray()
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, Link.PATH_APK_RESULT, payload)
                        .await()
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Human text for the common PackageInstaller failure codes. The system
     * message is often null (that was the old "unknown error"), so the
     * mapped text is the fallback that actually explains what happened.
     */
    private fun statusText(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_INVALID -> "update file invalid"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflicting install"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "install blocked"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible update"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "update cancelled"
        else -> message ?: "unknown error"
    }

    companion object {
        const val ACTION_UPDATE_RESULT =
            "com.fourgeailabs.bpwatch.wear.UPDATE_RESULT"
        private const val TAG = "UpdateResultReceiver"
    }
}

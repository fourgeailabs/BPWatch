package com.fourgeailabs.bpwatch.mobile.adb

import java.io.File

/**
 * Pushes the bundled watch APK to the Galaxy Watch over wireless debugging
 * and installs it. Pure java.* — no Android APIs.
 *
 * Update flow is the same as install (`pm install -r` reinstalls in place),
 * so as the watch app evolves, rebuilding the phone APK re-bundles the
 * latest watch APK and this pushes it over the top.
 */
class WatchInstaller(
    /** Directory where the ADB host key (adbkey) is kept. */
    private val keyDir: File,
    private val onLog: (String) -> Unit,
) {
    companion object {
        /** Must match the wear module's applicationId. */
        const val WATCH_PACKAGE = "com.fourgeailabs.bpwatch"
        private const val REMOTE_TMP = "/data/local/tmp/bpwatch-wear.apk"
    }

    /**
     * Pair with the watch over the *pairing* port (the one shown with the
     * 6-digit code in the watch's "Pair new device" screen). This registers
     * this phone's ADB key on the watch — a one-time step. After pairing,
     * use the *main* wireless-debugging port with [installOrUpdate]/[probe].
     */
    fun pair(host: String, pairingPort: Int, code: String) {
        onLog("Starting pairing — keep the watch's pairing screen open…")
        PairingClient(keyDir, onLog).pair(host, pairingPort, code)
    }

    /**
     * Full flow: connect → push APK → install → clean up.
     * Throws [AdbException] with a human-readable message on failure.
     */
    fun installOrUpdate(
        apkFile: File,
        host: String,
        port: Int,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ) {
        require(apkFile.exists()) { "Watch APK not found: ${apkFile.absolutePath}" }
        val sizeMb = apkFile.length() / 1_048_576.0
        onLog("Bundled watch app: %.1f MB".format(sizeMb))

        AdbClient(keyDir).connectTls(host, port, onLog).use { session ->
            val model = session.shell("getprop ro.product.model").trim().ifEmpty { "unknown device" }
            onLog("Talking to: $model")

            onLog("Uploading to watch…")
            session.push(apkFile, REMOTE_TMP) { sent, total ->
                onProgress(sent, total)
            }
            onLog("Upload complete.")

            onLog("Installing on watch…")
            val out = session.shell("pm install -r $REMOTE_TMP", timeoutMs = 120_000).trim()
            onLog(out.ifEmpty { "(no output)" })
            if (!out.contains("Success")) {
                throw AdbException("Install failed on the watch:\n$out")
            }
            runCatching { session.shell("rm $REMOTE_TMP") }
            onLog("Done — BPWatch is on your watch.")
        }
    }

    /** Remove the watch app entirely (handy while iterating). */
    fun uninstall(host: String, port: Int) {
        AdbClient(keyDir).connectTls(host, port, onLog).use { session ->
            val out = session.shell("pm uninstall $WATCH_PACKAGE").trim()
            onLog(out.ifEmpty { "(no output)" })
            if (!out.contains("Success")) {
                throw AdbException("Uninstall failed:\n$out")
            }
        }
    }

    /** Lightweight connectivity check: returns the device model or throws. */
    fun probe(host: String, port: Int): String {
        AdbClient(keyDir).connectTls(host, port, onLog).use { session ->
            return session.shell("getprop ro.product.model").trim().ifEmpty { "unknown device" }
        }
    }
}

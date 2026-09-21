package com.fourgeailabs.bpwatch.mobile.wearable

import android.content.Context
import android.os.Build
import com.fourgeailabs.bpwatch.Link
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * One-tap watch updater (v1.15+): sends the bundled watch APK to the Galaxy
 * Watch over the normal Data Layer connection — no Wi-Fi debugging, no IP
 * addresses, no pairing codes. The watch installs the update itself via
 * PackageInstaller (all its data is preserved).
 *
 * The watch APK is bundled inside this phone APK (see bundleWearApk), so
 * every phone build carries the matching watch build.
 */
object WatchUpdater {

    data class BundledApk(
        val versionCode: Long,
        val versionName: String,
        val file: File,
    )

    /**
     * Reads the bundled watch APK (extracting it from assets on first use)
     * and returns its version. Null when the bundle is missing or unreadable.
     */
    fun getBundledApk(context: Context): BundledApk? {
        return try {
            val out = File(context.cacheDir, "bpwatch-wear.apk")
            if (!out.exists()) {
                context.assets.open("bpwatch-wear.apk").use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs) }
                }
            }
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(
                out.absolutePath,
                0,
            ) ?: return null
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            BundledApk(versionCode, info.versionName ?: "?", out)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Step 1: ask every connected watch whether it needs [bundled]'s version.
     * The watch replies on PATH_APK_READY (handled by PhoneListenerService).
     */
    suspend fun sendBegin(context: Context, bundled: BundledApk): Boolean {
        val payload = DataMap().apply {
            putLong(Link.KEY_APK_VERSION_CODE, bundled.versionCode)
            putString(Link.KEY_APK_VERSION_NAME, bundled.versionName)
        }.toByteArray()
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, Link.PATH_APK_BEGIN, payload)
                    .await()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Step 2: beam the APK to every connected watch as a DataItem asset.
     * ~20 MB over Bluetooth takes a few minutes; the Data Layer handles
     * retries across disconnects. The timestamp forces the item to count as
     * changed even when re-sending the same version.
     */
    suspend fun sendApk(context: Context, bundled: BundledApk): Boolean {
        return try {
            val bytes = bundled.file.readBytes()
            val request = PutDataMapRequest.create(Link.PATH_APK_UPDATE).apply {
                dataMap.putLong(Link.KEY_APK_VERSION_CODE, bundled.versionCode)
                dataMap.putString(Link.KEY_APK_VERSION_NAME, bundled.versionName)
                dataMap.putLong(Link.KEY_TIMESTAMP, System.currentTimeMillis())
                dataMap.putAsset(Link.KEY_APK_ASSET, Asset.createFromBytes(bytes))
            }
            Wearable.getDataClient(context)
                .putDataItem(request.asPutDataRequest().setUrgent())
                .await()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Ask every connected watch to report its installed version. */
    suspend fun requestWatchInfo(context: Context): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, Link.PATH_WATCH_INFO_REQUEST, ByteArray(0))
                    .await()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

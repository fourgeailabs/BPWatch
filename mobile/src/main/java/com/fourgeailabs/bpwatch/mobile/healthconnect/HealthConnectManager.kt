package com.fourgeailabs.bpwatch.mobile.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Health Connect bridge — this is also the Samsung Health path.
 *
 * Samsung's own Health SDK needs Samsung's partner approval per app, which we
 * don't have. The supported route: Samsung Health (on the phone) can sync its
 * data — including SpO2 from the Galaxy Watch — into Health Connect, and this
 * class reads it from there. So "connect Samsung Health" really means:
 *   1. In Samsung Health: Settings → Health Connect → allow sharing.
 *   2. In BPWatch: grant Health Connect permissions below.
 * It also publishes our BP estimates as BloodPressureRecords so other health
 * apps can see them.
 */
class HealthConnectManager(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    /**
     * Never throws: a Health Connect hiccup must not take the app down.
     * (Called during ViewModel construction, before any try/catch can help.)
     */
    val isAvailable: Boolean
        get() = try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (_: Exception) {
            false
        }

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
    )

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** Latest SpO2 % from the last 7 days, or null. */
    suspend fun readLatestSpo2(): Int? {
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        return response.records
            .maxByOrNull { it.time }
            ?.percentage
            ?.let { pct: Percentage -> pct.value.toInt() }
    }

    /** Publishes an estimated BP reading so Health Connect apps can use it. */
    suspend fun writeBloodPressure(sys: Int, dia: Int, time: Instant) {
        val record = BloodPressureRecord(
            time = time,
            zoneOffset = java.time.ZoneId.systemDefault().rules.getOffset(time),
            systolic = Pressure.millimetersOfMercury(sys.toDouble()),
            diastolic = Pressure.millimetersOfMercury(dia.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM,
        )
        client.insertRecords(listOf(record))
    }

    companion object {
        private const val SAMSUNG_HEALTH_PKG = "com.sec.android.app.shealth"
        private const val HC_PLAY_STORE_PKG = "com.google.android.apps.healthdata"

        /** True when the Health Connect app / system component can handle intents. */
        fun isHealthConnectInstalled(context: Context): Boolean {
            return try {
                val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                intent.setPackage("com.android.settings")
                context.packageManager.resolveActivity(intent, 0) != null ||
                    isPackageInstalled(context, HC_PLAY_STORE_PKG) ||
                    Build.VERSION.SDK_INT >= 34
            } catch (_: Exception) {
                false
            }
        }

        fun isSamsungHealthInstalled(context: Context): Boolean =
            isPackageInstalled(context, SAMSUNG_HEALTH_PKG)

        private fun isPackageInstalled(context: Context, pkg: String): Boolean =
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        /** Opens Health Connect in the Play Store. */
        fun openHealthConnectInPlayStore(context: Context) {
            openUrl(context, "https://play.google.com/store/apps/details?id=$HC_PLAY_STORE_PKG")
        }

        /** Opens Samsung Health so the user can enable Health Connect sync. */
        fun openSamsungHealth(context: Context): Boolean {
            return try {
                val launch = context.packageManager.getLaunchIntentForPackage(SAMSUNG_HEALTH_PKG)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    true
                } else false
            } catch (_: Exception) {
                false
            }
        }

        /** Opens the system Health Connect settings screen. */
        fun openHealthConnectSettings(context: Context): Boolean {
            return try {
                val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun openUrl(context: Context, url: String) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}

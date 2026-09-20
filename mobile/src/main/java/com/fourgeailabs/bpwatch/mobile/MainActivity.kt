package com.fourgeailabs.bpwatch.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.ui.CalibrateScreen
import com.fourgeailabs.bpwatch.mobile.ui.HistoryScreen
import com.fourgeailabs.bpwatch.mobile.ui.HomeScreen
import com.fourgeailabs.bpwatch.mobile.ui.SettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.WatchInstallScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val hcPermissionLauncher = registerForActivityResult(
        HealthConnectManager(this).permissionContract()
    ) { granted ->
        // Diagnostic: this MUST fire after the system dialog (or a silent result).
        Log.i("BpWatch", "HC permission result: granted=$granted")
        Toast.makeText(
            this,
            "Health Connect: ${granted.size} granted",
            Toast.LENGTH_LONG,
        ).show()
        viewModel.onHcPermissionResult()
        viewModel.refreshHealthConnect()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort — estimates still land in History */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        ensureNotificationPermission()
        val lastCrashReport = readCrashReport()
        setContent {
            MaterialTheme {
                BpWatchPhoneApp(
                    viewModel = viewModel,
                    onRequestHcPermissions = { perms ->
                        Log.i("BpWatch", "HC: tapping Connect, SDK=${viewModel.hcStatusText}, requesting=$perms")
                        Toast.makeText(this, "Requesting ${perms.size} Health Connect permissions…", Toast.LENGTH_SHORT).show()
                        try {
                            hcPermissionLauncher.launch(perms)
                        } catch (t: Throwable) {
                            Log.e("BpWatch", "HC: permission launch failed", t)
                            Toast.makeText(this, "Permission request failed: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    crashReport = lastCrashReport,
                    onDismissCrashReport = {
                        // Already renamed on read; nothing more to do.
                    },
                )
            }
        }
    }

    /**
     * Android 13+ needs runtime consent for notifications. Ask once; if
     * denied, estimates still land in History — just no ping.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    /**
     * Saves any uncaught crash (exception OR error) to a file before the
     * process dies, so the app can show the report on next launch.
     * The trace is capped so the file can never be huge. Chained to the
     * previous handler so the app still terminates normally.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    appendLine("Crash at ${java.util.Date()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("${throwable::class.qualifiedName}: ${throwable.message}")
                    throwable.stackTrace.take(200).forEach { appendLine("    at $it") }
                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && depth < 3) {
                        appendLine("Caused by ${cause::class.qualifiedName}: ${cause.message}")
                        cause.stackTrace.take(50).forEach { appendLine("    at $it") }
                        cause = cause.cause
                        depth++
                    }
                }
                java.io.File(filesDir, "bpwatch-crash.log").writeText(report)
            } catch (_: Exception) {
                // Never let the recorder itself fail.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Reads the saved crash report (max 48 KB), renames it so it is shown
     * only once, and returns null when there is none or it can't be read.
     */
    private fun readCrashReport(): String? {
        return try {
            val f = java.io.File(filesDir, "bpwatch-crash.log")
            if (!f.exists()) return null
            val bytes = f.inputStream().use { ins ->
                val buf = ByteArray(48 * 1024)
                var off = 0
                while (off < buf.size) {
                    val r = ins.read(buf, off, buf.size - off)
                    if (r < 0) break
                    off += r
                }
                buf.copyOf(off)
            }
            f.renameTo(java.io.File(filesDir, "bpwatch-crash-prev.log"))
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun BpWatchPhoneApp(
    viewModel: MainViewModel,
    onRequestHcPermissions: (Set<String>) -> Unit,
    crashReport: String?,
    onDismissCrashReport: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showCrash by remember(crashReport) { mutableStateOf(crashReport != null) }

    if (showCrash && crashReport != null) {
        val report = crashReport
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            title = { Text("The app crashed last time") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        // heightIn OUTSIDE the scroll: the scrollable must be
                        // measured with finite constraints (see Watch tab fix).
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("BPWatch crash", report))
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCrash = false
                    onDismissCrashReport()
                }) {
                    Text("Dismiss")
                }
            },
        )
    }

    var showDisclaimer by remember {
        val prefs = viewModel.getApplication<android.app.Application>()
            .getSharedPreferences("bpwatch_prefs", android.content.Context.MODE_PRIVATE)
        mutableStateOf(!prefs.getBoolean("disclaimer_seen", false))
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { /* must acknowledge */ },
            title = { Text("Before you start") },
            text = {
                Text(
                    "BPWatch estimates blood pressure from your watch's heart rate " +
                        "using your own cuff calibration. It is a wellness tool, not a " +
                        "medical device, and its readings are not measurements. " +
                        "Never use it to diagnose, treat, or change medication — " +
                        "always confirm with a cuff."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.getApplication<android.app.Application>()
                        .getSharedPreferences("bpwatch_prefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("disclaimer_seen", true)
                        .apply()
                    showDisclaimer = false
                }) {
                    Text("I understand")
                }
            },
        )
    }

    val tabs = listOf(
        "Home" to Icons.Filled.Home,
        "Calibrate" to Icons.Filled.MonitorHeart,
        "History" to Icons.Filled.History,
        "Watch" to Icons.Filled.Watch,
        "Settings" to Icons.Filled.Settings,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(viewModel)
                1 -> CalibrateScreen(viewModel)
                2 -> HistoryScreen(viewModel)
                3 -> WatchInstallScreen()
                4 -> SettingsScreen(viewModel, onRequestHcPermissions)
            }
        }
    }
}

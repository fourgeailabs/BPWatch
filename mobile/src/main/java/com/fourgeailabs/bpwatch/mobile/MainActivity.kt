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
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.healthconnect.HealthConnectManager
import com.fourgeailabs.bpwatch.mobile.ui.AboutScreen
import com.fourgeailabs.bpwatch.mobile.ui.BodyProfileSettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.BpWatchTheme
import com.fourgeailabs.bpwatch.mobile.ui.CalibrateScreen
import com.fourgeailabs.bpwatch.mobile.ui.CalibrationSettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.ChangelogScreen
import com.fourgeailabs.bpwatch.mobile.ui.ConnectionsSettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.HistoryScreen
import com.fourgeailabs.bpwatch.mobile.ui.HomeScreen
import com.fourgeailabs.bpwatch.mobile.ui.MonitoringSettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.SettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.SleepSettingsScreen
import com.fourgeailabs.bpwatch.mobile.ui.SnoreScreen
import com.fourgeailabs.bpwatch.mobile.ui.TrendMetric
import com.fourgeailabs.bpwatch.mobile.ui.TrendsScreen
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

    // v2.3 snore detection: runtime microphone permission, requested from
    // the Settings toggle when the user opts in.
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        ensureNotificationPermission()
        val lastCrashReport = readCrashReport()
        setContent {
            BpWatchTheme {
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
                    onRequestMicPermission = {
                        try {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        } catch (t: Throwable) {
                            Log.e("BpWatch", "Mic: permission launch failed", t)
                            Toast.makeText(this, "Microphone request failed: ${t.message}", Toast.LENGTH_LONG).show()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BpWatchPhoneApp(
    viewModel: MainViewModel,
    onRequestHcPermissions: (Set<String>) -> Unit,
    onRequestMicPermission: () -> Unit,
    crashReport: String?,
    onDismissCrashReport: () -> Unit,
) {
    val context = LocalContext.current
    // v2.4.0: 0..3 = bottom tabs (Home, Trends, History, Settings hub),
    // 4..12 = detail screens opened from Home or the hub (not tabs):
    // 4 Calibrate, 5 Snore, 6 About, 7 What's new, 8 Watch app,
    // 9 Body profile, 10 Connections, 11 Monitoring & alerts, 12 Sleep.
    var selected by remember { mutableIntStateOf(0) }
    // Deep-link target for the Trends tab: a home tile sets this, then the
    // tab switch opens Trends with the metric preselected (Week range is
    // the default). TrendsScreen clears it once consumed.
    var trendsInitial by remember { mutableStateOf<TrendMetric?>(null) }
    var showCrash by remember(crashReport) { mutableStateOf(crashReport != null) }
    // v2.3.2: in-app back stack so the system back button walks back through
    // screens instead of closing the app. SnapshotStateList so the
    // BackHandler's enabled flag recomposes as the stack changes.
    val backStack = remember { mutableStateListOf<Int>() }
    fun goTo(index: Int) {
        if (index != selected) {
            backStack.add(selected)
            selected = index
        }
    }
    fun goBack(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        selected = prev
        return true
    }

    if (showCrash && crashReport != null) {
        val report = crashReport
        AlertDialog(
            onDismissRequest = { /* must choose */ },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
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
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
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
        "Trends" to Icons.Filled.TrendingUp,
        "History" to Icons.Filled.History,
        "Settings" to Icons.Filled.Settings,
    )

    // v2.3.2: system back walks the in-app history. Only with an empty
    // stack (and no blocking dialog up) does back fall through and close
    // the app as before.
    BackHandler(enabled = backStack.isNotEmpty() && !showCrash && !showDisclaimer) {
        goBack()
    }

    Scaffold(
        topBar = {
            // v2.4.0: 0..3 = bottom tabs; 4..12 = detail screens with back.
            // 4 Calibrate, 5 Snoring, 6 About, 7 What's new, 8 Watch app,
            // 9 Body profile, 10 Connections, 11 Monitoring & alerts, 12 Sleep.
            if (selected in 4..12) {
                MediumTopAppBar(
                    title = {
                        Text(
                            when (selected) {
                                4 -> "Calibrate"
                                5 -> "Snoring"
                                6 -> "About"
                                7 -> "What's new"
                                8 -> "Watch app"
                                9 -> "Body profile"
                                10 -> "Connections"
                                11 -> "Monitoring & alerts"
                                else -> "Sleep"
                            }
                        )
                    },
                    navigationIcon = {
                        // v2.3.2: top-bar back pops the same in-app history as
                        // the system back button, with the old hardcoded
                        // target as a fallback if the stack is ever empty.
                        // v2.4.0: settings details fall back to the hub (3).
                        IconButton(onClick = {
                            if (!goBack()) selected =
                                if (selected in 6..12) 3 else 0
                        }) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { goTo(index) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selected) {
                0 -> HomeScreen(
                    viewModel,
                    // v2.4.0: Watch lives in Settings now, not the bottom bar.
                    onOpenWatch = { goTo(8) },
                    onOpenSettings = { goTo(3) },
                    onOpenTrends = { metric -> trendsInitial = metric; goTo(1) },
                    onOpenSnore = { goTo(5) },
                )
                1 -> TrendsScreen(
                    viewModel,
                    onRequestHcPermissions,
                    initialMetric = trendsInitial,
                    onInitialMetricConsumed = { trendsInitial = null },
                    // v2.3.2: sleep diagnostic in the sleep empty state.
                    onDiagnoseSleep = { viewModel.diagnoseSleep() },
                )
                2 -> HistoryScreen(viewModel)
                // v2.4.0: Settings is a hub of clickable cards; each card
                // opens its own detail screen (8..12).
                3 -> SettingsScreen(
                    onOpenWatch = { goTo(8) },
                    onOpenProfile = { goTo(9) },
                    onOpenConnections = { goTo(10) },
                    onOpenMonitoring = { goTo(11) },
                    onOpenSleep = { goTo(12) },
                    onOpenCalibration = { goTo(4) },
                    onOpenAbout = { goTo(6) },
                    onOpenChangelog = { goTo(7) },
                )
                4 -> CalibrateScreen(viewModel)
                5 -> SnoreScreen(viewModel)
                6 -> AboutScreen()
                7 -> ChangelogScreen()
                8 -> WatchInstallScreen(onOpenCalibrate = { goTo(4) })
                9 -> BodyProfileSettingsScreen(viewModel)
                10 -> ConnectionsSettingsScreen(viewModel, onRequestHcPermissions)
                11 -> MonitoringSettingsScreen(viewModel)
                12 -> SleepSettingsScreen(
                    viewModel,
                    onRequestMicPermission,
                    // v2.3.2: sleep diagnostic card in the Sleep section.
                    onDiagnoseSleep = { viewModel.diagnoseSleep() },
                )
            }
        }
    }
}

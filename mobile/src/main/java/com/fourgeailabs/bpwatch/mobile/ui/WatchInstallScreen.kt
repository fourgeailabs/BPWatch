package com.fourgeailabs.bpwatch.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.adb.WatchInstaller
import com.fourgeailabs.bpwatch.mobile.wearable.WatchUpdateState
import com.fourgeailabs.bpwatch.mobile.wearable.WatchUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Watch app updates, two ways: the one-tap updater at the top (beams the
 * bundled watch APK over Bluetooth — no debugging), and the Wi-Fi debugging
 * installer below for first-time installs. The watch APK is bundled inside
 * this phone APK (see bundleWearApk), so updates ship with every phone
 * build — no PC or cable needed after the first setup.
 */
@Composable
fun WatchInstallScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("5555") }
    var pairPortText by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    val scroll = rememberScrollState()

    fun log(line: String) {
        // Snapshot state is thread-safe; the installer calls back from IO threads.
        logLines = logLines + line
    }

    /** Log a failure with its class, message, and top stack frames for diagnosis. */
    fun logError(prefix: String, e: Throwable) {
        log("$prefix: ${e::class.simpleName}: ${e.message}")
        e.stackTrace.take(10).forEach { log("    at $it") }
        e.cause?.let { log("  caused by ${it::class.simpleName}: ${it.message}") }
    }

    // If the app crashed last run, the launch dialog in MainActivity shows the
    // saved report (with a Copy button). We deliberately do NOT auto-read it
    // here: if the reader itself were the crash, the tab could never open.
    fun installer() = WatchInstaller(File(context.filesDir, "adbkey"), ::log)

    suspend fun extractBundledApk(): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "bpwatch-wear.apk")
        context.assets.open("bpwatch-wear.apk").use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs) }
        }
        out
    }

    fun parsePort(): Int? {
        val p = portText.trim().toIntOrNull()
        if (p == null || p !in 1..65535) {
            log("Port must be a number between 1 and 65535.")
            return null
        }
        return p
    }

    fun parsePairPort(): Int? {
        val p = pairPortText.trim().toIntOrNull()
        if (p == null || p !in 1..65535) {
            log("Enter the pairing port shown on the watch's pairing screen.")
            return null
        }
        return p
    }

    fun pairWatch() {
        val p = parsePairPort() ?: return
        val h = host.trim()
        if (h.isEmpty()) {
            log("Enter the watch's IP address first.")
            return
        }
        val code = pairCode.trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            log("Enter the 6-digit pairing code shown on the watch.")
            return
        }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { installer().pair(h, p, code) }
            } catch (e: Throwable) {
                // Throwable, not just Exception: even an Error must land in the
                // log as a diagnosis, never as a silent app kill.
                logError("Pairing failed", e)
            } finally {
                busy = false
            }
        }
    }

    fun testConnection() {
        val p = parsePort() ?: return
        val h = host.trim()
        if (h.isEmpty()) {
            log("Enter the watch's IP address first.")
            return
        }
        busy = true
        scope.launch {
            try {
                val model = withContext(Dispatchers.IO) { installer().probe(h, p) }
                log("Connected: $model")
            } catch (e: Throwable) {
                // Throwable, not just Exception: even an Error must land in the
                // log as a diagnosis, never as a silent app kill.
                logError("Couldn't connect", e)
                log("Hint: use the watch's ADB port from Wireless debugging (not the pairing port).")
            } finally {
                busy = false
            }
        }
    }

    fun installOrUpdate() {
        val p = parsePort() ?: return
        val h = host.trim()
        if (h.isEmpty()) {
            log("Enter the watch's IP address first.")
            return
        }
        busy = true
        progress = 0f
        scope.launch {
            try {
                log("Reading bundled watch app…")
                val apk = extractBundledApk()
                var lastPct = -1
                withContext(Dispatchers.IO) {
                    installer().installOrUpdate(apk, h, p) { sent, total ->
                        val pct = (sent * 100 / total.coerceAtLeast(1)).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            progress = sent / total.coerceAtLeast(1).toFloat()
                        }
                    }
                }
            } catch (e: Throwable) {
                // Throwable, not just Exception: even an Error must land in the
                // log as a diagnosis, never as a silent app kill.
                logError("Install failed", e)
            } finally {
                busy = false
                progress = null
            }
        }
    }

    LaunchedEffect(logLines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // v2.4.0: no headline here — this screen is opened from Settings and
        // the top bar already says "Watch app".
        Text(
            "Update BPWatch on your Galaxy Watch with one tap — no debugging, " +
                "no PC or cable. The watch app is bundled inside this phone " +
                "app, so every phone update carries the latest watch build.",
            style = MaterialTheme.typography.bodyMedium,
        )

        WatchUpdaterCard()

        Text(
            "First-time install over Wi-Fi debugging",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Only needed once, to get BPWatch onto a fresh watch — after " +
                "that, use the one-tap updater above. On the watch: " +
                "Settings → Developer options → Wireless debugging → turn " +
                "it on. Pair once using step 1 below, then install using the " +
                "IP and port from the main wireless-debugging screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Watch IP address") },
            placeholder = { Text("192.168.1.42") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        // Step 1: pair.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TintedIcon(icon = Icons.Filled.Bluetooth, contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "1 · Pair with watch",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "First time only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "On the watch: Wireless debugging → “Pair new device”. " +
                        "Enter the pairing port and the 6-digit code shown there. " +
                        "This is a one-time step — afterwards the watch trusts this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pairPortText,
                    onValueChange = { pairPortText = it },
                    label = { Text("Pairing port") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6-digit pairing code") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = ::pairWatch, enabled = !busy) {
                    Text("Pair with watch")
                }
            }
        }

        // Step 2: connect & install.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TintedIcon(icon = Icons.Filled.Link, contentDescription = null)
                    Text(
                        "2 · Connect & install",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("ADB port (main wireless-debugging screen)") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::testConnection, enabled = !busy) {
                        Text("Test connection")
                    }
                    Button(onClick = ::installOrUpdate, enabled = !busy) {
                        Text("Install / Update")
                    }
                }
            }
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (logLines.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                // NOTE: no verticalScroll here — the screen's outer Column
                // already scrolls, and nesting a scrollable inside a
                // scrollable crashes Compose with infinite-height constraints.
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Log",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    logLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One-tap watch updater (v1.15+): beams the bundled watch APK to the watch
 * over Bluetooth and the watch installs it itself. No debugging, no IP
 * addresses — and every setting on the watch survives the update.
 */
@Composable
private fun WatchUpdaterCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by WatchUpdateState.state.collectAsState()

    val busyUpdater = ui.status == WatchUpdateState.Status.CHECKING ||
        ui.status == WatchUpdateState.Status.SENDING
    val terminal = ui.status == WatchUpdateState.Status.DONE ||
        ui.status == WatchUpdateState.Status.UP_TO_DATE ||
        ui.status == WatchUpdateState.Status.ERROR ||
        ui.status == WatchUpdateState.Status.WAITING_WATCH

    // Read the bundled watch version once, and ask the watch for its own.
    LaunchedEffect(Unit) {
        val bundled = withContext(Dispatchers.IO) { WatchUpdater.getBundledApk(context) }
        if (bundled != null) {
            WatchUpdateState.setBundled(bundled.versionCode, bundled.versionName)
        }
        withContext(Dispatchers.IO) { WatchUpdater.requestWatchInfo(context) }
    }

    fun startUpdate() {
        scope.launch {
            WatchUpdateState.reset()
            val bundled = withContext(Dispatchers.IO) { WatchUpdater.getBundledApk(context) }
            if (bundled == null) {
                WatchUpdateState.error("Couldn't read the bundled watch app from this phone build.")
                return@launch
            }
            WatchUpdateState.setBundled(bundled.versionCode, bundled.versionName)
            WatchUpdateState.checking("Asking the watch what it's running…")
            val mark = System.currentTimeMillis()
            val asked = withContext(Dispatchers.IO) { WatchUpdater.sendBegin(context, bundled) }
            if (!asked) {
                WatchUpdateState.error(
                    "No watch reachable — is Bluetooth on and the watch connected?",
                )
                return@launch
            }
            // Wait for the watch's PATH_APK_READY reply (30s).
            val reply = withTimeoutOrNull(30_000L) {
                WatchUpdateState.state.first { s ->
                    s.lastReadyAt > mark ||
                        s.status == WatchUpdateState.Status.UP_TO_DATE ||
                        s.status == WatchUpdateState.Status.ERROR
                }
            }
            if (reply == null) {
                WatchUpdateState.error(
                    "The watch didn't answer. If it's on an older version, update it once " +
                        "with the debugging installer below — one-tap updates work after that.",
                )
                return@launch
            }
            if (reply.status == WatchUpdateState.Status.UP_TO_DATE ||
                reply.status == WatchUpdateState.Status.ERROR
            ) {
                return@launch // the state already carries the message
            }
            WatchUpdateState.sending(
                "Sending the update to your watch — keep this screen open. " +
                    "It's about 20 MB over Bluetooth, so give it a few minutes.",
            )
            val beamed = withContext(Dispatchers.IO) { WatchUpdater.sendApk(context, bundled) }
            if (!beamed) {
                WatchUpdateState.error(
                    "Couldn't beam the update. Keep the watch close and try again.",
                )
                return@launch
            }
            // Wait for the watch to confirm it received the APK (10 min).
            val result = withTimeoutOrNull(10 * 60_000L) {
                WatchUpdateState.state.first { s ->
                    s.status == WatchUpdateState.Status.DONE ||
                        s.status == WatchUpdateState.Status.UP_TO_DATE ||
                        s.status == WatchUpdateState.Status.ERROR ||
                        s.status == WatchUpdateState.Status.WAITING_WATCH
                }
            }
            if (result == null) {
                WatchUpdateState.error(
                    "Sent, but the watch hasn't confirmed. Check the watch — " +
                        "it may be waiting for your tap.",
                )
            }
            // Otherwise the listener already recorded the outcome.
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon = Icons.Filled.SystemUpdate, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Update watch app",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "One tap — no debugging",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Beams the latest watch build to your watch over Bluetooth and " +
                    "the watch installs it itself. Your thresholds and check " +
                    "schedule survive every update — nothing to re-enter.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "In this phone app: watch v${ui.bundledVersion ?: "…"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "On your watch now: v${ui.watchVersion ?: "unknown yet"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!watchSupportsOneTap(ui.watchVersion)) {
                Text(
                    "First time? The watch needs v1.15.0 or newer installed once via " +
                        "the Wi-Fi debugging installer below — only then can it " +
                        "receive one-tap updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // v2.4.0: the watch tells us upfront whether "Install unknown
            // apps" is allowed for BPWatch — warn before beaming 20 MB.
            if (ui.watchCanInstall == false) {
                Text(
                    "Heads up: on your watch, Settings → Apps → Special app " +
                        "access → Install unknown apps → allow BPWatch. " +
                        "Without it the watch can't install the update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ui.status != WatchUpdateState.Status.IDLE && ui.message.isNotEmpty()) {
                Text(
                    ui.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.status == WatchUpdateState.Status.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (busyUpdater) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::startUpdate, enabled = !busyUpdater) {
                    Text(if (terminal) "Check again" else "Send update to watch")
                }
            }
        }
    }
}

/** True when the watch is known to run a build containing the one-tap updater (v1.15.0+). */
private fun watchSupportsOneTap(version: String?): Boolean {
    if (version == null) return false
    val parts = version.substringBefore(" ").split(".")
    if (parts.size < 3) return false
    val numbers = parts.take(3).map { it.toIntOrNull() ?: return false }
    return numbers[0] * 10000 + numbers[1] * 100 + numbers[2] >= 11500
}

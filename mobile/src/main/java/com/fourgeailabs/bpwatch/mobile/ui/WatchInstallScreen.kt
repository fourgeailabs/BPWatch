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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.mobile.adb.WatchInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs/updates the watch app over Wi-Fi debugging. The watch APK is
 * bundled inside this phone APK (see bundleWearApk), so updates ship with
 * every phone build — no PC or cable needed after the first setup.
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Watch app", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Install or update BPWatch on your Galaxy Watch over Wi-Fi — " +
                "no PC or cable needed. The watch app is bundled inside this " +
                "phone app, so every phone update carries the latest watch build.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "On the watch: Settings → Developer options → Wireless debugging → " +
                "turn it on. First pair once using “Pair new device” below, then " +
                "install using the IP and port from the main wireless-debugging screen.",
            style = MaterialTheme.typography.bodySmall,
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pairing (first time only)", style = MaterialTheme.typography.titleMedium)
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
                Button(onClick = ::pairWatch, enabled = !busy) {
                    Text("Pair with watch")
                }
            }
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
            Button(onClick = ::testConnection, enabled = !busy) {
                Text("Test connection")
            }
            Button(onClick = ::installOrUpdate, enabled = !busy) {
                Text("Install / Update")
            }
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (logLines.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                // NOTE: no verticalScroll here — the screen's outer Column
                // already scrolls, and nesting a scrollable inside a
                // scrollable crashes Compose with infinite-height constraints.
                Column(modifier = Modifier.padding(12.dp)) {
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
    }
}

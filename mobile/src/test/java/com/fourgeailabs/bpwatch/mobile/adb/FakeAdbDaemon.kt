package com.fourgeailabs.bpwatch.mobile.adb

import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_AUTH
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_CLSE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_CNXN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_OKAY
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_OPEN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_WRTE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_RSAPUBLICKEY
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_SIGNATURE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_TOKEN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.MAXDATA
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.Message
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.VERSION
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Test double for a watch's adbd: real ADB transport framing, real RSA
 * challenge/response (SHA-256, like current AOSP), v1 sync push, and canned
 * shell responses. Lets the unit tests drive the real [AdbClient].
 */
class FakeAdbDaemon : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val uploaded: MutableMap<String, ByteArray> = ConcurrentHashMap()

    private data class StreamState(
        val clientLocal: Int,
        val daemonLocal: Int,
        val kind: Kind,
        val shellCmd: String = "",
        val syncBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var syncPath: String? = null,
        val syncFile: ByteArrayOutputStream = ByteArrayOutputStream(),
    )
    private enum class Kind { SYNC, SHELL }

    private val worker = thread(isDaemon = true, name = "fake-adbd") { serve() }

    private fun serve() {
        val sock: Socket
        try {
            sock = server.accept()
        } catch (_: Exception) {
            return
        }
        sock.soTimeout = 30_000
        val input: InputStream = sock.getInputStream()
        val output: OutputStream = sock.getOutputStream()
        fun send(m: Message) {
            synchronized(output) {
                output.write(AdbProtocol.encode(m))
                output.flush()
            }
        }

        var token: ByteArray? = null
        var authedKey: RSAPublicKey? = null
        var nextDaemonId = 100
        val streams = mutableMapOf<Int, StreamState>() // by daemon local id

        fun challenge() {
            if (token == null) token = Random.nextBytes(20)
            send(Message(A_AUTH, AUTH_TOKEN, 0, token!!))
        }

        fun rechallenge() {
            token = Random.nextBytes(20)
            send(Message(A_AUTH, AUTH_TOKEN, 0, token!!))
        }

        try {
            while (true) {
                val m = AdbProtocol.decode(input)
                when (m.command) {
                    A_CNXN -> challenge()
                    A_AUTH -> when (m.arg0) {
                        AUTH_RSAPUBLICKEY -> {
                            authedKey = AdbKey.parseKeyLine(m.payload)
                            // Simulate the user tapping "Allow" straight away.
                        }
                        AUTH_SIGNATURE -> {
                            val key = authedKey
                            val ok = key != null && token != null && runCatching {
                                val sig = Signature.getInstance("SHA256withRSA")
                                sig.initVerify(key)
                                sig.update(token!!)
                                sig.verify(m.payload)
                            }.getOrDefault(false)
                            if (ok) {
                                send(
                                    Message(
                                        A_CNXN, VERSION, MAXDATA,
                                        "device::fake-watch".toByteArray(Charsets.UTF_8),
                                    ),
                                )
                            } else {
                                rechallenge() // like a real daemon on bad signature
                            }
                        }
                    }
                    A_OPEN -> {
                        val dest = String(m.payload, Charsets.UTF_8)
                        val clientLocal = m.arg0
                        val daemonLocal = nextDaemonId++
                        val state = if (dest == "sync:") {
                            StreamState(clientLocal, daemonLocal, Kind.SYNC)
                        } else {
                            StreamState(
                                clientLocal, daemonLocal, Kind.SHELL,
                                shellCmd = dest.removePrefix("shell:"),
                            )
                        }
                        streams[daemonLocal] = state
                        send(Message(A_OKAY, daemonLocal, clientLocal, ByteArray(0)))
                        if (state.kind == Kind.SHELL) {
                            val out = runShell(state.shellCmd)
                            if (out.isNotEmpty()) {
                                send(
                                    Message(
                                        A_WRTE, daemonLocal, clientLocal,
                                        out.toByteArray(Charsets.UTF_8),
                                    ),
                                )
                            }
                            send(Message(A_CLSE, daemonLocal, clientLocal, ByteArray(0)))
                        }
                    }
                    A_WRTE -> {
                        val st = streams.values.find { it.clientLocal == m.arg0 }
                        if (st != null) {
                            send(Message(A_OKAY, st.daemonLocal, st.clientLocal, ByteArray(0)))
                            if (st.kind == Kind.SYNC) handleSyncPayload(st, m.payload, ::send)
                        }
                    }
                    A_OKAY -> { /* flow-control ack */ }
                    A_CLSE -> {
                        val st = streams.values.find { it.clientLocal == m.arg0 }
                        if (st != null) {
                            send(Message(A_CLSE, st.daemonLocal, st.clientLocal, ByteArray(0)))
                            streams.remove(st.daemonLocal)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Test over / client went away.
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun runShell(cmd: String): String {
        commands.add(cmd)
        return when {
            cmd.startsWith("getprop ro.product.model") -> "Galaxy Watch Ultra\n"
            cmd.startsWith("pm install") -> "Success\n"
            cmd.startsWith("pm uninstall") -> "Success\n"
            else -> ""
        }
    }

    /** Each WRTE from our client carries exactly one v1 sync request. */
    private fun handleSyncPayload(
        st: StreamState,
        payload: ByteArray,
        send: (Message) -> Unit,
    ) {
        if (payload.size < 8) return
        val id = String(payload, 0, 4, Charsets.US_ASCII)
        val len = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val body = payload.copyOfRange(8, minOf(8 + len, payload.size))
        when (id) {
            "SEND" -> {
                st.syncPath = String(body, Charsets.UTF_8).split(",").first()
                st.syncFile.reset()
            }
            "DATA" -> st.syncFile.write(body)
            "DONE" -> {
                uploaded[st.syncPath ?: "unknown"] = st.syncFile.toByteArray()
                val resp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                resp.put("OKAY".toByteArray(Charsets.US_ASCII))
                resp.putInt(0)
                send(Message(A_WRTE, st.daemonLocal, st.clientLocal, resp.array()))
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        worker.join(5_000)
    }
}

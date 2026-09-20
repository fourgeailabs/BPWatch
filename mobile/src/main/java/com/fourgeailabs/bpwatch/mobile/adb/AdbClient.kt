package com.fourgeailabs.bpwatch.mobile.adb

import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_AUTH
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_CLSE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_CNXN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_OKAY
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_OPEN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_SLES
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_STLS
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_WRTE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_RSAPUBLICKEY
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_SIGNATURE
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.AUTH_TOKEN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.MAXDATA
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.Message
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.VERSION
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.commandName
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal ADB-over-TCP client: just enough to push a file and run shell
 * commands on the watch (install the bundled watch APK, no PC needed).
 *
 * Single-threaded by design — call from a background thread.
 */
class AdbClient(private val keyDir: File) {

    /**
     * Connect to the watch's wireless debugging port (usually 5555) and run
     * the auth handshake. [onEvent] receives human-readable progress lines.
     */
    fun connect(
        host: String,
        port: Int,
        onEvent: (String) -> Unit,
        authTimeoutMs: Long = 120_000,
    ): AdbSession {
        onEvent("Connecting to $host:$port…")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 10_000)
        } catch (e: IOException) {
            throw AdbException(
                "Couldn't reach $host:$port — is the watch on the same Wi-Fi with " +
                    "wireless debugging turned on?",
                e,
            )
        }
        val session = AdbSession(socket, onEvent)
        try {
            session.handshake(keyDir, authTimeoutMs)
        } catch (e: Exception) {
            session.close()
            throw e
        }
        return session
    }

    /**
     * Connect to a *modern* wireless-debugging port (Galaxy Watch Ultra):
     * cleartext CNXN → daemon asks for a TLS upgrade (STLS, or Samsung's
     * SLES) → TLS 1.3 with our paired client certificate → the daemon's
     * CNXN arrives inside TLS → ordinary OPEN/OKAY/WRTE/CLSE from there.
     *
     * If the daemon answers the cleartext CNXN with its own CNXN (classic
     * wireless ADB without TLS), falls back to the plain handshake.
     */
    fun connectTls(
        host: String,
        port: Int,
        onEvent: (String) -> Unit,
        authTimeoutMs: Long = 120_000,
    ): AdbSession {
        val pair = AdbKey.loadOrCreate(keyDir)
        val cert = AdbTls.selfSignedCert(pair)
        onEvent("Connecting to $host:$port…")
        val tcp = Socket()
        try {
            tcp.connect(InetSocketAddress(host, port), 10_000)
        } catch (e: IOException) {
            throw AdbException(
                "Couldn't reach $host:$port — is the watch on the same Wi-Fi with " +
                    "wireless debugging turned on?",
                e,
            )
        }
        tcp.tcpNoDelay = true
        try {
            // 1. Cleartext CNXN, exactly like classic ADB.
            val rawOut = tcp.getOutputStream()
            rawOut.write(
                AdbProtocol.encode(
                    Message(A_CNXN, VERSION, MAXDATA, "host::bpwatch-installer".toByteArray(Charsets.UTF_8))
                )
            )
            rawOut.flush()

            // 2. Read the reply header raw first, for diagnostics, then the
            //    full first message (we may need it for the classic fallback).
            tcp.soTimeout = 15_000
            val rawIn = tcp.getInputStream()
            val header = AdbProtocol.readFully(rawIn, 24)
            onEvent("Watch replied: ${hexAscii(header)}")
            val hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = hdr.int
            val arg0 = hdr.int
            val arg1 = hdr.int
            val length = hdr.int
            val payload =
                if (length > 0 && length <= 65536) AdbProtocol.readFully(rawIn, length)
                else ByteArray(0)
            val first = Message(cmd, arg0, arg1, payload)

            when (cmd) {
                A_CNXN, A_AUTH -> {
                    // Classic daemon: either greeted with CNXN (real classic
                    // wireless ADB) or skipped straight to the AUTH challenge.
                    onEvent("Watch accepted a plain connection.")
                    val session = AdbSession(tcp, onEvent)
                    try {
                        session.handshakeWithFirstMessage(keyDir, first, authTimeoutMs)
                    } catch (e: Exception) {
                        session.close()
                        throw e
                    }
                    return session
                }
                A_STLS, A_SLES -> {
                    onEvent(
                        if (cmd == A_STLS) "Watch asked to upgrade to TLS."
                        else "Watch asked to upgrade to TLS (Samsung variant)."
                    )
                }
                else -> throw AdbException(
                    "Watch answered ${commandName(cmd)} instead of a TLS upgrade — " +
                        "is $port the ADB port from the main wireless-debugging screen " +
                        "(not the pairing port)?"
                )
            }

            // 3. Acknowledge with an empty message echoing the daemon's command.
            rawOut.write(AdbProtocol.encode(Message(cmd, 0, 0, ByteArray(0))))
            rawOut.flush()

            // 4. TLS 1.3 over the same socket, presenting our paired client cert.
            val sslCtx = AdbTls.connectContext(pair, cert)
            val ssl = sslCtx.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            try {
                ssl.startHandshake()
            } catch (e: Exception) {
                throw AdbException(
                    "TLS upgrade failed — the watch probably doesn't trust this phone yet. " +
                        "Pair first using the pairing port and the 6-digit code.", e)
            }
            onEvent("TLS established (${ssl.session.cipherSuite}).")

            // 5. Inside TLS the daemon sends CNXN; the client never re-sends it.
            val session = AdbSession(ssl, onEvent)
            try {
                session.handshakeTls(authTimeoutMs)
            } catch (e: Exception) {
                session.close()
                throw e
            }
            return session
        } catch (e: Exception) {
            try {
                tcp.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun hexAscii(bytes: ByteArray): String {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val ascii = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
        return "$hex  \"$ascii\""
    }
}

class AdbSession internal constructor(
    private val socket: Socket,
    private val onEvent: (String) -> Unit,
) : Closeable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val nextId = AtomicInteger(1)
    private val sendLock = Any()

    /**
     * Messages read ahead while waiting for something else. The daemon can
     * pipeline OKAY/WRTE/CLSE, so anything not for the current waiter is
     * stashed here instead of dropped.
     */
    private val pending = ArrayDeque<Message>()

    private data class Stream(val localId: Int, val remoteId: Int)

    // ------------------------------------------------------------------ handshake

    internal fun handshake(keyDir: File, authTimeoutMs: Long) {
        val pair = AdbKey.loadOrCreate(keyDir)
        send(Message(A_CNXN, VERSION, MAXDATA, "host::bpwatch-installer".toByteArray(Charsets.UTF_8)))
        handshakeLoop(pair, first = null, authTimeoutMs)
    }

    /**
     * Variant where the daemon's first reply was already read (used by
     * [AdbClient.connectTls], which peeks at the reply to decide between the
     * TLS upgrade and the classic handshake).
     */
    internal fun handshakeWithFirstMessage(keyDir: File, first: Message, authTimeoutMs: Long) {
        val pair = AdbKey.loadOrCreate(keyDir)
        handshakeLoop(pair, first, authTimeoutMs)
    }

    private fun handshakeLoop(pair: java.security.KeyPair, first: Message?, authTimeoutMs: Long) {
        var sentPubKey = false
        val deadline = System.currentTimeMillis() + authTimeoutMs
        var unconsumed: Message? = first
        while (true) {
            val m = unconsumed ?: recv(deadline, "handshake")
            unconsumed = null
            when (m.command) {
                A_CNXN -> {
                    onEvent("Watch accepted the connection.")
                    return
                }
                A_AUTH -> when (m.arg0) {
                    AUTH_TOKEN -> {
                        if (!sentPubKey) {
                            send(Message(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbKey.publicKeyPayload(pair)))
                            sentPubKey = true
                            onEvent("Check the watch and tap “Allow” for wireless debugging…")
                        }
                        send(Message(A_AUTH, AUTH_SIGNATURE, 0, AdbKey.sign(pair.private, m.payload)))
                    }
                    else -> throw AdbException("Unexpected AUTH type ${m.arg0} from watch")
                }
                else -> throw AdbException("Unexpected ${commandName(m.command)} during handshake")
            }
        }
    }

    /**
     * Post-TLS handshake for modern wireless debugging: the daemon sends its
     * CNXN inside the TLS session and the client never re-sends CNXN or does
     * AUTH — the client certificate presented during the TLS handshake *is*
     * the authentication.
     */
    internal fun handshakeTls(authTimeoutMs: Long) {
        val deadline = System.currentTimeMillis() + authTimeoutMs
        val m = recv(deadline, "TLS handshake")
        if (m.command != A_CNXN) {
            throw AdbException(
                "Expected the watch's greeting after the TLS upgrade, " +
                    "got ${commandName(m.command)}")
        }
        onEvent("Watch accepted the TLS connection.")
    }

    // ------------------------------------------------------------------ shell

    /** Run a shell command on the watch, return its stdout. */
    fun shell(cmd: String, timeoutMs: Long = 90_000): String {
        val s = open("shell:$cmd", 20_000)
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val m = recvMatching(deadline, "shell:$cmd") { msg ->
                (msg.command == A_WRTE || msg.command == A_CLSE) && msg.arg1 == s.localId
            }
            if (m.command == A_WRTE) {
                out.write(m.payload)
                send(Message(A_OKAY, s.localId, s.remoteId, ByteArray(0)))
            } else {
                send(Message(A_CLSE, s.localId, s.remoteId, ByteArray(0)))
                break
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ------------------------------------------------------------------ sync push (v1)

    /** Push [local] to [remotePath] on the watch using the v1 sync protocol. */
    fun push(local: File, remotePath: String, onProgress: (sentBytes: Long, totalBytes: Long) -> Unit) {
        val total = local.length()
        val s = open("sync:", 20_000)
        try {
            syncRequest(s, "SEND", "$remotePath,0644".toByteArray(Charsets.UTF_8))
            var sent = 0L
            local.inputStream().buffered().use { ins ->
                val buf = ByteArray(MAXDATA - 8) // DATA header is 8 bytes; one WRTE each
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    syncRequest(s, "DATA", buf.copyOf(n))
                    sent += n
                    onProgress(sent, total)
                }
            }
            val mtime = (local.lastModified() / 1000).toInt()
            val donePayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(mtime).array()
            syncRequest(s, "DONE", donePayload)

            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                val m = recvMatching(deadline, "upload response") { msg ->
                    (msg.command == A_WRTE || msg.command == A_CLSE) && msg.arg1 == s.localId
                }
                if (m.command == A_WRTE) {
                    send(Message(A_OKAY, s.localId, s.remoteId, ByteArray(0)))
                    val p = m.payload
                    if (p.size < 8) continue
                    val id = String(p, 0, 4, Charsets.US_ASCII)
                    if (id == "OKAY") break
                    if (id == "FAIL") {
                        val len = ByteBuffer.wrap(p, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val msg = String(p, 8, minOf(len, p.size - 8), Charsets.UTF_8)
                        throw AdbException("Watch refused the upload: $msg")
                    }
                } else {
                    throw AdbException("Watch closed the upload stream unexpectedly")
                }
            }
            send(Message(A_CLSE, s.localId, s.remoteId, ByteArray(0)))
            // Drain the daemon's CLSE reply (best effort).
            try {
                recvMatching(System.currentTimeMillis() + 5_000, "upload close") { msg ->
                    (msg.command == A_WRTE || msg.command == A_CLSE) && msg.arg1 == s.localId
                }
            } catch (_: AdbException) {
                // Non-fatal: upload already acknowledged OKAY.
            }
        } catch (e: Exception) {
            try {
                send(Message(A_CLSE, s.localId, s.remoteId, ByteArray(0)))
            } catch (_: Exception) {
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ internals

    private fun open(destination: String, timeoutMs: Long): Stream {
        val local = nextId.getAndAdd(1)
        send(Message(A_OPEN, local, 0, destination.toByteArray(Charsets.UTF_8)))
        val deadline = System.currentTimeMillis() + timeoutMs
        val m = recvMatching(deadline, "open $destination") { msg ->
            (msg.command == A_OKAY || msg.command == A_CLSE) && msg.arg1 == local
        }
        if (m.command == A_CLSE) {
            throw AdbException("Watch refused '$destination'")
        }
        return Stream(local, m.arg0)
    }

    /** One sync request (SEND/DATA/DONE) as a single WRTE, acknowledged by the daemon. */
    private fun syncRequest(s: Stream, id: String, payload: ByteArray) {
        require(id.length == 4)
        val req = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        req.put(id.toByteArray(Charsets.US_ASCII))
        req.putInt(payload.size)
        req.put(payload)
        val bytes = req.array()
        require(bytes.size <= MAXDATA) { "sync request too large" }
        send(Message(A_WRTE, s.localId, s.remoteId, bytes))
        val deadline = System.currentTimeMillis() + 30_000
        val m = recvMatching(deadline, "sync $id") { msg ->
            (msg.command == A_OKAY || msg.command == A_CLSE) && msg.arg1 == s.localId
        }
        if (m.command == A_CLSE) {
            throw AdbException("Watch closed stream during upload")
        }
    }

    /** Next message: stashed ones first, then the socket (with deadline). */
    private fun recv(deadlineMs: Long, what: String): Message =
        recvMatching(deadlineMs, what) { true }

    /**
     * Next message matching [match]: stashed ones first, then the socket
     * (with deadline). Anything else is stashed for whichever waiter it
     * belongs to. Crucially, a waiter never re-takes a message it already
     * rejected — that was a livelock (stale CLSE from a dead stream bouncing
     * between the waiter and [pending] forever).
     */
    private fun recvMatching(
        deadlineMs: Long,
        what: String,
        match: (Message) -> Boolean,
    ): Message {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (match(m)) {
                iter.remove()
                return m
            }
        }
        while (true) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) throw AdbException("Timed out waiting for watch ($what)")
            socket.soTimeout = minOf(remaining, 10_000).toInt().coerceAtLeast(1)
            try {
                val m = AdbProtocol.decode(input)
                if (match(m)) return m
                pending.add(m)
            } catch (e: SocketTimeoutException) {
                // Loop back and re-check the deadline.
            } catch (e: EOFException) {
                throw AdbException("Watch closed the connection ($what)", e)
            }
        }
    }

    private fun send(m: Message) {
        synchronized(sendLock) {
            try {
                output.write(AdbProtocol.encode(m))
                output.flush()
            } catch (e: IOException) {
                throw AdbException("Lost connection to watch", e)
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

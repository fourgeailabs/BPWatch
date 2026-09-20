package com.fourgeailabs.bpwatch.mobile.adb

import com.fourgeailabs.bpwatch.mobile.adb.spake2.Spake2Exchange
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Wireless-debugging pairing client for the Galaxy Watch Ultra.
 *
 * Flow (mirrors AOSP's pairing_connection.cpp):
 *  1. TCP connect to the *pairing* port (the one shown with the 6-digit code).
 *  2. TLS 1.3 immediately (Conscrypt, for the keying-material exporter).
 *  3. password = ASCII(pairing code) + TLS-Exporter("adb-label\\0", 64 bytes).
 *  4. SPAKE2 (BoringSSL variant) message exchange, framed as
 *     version(1) + type(1) + BE32 length.
 *  5. HKDF-SHA256 over the SPAKE2 key material -> AES-128-GCM key.
 *  6. Send our 8192-byte PEER_INFO (type 0 + ADB public-key line), encrypted;
 *     read and decrypt theirs.
 *
 * Successful pairing registers our existing RSA public key on the watch —
 * no new key is issued. After this, connect to the *ADB* port (the one on
 * the main wireless-debugging screen) with [AdbClient.connectTls].
 *
 * Single-threaded by design — call from a background thread.
 */
class PairingClient(
    private val keyDir: File,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val HEADER_VERSION: Byte = 1
        private const val TYPE_SPAKE2: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_SIZE = 8192
        private const val PEER_KEY_TYPE: Byte = 0 // ADB_RSA_PUB_KEY
        private const val EXPORTER_LABEL = "adb-label\u0000"
        private const val EXPORTER_SIZE = 64
        private const val MAX_FRAME = 65536
    }

    /**
     * Run the full pairing handshake. Returns silently on success; throws
     * [AdbException] with a human-readable message on failure (including a
     * wrong/expired pairing code, which shows up as a PEER_INFO decrypt failure).
     */
    fun pair(host: String, port: Int, code: String) {
        require(code.trim().length == 6 && code.trim().all { it.isDigit() }) {
            "Pairing code must be the 6 digits shown on the watch"
        }
        val pair = AdbKey.loadOrCreate(keyDir)
        val cert = AdbTls.selfSignedCert(pair)

        onLog("Connecting to $host:$port…")
        val tcp = Socket()
        try {
            tcp.connect(InetSocketAddress(host, port), 10_000)
        } catch (e: Exception) {
            throw AdbException(
                "Couldn't reach $host:$port — use the IP and *pairing* port shown " +
                    "with the 6-digit code on the watch (not the main ADB port).", e)
        }
        tcp.use {
            it.tcpNoDelay = true
            it.soTimeout = 30_000
            val sslCtx = AdbTls.pairingContext(pair, cert)
            val ssl = sslCtx.socketFactory.createSocket(it, host, port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            try {
                ssl.startHandshake()
            } catch (e: Exception) {
                throw AdbException(
                    "TLS handshake failed on the pairing port — is $port the pairing " +
                        "port from the watch's pairing-code screen?", e)
            }
            onLog("TLS 1.3 established (${ssl.session.cipherSuite}).")
            ssl.use { s ->
                runPairing(s, pair, code.trim())
            }
        }
    }

    private fun runPairing(ssl: SSLSocket, pair: java.security.KeyPair, code: String) {
        val input = DataInputStream(ssl.inputStream)
        val output = DataOutputStream(ssl.outputStream)

        // SPAKE2 password: pairing code + TLS exporter material.
        val exporter = try {
            AdbTls.exportKeyingMaterial(ssl, EXPORTER_LABEL, EXPORTER_SIZE)
        } catch (e: Exception) {
            throw AdbException("Couldn't derive the pairing key from TLS (exporter failed).", e)
        }
        val password = code.toByteArray(Charsets.US_ASCII) + exporter

        val spake = Spake2Exchange(
            isAlice = true,
            myName = "adb pair client\u0000".toByteArray(Charsets.UTF_8),
            theirName = "adb pair server\u0000".toByteArray(Charsets.UTF_8),
        )
        val ourMsg: ByteArray
        try {
            ourMsg = spake.generateMessage(password)
        } catch (e: Exception) {
            throw AdbException("Couldn't start the pairing exchange.", e)
        }
        writeFrame(output, TYPE_SPAKE2, ourMsg)
        onLog("Pairing request sent — waiting for the watch…")

        val (spakeType, theirMsg) = readFrame(input, "pairing response")
        if (spakeType != TYPE_SPAKE2 || theirMsg.size != 32) {
            throw AdbException("Watch sent an unexpected pairing response.")
        }
        val keyMaterial: ByteArray
        try {
            keyMaterial = spake.processMessage(theirMsg)
        } catch (e: Exception) {
            throw AdbException("Pairing key exchange failed.", e)
        }
        onLog("Secure channel established.")

        val aesKey = hkdfSha256(
            ikm = keyMaterial,
            salt = ByteArray(0),
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8),
            length = 16,
        )
        val cipher = PairingCipher(aesKey)

        // Our peer info: type byte + ADB public-key line, zero-padded to 8192.
        val peerInfo = ByteArray(PEER_INFO_SIZE)
        peerInfo[0] = PEER_KEY_TYPE
        AdbKey.peerInfoKeyLine(pair).copyInto(peerInfo, 1)
        writeFrame(output, TYPE_PEER_INFO, cipher.encrypt(peerInfo))

        val (infoType, theirEncrypted) = readFrame(input, "watch peer info")
        if (infoType != TYPE_PEER_INFO) {
            throw AdbException("Watch sent an unexpected pairing response.")
        }
        val theirInfo = cipher.decrypt(theirEncrypted)
            ?: throw AdbException(
                "The watch rejected the pairing — the code was probably wrong " +
                    "or expired. Get a fresh code from the watch and try again.")
        if (theirInfo.size != PEER_INFO_SIZE) {
            throw AdbException("Watch sent a malformed pairing response.")
        }
        onLog("Paired! The watch now trusts this phone.")
        onLog("Now connect to the *main* wireless-debugging port to install the app.")
    }

    // ------------------------------------------------------------------ framing

    private fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        header.put(HEADER_VERSION)
        header.put(type)
        header.putInt(payload.size)
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: InputStream, what: String): Pair<Byte, ByteArray> {
        val header = ByteArray(6)
        try {
            DataInputStream(input).readFully(header)
        } catch (e: EOFException) {
            throw AdbException("Watch closed the connection while waiting for $what.", e)
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get()
        val type = buf.get()
        val length = buf.int
        if (version != HEADER_VERSION) {
            throw AdbException("Watch sent an unexpected pairing response (v$version).")
        }
        if (type != TYPE_SPAKE2 && type != TYPE_PEER_INFO) {
            throw AdbException("Watch sent an unexpected pairing response.")
        }
        if (length <= 0 || length > MAX_FRAME) {
            throw AdbException("Watch sent an unexpected pairing response.")
        }
        val payload = ByteArray(length)
        try {
            DataInputStream(input).readFully(payload)
        } catch (e: EOFException) {
            throw AdbException("Watch closed the connection while waiting for $what.", e)
        }
        return type to payload
    }

    // ------------------------------------------------------------------ crypto

    /** HKDF-SHA256 (RFC 5869), matching AOSP's pairing_auth HKDF step. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(realSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - pos)
            previous.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * AES-128-GCM with per-direction sequence counters, matching AOSP's
     * Aes128Gcm: 12-byte nonce, little-endian u64 counter in the first 8 bytes,
     * separate encrypt/decrypt counters starting at 0, no AAD.
     */
    internal class PairingCipher(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private var encSeq = 0L
        private var decSeq = 0L

        fun encrypt(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(encSeq++)))
            return c.doFinal(plain)
        }

        fun decrypt(ciphertext: ByteArray): ByteArray? {
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(decSeq++)))
                c.doFinal(ciphertext)
            } catch (_: Exception) {
                null // GCM auth failure — wrong code, essentially.
            }
        }

        private fun nonce(seq: Long): ByteArray {
            val n = ByteArray(12)
            ByteBuffer.wrap(n).order(ByteOrder.LITTLE_ENDIAN).putLong(seq)
            return n
        }
    }
}

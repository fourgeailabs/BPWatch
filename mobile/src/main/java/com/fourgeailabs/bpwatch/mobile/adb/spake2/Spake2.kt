package com.fourgeailabs.bpwatch.mobile.adb.spake2

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SPAKE2 as used by ADB wireless-debugging pairing, matching BoringSSL's
 * `spake25519.c` (which is what adbd runs).
 *
 * Protocol (from BoringSSL + AOSP's pairing implementation):
 * - password = ASCII(pairing code) || 64-byte TLS exporter
 * - w = passwordScalar(password)  (SHA-512, reduced, low 3 bits cleared)
 * - Alice (phone): X = x*G; sends X* = X + w*M
 * - Bob (watch):   Y = y*G; sends Y* = Y + w*N
 * - Alice computes: K = x*(Y* - w*N) = x*Y
 * - Bob computes:   K = y*(X* - w*M) = y*X
 * - key = SHA-512(transcript), where the transcript is length-prefixed names,
 *   messages, DH secret, and password hash (BoringSSL's construction).
 *
 * M and N are the fixed BoringSSL points derived from
 * SHA-256("edwards25519 point generation seed (M|N)").
 */
internal class Spake2Exchange(
    private val isAlice: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    private var privateScalar: BigInteger? = null
    private var myMsg: ByteArray? = null
    private var passwordHash: ByteArray? = null
    private var passwordW: BigInteger? = null

    /** Generate our 32-byte handshake message with a fresh random private key. */
    fun generateMessage(password: ByteArray): ByteArray {
        val priv = ByteArray(64)
        java.security.SecureRandom().nextBytes(priv)
        return generateMessage(password, priv)
    }

    /** Generate our 32-byte handshake message. */
    fun generateMessage(password: ByteArray, privateKey64: ByteArray): ByteArray {
        check(privateScalar == null) { "message already generated" }
        require(privateKey64.size == 64) { "expected 64-byte private key" }

        // Ephemeral scalar: reduce the 64-byte key, clear low 3 bits (cofactor).
        val x = Ed25519Spake.reduceToScalar(privateKey64)
            .shiftRight(3).shiftLeft(3)
        privateScalar = x

        val pwh = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHash = pwh
        val w = Ed25519Spake.passwordScalar(password)
        passwordW = w

        val xg = Ed25519Spake.scalarMult(x, Ed25519Spake.B)
        val maskPoint = if (isAlice) Ed25519Spake.M else Ed25519Spake.N
        val masked = Ed25519Spake.pointAdd(xg, Ed25519Spake.scalarMult(w, maskPoint))
        val msg = Ed25519Spake.encodePoint(masked)
        myMsg = msg
        return msg
    }

    /** Process the peer's 32-byte message; returns the 64-byte shared key. */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        val x = checkNotNull(privateScalar) { "generateMessage not called" }
        val msg = checkNotNull(myMsg) { "generateMessage not called" }
        val pwh = checkNotNull(passwordHash) { "generateMessage not called" }
        val w = checkNotNull(passwordW) { "generateMessage not called" }
        require(theirMsg.size == 32) { "peer's message is not 32 bytes" }

        val qStar = Ed25519Spake.decodePoint(theirMsg)
            ?: throw IllegalArgumentException("Point received from peer was not on the curve.")

        // Unmask: Q = Q* - w*(N for Alice, M for Bob).
        val maskPoint = if (isAlice) Ed25519Spake.N else Ed25519Spake.M
        val q = Ed25519Spake.pointAdd(qStar, Ed25519Spake.pointNeg(
            Ed25519Spake.scalarMult(w, maskPoint)))

        // DH = x * Q.
        val dh = Ed25519Spake.scalarMult(x, q)
        val dhBytes = Ed25519Spake.encodePoint(dh)

        // BoringSSL transcript: 8-byte LE length-prefixed names, messages, DH, pw hash.
        val sha = MessageDigest.getInstance("SHA-512")
        fun update(data: ByteArray) {
            var len = data.size.toLong()
            repeat(8) {
                sha.update((len and 0xFF).toByte())
                len = len ushr 8
            }
            sha.update(data)
        }
        if (isAlice) {
            update(myName); update(theirName); update(msg); update(theirMsg)
        } else {
            update(theirName); update(myName); update(theirMsg); update(msg)
        }
        update(dhBytes)
        update(pwh)
        return sha.digest()
    }
}

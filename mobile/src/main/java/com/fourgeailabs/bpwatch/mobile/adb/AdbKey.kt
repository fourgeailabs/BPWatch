package com.fourgeailabs.bpwatch.mobile.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * ADB host identity key ("adbkey").
 *
 * The public key is stored in AOSP's adbkey.pub wire format: base64 of the
 * 524-byte little-endian RSA struct (word count, n0inv, modulus words, R^2
 * words, exponent) followed by " name". This is the exact format adbd parses —
 * both for the AUTH RSAPUBLICKEY step and for the PEER_INFO line sent during
 * wireless-debugging pairing.
 *
 * Keys persist in [dir] so the watch recognises us after the first pairing.
 */
internal object AdbKey {
    private const val PRIVATE_NAME = "adbkey"
    private const val PUBLIC_NAME = "adbkey.pub"

    /** Words (32-bit) in a 2048-bit RSA modulus. */
    private const val MOD_WORDS = 64
    /** Size of the AOSP RSA wire struct. */
    private const val STRUCT_SIZE = 524

    fun loadOrCreate(dir: File): KeyPair {
        dir.mkdirs()
        val privFile = File(dir, PRIVATE_NAME)
        val pubFile = File(dir, PUBLIC_NAME)
        if (privFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = parseWireStruct(pubFile)
                    ?: parseLegacyPkcs1(pubFile)?.also {
                        // Migrate: keep the keypair, rewrite the pub file in the
                        // format adbd actually understands.
                        pubFile.writeBytes(publicKeyPayload(KeyPair(it, priv)))
                    }
                if (pub != null) return KeyPair(pub, priv)
            } catch (_: Exception) {
                // Corrupt key files — fall through and regenerate.
            }
            privFile.delete()
            pubFile.delete()
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        privFile.writeBytes(pair.private.encoded) // PKCS#8 DER
        pubFile.writeBytes(publicKeyPayload(pair))
        return pair
    }

    /**
     * adbkey.pub payload bytes: base64(524-byte struct) + " bpwatch@phone".
     * This is what adbd expects in AUTH and in pairing PEER_INFO.
     */
    fun publicKeyPayload(pair: KeyPair): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(keyStruct(pair.public as RSAPublicKey))
        return "$b64 bpwatch@phone".toByteArray(Charsets.UTF_8)
    }

    /**
     * The key line as it goes into the pairing PEER_INFO payload:
     * base64(524-byte struct) + " bpwatch@phone\n".
     */
    fun peerInfoKeyLine(pair: KeyPair): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(keyStruct(pair.public as RSAPublicKey))
        return "$b64 bpwatch@phone\n".toByteArray(Charsets.UTF_8)
    }

    /** RSA PKCS#1 v1.5 signature of [token] with SHA-256 (matches current AOSP adbd). */
    fun sign(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(token)
        return sig.sign()
    }

    /**
     * Encode an RSA-2048 public key as AOSP's 524-byte little-endian struct:
     * u32 word count (64), u32 n0inv, u32[64] modulus, u32[64] R^2 mod n, u32 exponent.
     */
    internal fun keyStruct(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() <= 2048) { "only RSA-2048 supported" }
        val buf = java.nio.ByteBuffer.allocate(STRUCT_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val r32 = BigInteger.ZERO.setBit(32)
        buf.putInt(MOD_WORDS)
        // n0inv = -1 / n[0] mod 2^32
        val n0inv = key.modulus.remainder(r32).modInverse(r32).negate().and(r32.subtract(BigInteger.ONE))
        buf.putInt(n0inv.toInt())
        putWords(buf, key.modulus)
        // rr = R^2 mod n with R = 2^2048
        val r = BigInteger.ZERO.setBit(2048)
        putWords(buf, r.modPow(BigInteger.valueOf(2), key.modulus))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }

    private fun putWords(buf: java.nio.ByteBuffer, v: BigInteger) {
        val r32 = BigInteger.ZERO.setBit(32)
        var tmp = v
        repeat(MOD_WORDS) {
            val dm = tmp.divideAndRemainder(r32)
            buf.putInt(dm[1].toInt())
            tmp = dm[0]
        }
    }

    /** Parse our own adbkey.pub back to a public key (null if unparsable). */
    private fun parseWireStruct(pubFile: File): RSAPublicKey? {
        return try {
            parseKeyLine(pubFile.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse an ADB public-key line — "base64(524-byte struct) name" — as sent
     * in AUTH RSAPUBLICKEY and in the pairing PEER_INFO. Null if unparsable.
     */
    internal fun parseKeyLine(line: ByteArray): RSAPublicKey? {
        return try {
            val text = line.toString(Charsets.UTF_8).trim().split(" ").first()
            val raw = Base64.getDecoder().decode(text)
            if (raw.size != STRUCT_SIZE) return null
            val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buf.int != MOD_WORDS) return null
            buf.int // n0inv — not needed to reconstruct the key
            val r32 = BigInteger.ZERO.setBit(32)
            var n = BigInteger.ZERO
            repeat(MOD_WORDS) { i ->
                val w = buf.int.toLong() and 0xFFFF_FFFFL
                n = n.add(BigInteger.valueOf(w).multiply(r32.pow(i)))
            }
            repeat(MOD_WORDS) { buf.int } // rr — not needed
            val e = BigInteger.valueOf(buf.int.toLong() and 0xFFFF_FFFFL)
            KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ legacy

    /** Parse the pre-v6 adbkey.pub (base64 PKCS#1 DER) for one-time migration. */
    private fun parseLegacyPkcs1(pubFile: File): RSAPublicKey? {
        return try {
            val text = pubFile.readText(Charsets.UTF_8).trim().split(" ").first()
            parsePkcs1(Base64.getDecoder().decode(text))
        } catch (_: Exception) {
            null
        }
    }

    /** Minimal DER parser for PKCS#1 RSAPublicKey: SEQUENCE { INTEGER n, INTEGER e }. */
    internal fun parsePkcs1(der: ByteArray): RSAPublicKey {
        var p = 0
        require(der[p++] == 0x30.toByte()) { "not a SEQUENCE" }
        p = readLength(der, p).second
        require(der[p++] == 0x02.toByte())
        val (nLen, p2) = readLength(der, p); p = p2
        val n = BigInteger(der.copyOfRange(p, p + nLen)); p += nLen
        require(der[p++] == 0x02.toByte())
        val (eLen, p3) = readLength(der, p); p = p3
        val e = BigInteger(der.copyOfRange(p, p + eLen))
        val spec = RSAPublicKeySpec(n, e)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun readLength(der: ByteArray, p0: Int): Pair<Int, Int> {
        var p = p0
        val first = der[p++].toInt() and 0xFF
        if (first < 0x80) return first to p
        val count = first and 0x7F
        var len = 0
        repeat(count) { len = (len shl 8) or (der[p++].toInt() and 0xFF) }
        return len to p
    }
}

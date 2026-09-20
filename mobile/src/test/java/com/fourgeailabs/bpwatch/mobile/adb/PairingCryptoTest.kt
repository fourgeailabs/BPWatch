package com.fourgeailabs.bpwatch.mobile.adb

import java.io.File
import java.security.KeyPairGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val client = PairingClient(File("/tmp"), {})
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = client.hkdfSha256(ikm, salt, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `hkdf with empty salt matches pairing derivation shape`() {
        val client = PairingClient(File("/tmp"), {})
        // 64-byte SPAKE2-style key material, empty salt, the real info string.
        val ikm = ByteArray(64) { it.toByte() }
        val k1 = client.hkdfSha256(ikm, ByteArray(0), "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
        val k2 = client.hkdfSha256(ikm, ByteArray(0), "adb pairing_auth aes-128-gcm key".toByteArray(), 16)
        assertEquals(16, k1.size)
        assertArrayEquals(k1, k2)
    }

    @Test
    fun `pairing cipher round-trips and rejects wrong keys`() {
        val enc = PairingClient.PairingCipher(ByteArray(16) { 0x42 })
        val plain = ByteArray(8192) { (it and 0xFF).toByte() }
        val ct = enc.encrypt(plain)
        // AES-GCM adds a 16-byte tag.
        assertEquals(8192 + 16, ct.size)

        val dec = PairingClient.PairingCipher(ByteArray(16) { 0x42 })
        assertArrayEquals(plain, dec.decrypt(ct))

        // Wrong key -> GCM auth failure -> null (this is how a wrong pairing
        // code surfaces).
        val wrong = PairingClient.PairingCipher(ByteArray(16) { 0x43 })
        assertNull(wrong.decrypt(ct))
    }

    @Test
    fun `pairing cipher nonces advance per direction`() {
        val a = PairingClient.PairingCipher(ByteArray(16) { 1 })
        val b = PairingClient.PairingCipher(ByteArray(16) { 1 })
        val m1 = a.encrypt("one".toByteArray())
        val m2 = a.encrypt("one".toByteArray())
        // Same plaintext, different nonce -> different ciphertext.
        assertFalse(m1.contentEquals(m2))
        assertArrayEquals("one".toByteArray(), b.decrypt(m1))
        assertArrayEquals("one".toByteArray(), b.decrypt(m2))
    }

    @Test
    fun `adb key struct round-trips through base64`() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val rsa = pair.public as java.security.interfaces.RSAPublicKey

        val struct = AdbKey.keyStruct(rsa)
        assertEquals(524, struct.size)

        // Write it in adbkey.pub shape, then load it back through AdbKey.
        val dir = File(System.getProperty("java.io.tmpdir"), "adbkeytest-${System.nanoTime()}")
        try {
            dir.mkdirs()
            // A private key must exist for loadOrCreate to take the load path;
            // store ours so the round-trip is over a real keypair.
            File(dir, "adbkey").writeBytes(pair.private.encoded)
            File(dir, "adbkey.pub").writeBytes(
                (java.util.Base64.getEncoder().encodeToString(struct) + " bpwatch@phone")
                    .toByteArray(Charsets.UTF_8)
            )
            val reloaded = AdbKey.loadOrCreate(dir)
            val rsa2 = reloaded.public as java.security.interfaces.RSAPublicKey
            assertEquals(rsa.modulus, rsa2.modulus)
            assertEquals(rsa.publicExponent, rsa2.publicExponent)
            assertArrayEquals(pair.private.encoded, reloaded.private.encoded)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stls and sles command names resolve`() {
        assertEquals("STLS", AdbProtocol.commandName(AdbProtocol.A_STLS))
        assertEquals("SLES", AdbProtocol.commandName(AdbProtocol.A_SLES))
        // Little-endian ASCII check: bytes on the wire read "STLS"/"SLES".
        assertEquals("STLS", String(byteArrayOf(0x53, 0x54, 0x4C, 0x53), Charsets.US_ASCII))
        assertEquals("SLES", String(byteArrayOf(0x53, 0x4C, 0x45, 0x53), Charsets.US_ASCII))
    }
}

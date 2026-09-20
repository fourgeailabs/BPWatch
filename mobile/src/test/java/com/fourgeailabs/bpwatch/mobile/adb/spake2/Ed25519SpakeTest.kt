package com.fourgeailabs.bpwatch.mobile.adb.spake2

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519SpakeTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    @Test
    fun `base point encodes to RFC 8032 value`() {
        val enc = Ed25519Spake.encodePoint(Ed25519Spake.B)
        assertEquals(
            "5866666666666666666666666666666666666666666666666666666666666666",
            hex(enc),
        )
    }

    @Test
    fun `one times B equals B`() {
        val one = BigInteger.ONE
        val r = Ed25519Spake.scalarMult(one, Ed25519Spake.B)
        assertArrayEquals(
            Ed25519Spake.encodePoint(Ed25519Spake.B),
            Ed25519Spake.encodePoint(r),
        )
    }

    @Test
    fun `scalar multiplication satisfies DH`() {
        val a = BigInteger("123456789abcdef0", 16)
        val b = BigInteger("0fedcba987654321", 16)
        val ab = Ed25519Spake.scalarMult(a, Ed25519Spake.scalarMult(b, Ed25519Spake.B))
        val ba = Ed25519Spake.scalarMult(b, Ed25519Spake.scalarMult(a, Ed25519Spake.B))
        assertArrayEquals(Ed25519Spake.encodePoint(ab), Ed25519Spake.encodePoint(ba))
    }

    @Test
    fun `decode rejects non-canonical and off-curve points`() {
        // y >= q.
        assertTrue(Ed25519Spake.decodePoint(hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")) == null)
        // Random bytes that don't land on the curve (very likely).
        assertTrue(Ed25519Spake.decodePoint(ByteArray(32) { 0x42 }) == null)
        // Base point decodes.
        assertTrue(Ed25519Spake.decodePoint(hex("5866666666666666666666666666666666666666666666666666666666666666")) != null)
    }

    @Test
    fun `spake2 alice and bob derive the same key`() {
        // BoringSSL oracle inputs (password = "592781" + 64-byte exporter).
        val password = hex(
            "353932373831E63DD959651C211600F3B6561D0B9D90AF09D0A4A453EE2059A480" +
                "CC7C5A94D4D48933F9FFF5FE43317D52FA7BFF8F8BC4F3488B8007330FEC7C7EDC91C20E5D"
        )
        val alicePriv = hex(
            "47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f980" +
                "a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3"
        )
        val bobPriv = hex(
            "a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5f" +
                "d7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a"
        )
        val alice = Spake2Exchange(true, "adb pair client\u0000".toByteArray(StandardCharsets.UTF_8), "adb pair server\u0000".toByteArray(StandardCharsets.UTF_8))
        val bob = Spake2Exchange(false, "adb pair server\u0000".toByteArray(StandardCharsets.UTF_8), "adb pair client\u0000".toByteArray(StandardCharsets.UTF_8))
        val aliceMsg = alice.generateMessage(password, alicePriv)
        val bobMsg = bob.generateMessage(password, bobPriv)
        assertEquals(32, aliceMsg.size)
        assertEquals(32, bobMsg.size)
        val aliceKey = alice.processMessage(bobMsg)
        val bobKey = bob.processMessage(aliceMsg)
        assertEquals(64, aliceKey.size)
        assertArrayEquals(aliceKey, bobKey)
        println("ALICE_MSG: ${hex(aliceMsg)}")
        println("BOB_MSG:   ${hex(bobMsg)}")
        println("SHARED_KEY: ${hex(aliceKey)}")
    }

    @Test
    fun `spake2 wrong password gives different key`() {
        val password = hex("353932373831") + ByteArray(64) { 0x11 }
        val wrong = hex("353932373832") + ByteArray(64) { 0x11 }
        fun exchange(isAlice: Boolean, pw: ByteArray, priv: ByteArray): Pair<ByteArray, Spake2Exchange> {
            val e = Spake2Exchange(isAlice,
                (if (isAlice) "adb pair client\u0000" else "adb pair server\u0000").toByteArray(StandardCharsets.UTF_8),
                (if (isAlice) "adb pair server\u0000" else "adb pair client\u0000").toByteArray(StandardCharsets.UTF_8))
            return Pair(e.generateMessage(pw, priv), e)
        }
        val privA = ByteArray(64) { 0x21 }
        val privB = ByteArray(64) { 0x22 }
        val (aMsg, aEx) = exchange(true, password, privA)
        val (bMsg, bEx) = exchange(false, wrong, privB)
        val aKey = aEx.processMessage(bMsg)
        val bKey = bEx.processMessage(aMsg)
        assertFalse(aKey.contentEquals(bKey))
    }

    @Test
    fun `spake2 corrupted message does not yield a matching key`() {
        val password = hex("353932373831") + ByteArray(64) { 0x33 }
        fun exchange(isAlice: Boolean, priv: ByteArray): Pair<ByteArray, Spake2Exchange> {
            val e = Spake2Exchange(isAlice,
                (if (isAlice) "adb pair client\u0000" else "adb pair server\u0000").toByteArray(StandardCharsets.UTF_8),
                (if (isAlice) "adb pair server\u0000" else "adb pair client\u0000").toByteArray(StandardCharsets.UTF_8))
            return Pair(e.generateMessage(password, priv), e)
        }
        for (bit in 0 until 8) {
            val (aMsg0, aEx) = exchange(true, ByteArray(64) { 0x44 })
            val (bMsg, bEx) = exchange(false, ByteArray(64) { 0x45 })
            val aMsg = aMsg0.copyOf()
            aMsg[0] = (aMsg[0].toInt() xor (1 shl bit)).toByte()
            val aKey = aEx.processMessage(bMsg)
            val bKey = try {
                bEx.processMessage(aMsg)
            } catch (_: IllegalArgumentException) {
                null
            }
            assertFalse("corrupted message produced a matching key",
                bKey != null && aKey.contentEquals(bKey))
        }
    }
}

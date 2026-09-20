package com.fourgeailabs.bpwatch.mobile.adb.spake2

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal twisted-Edwards25519 arithmetic for SPAKE2, implemented from
 * scratch on BigInteger.
 *
 * We vendor a SPAKE2 implementation rather than writing our own because the
 * protocol must interoperate with BoringSSL's adbd — but the vendored
 * ed25519 scalar multiplication turned out to be broken (1*G != G, and the
 * upstream repo fails its own key-agreement test). Rather than debugging
 * someone else's field arithmetic, this file implements the small subset of
 * Ed25519 that SPAKE2 needs, directly from RFC 8032, with test vectors.
 *
 * Only used for the ADB pairing handshake. Not constant-time; the scalars
 * here are ephemeral pairing secrets, and adbd itself is not constant-time
 * in this path either.
 */
internal object Ed25519Spake {

    val Q: BigInteger = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    val L: BigInteger = BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)

    /** d = -121665/121666 mod q */
    private val D: BigInteger =
        BigInteger("-121665").multiply(BigInteger("121666").modInverse(Q)).mod(Q)

    /** Base point B (RFC 8032). */
    val B: Pair<BigInteger, BigInteger> = Pair(
        BigInteger("216936d3cd6e53fec0a4e231fdd6dc5c692cc7609525a7b2c9562d608f25d51a", 16),
        BigInteger("6666666666666666666666666666666666666666666666666666666666666658", 16),
    )

    /**
     * Fixed SPAKE2 points from BoringSSL's spake25519.c. These are the exact
     * points adbd uses; the "encoded" values are baked into BoringSSL source.
     */
    val M: Pair<BigInteger, BigInteger> by lazy {
        decodePoint(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
            ?: throw IllegalStateException("M does not decode")
    }
    val N: Pair<BigInteger, BigInteger> by lazy {
        decodePoint(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
            ?: throw IllegalStateException("N does not decode")
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Little-endian bytes -> BigInteger. */
    private fun fromLe(bytes: ByteArray): BigInteger {
        return BigInteger(1, bytes.reversedArray())
    }

    /** BigInteger -> 32 little-endian bytes. */
    private fun toLe(v: BigInteger, size: Int = 32): ByteArray {
        val be = v.toByteArray().let {
            // Strip leading sign byte.
            if (it.size > size && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        require(be.size <= size) { "value too large" }
        val le = ByteArray(size)
        for (i in be.indices) le[i] = be[be.size - 1 - i]
        return le
    }

    /**
     * Decode a 32-byte compressed Edwards point. Returns (x, y) or null if the
     * bytes do not encode a valid curve point.
     *
     * Ed25519 is the twisted Edwards curve -x^2 + y^2 = 1 + d*x^2*y^2, so
     * x^2 = (y^2 - 1)/(d*y^2 + 1).
     */
    fun decodePoint(enc: ByteArray): Pair<BigInteger, BigInteger>? {
        if (enc.size != 32) return null
        val signBit = (enc[31].toInt() ushr 7) and 1
        val y = fromLe(enc.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() })
        if (y >= Q) return null
        val y2 = y.multiply(y).mod(Q)
        val num = y2.subtract(BigInteger.ONE).mod(Q)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(Q)
        val denInv = try {
            den.modInverse(Q)
        } catch (_: ArithmeticException) {
            return null
        }
        var x = modSqrt(num.multiply(denInv).mod(Q)) ?: return null
        if ((if (x.testBit(0)) 1 else 0) != signBit) {
            x = Q.subtract(x)
        }
        // Verify: -x^2 + y^2 = 1 + d*x^2*y^2.
        val x2 = x.multiply(x).mod(Q)
        val lhs = y2.subtract(x2).mod(Q)
        val rhs = BigInteger.ONE.add(D.multiply(x2).mod(Q).multiply(y2)).mod(Q)
        if (lhs != rhs) return null
        return Pair(x, y)
    }

    /** Encode (x, y) as 32 compressed bytes. */
    fun encodePoint(p: Pair<BigInteger, BigInteger>): ByteArray {
        val (x, y) = p
        val enc = toLe(y)
        if (x.testBit(0)) {
            enc[31] = (enc[31].toInt() or 0x80).toByte()
        }
        return enc
    }

    /**
     * Twisted Edwards point addition (a = -1):
     * x3 = (x1*y2 + x2*y1)/(1 + d*x1*x2*y1*y2)
     * y3 = (y1*y2 + x1*x2)/(1 - d*x1*x2*y1*y2)
     */
    fun pointAdd(p1: Pair<BigInteger, BigInteger>, p2: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        val (x1, y1) = p1
        val (x2, y2) = p2
        val x1x2 = x1.multiply(x2).mod(Q)
        val y1y2 = y1.multiply(y2).mod(Q)
        val dxy = D.multiply(x1x2).mod(Q).multiply(y1y2).mod(Q)
        val x3 = x1.multiply(y2).add(x2.multiply(y1)).mod(Q)
            .multiply(BigInteger.ONE.add(dxy).modInverse(Q)).mod(Q)
        val y3 = y1y2.add(x1x2).mod(Q)
            .multiply(BigInteger.ONE.subtract(dxy).modInverse(Q)).mod(Q)
        return Pair(x3, y3)
    }

    /** Point negation: -(x, y) = (-x, y). */
    fun pointNeg(p: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        return Pair(Q.subtract(p.first).mod(Q), p.second)
    }

    /** Scalar multiplication by double-and-add. Scalar is reduced mod L. */
    fun scalarMult(scalar: BigInteger, point: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        var s = scalar.mod(L)
        var result: Pair<BigInteger, BigInteger>? = null // identity
        var addend = point
        while (s > BigInteger.ZERO) {
            if (s.testBit(0)) {
                result = if (result == null) addend else pointAdd(result, addend)
            }
            addend = pointAdd(addend, addend)
            s = s.shiftRight(1)
        }
        // Identity element (0, 1).
        return result ?: Pair(BigInteger.ZERO, BigInteger.ONE)
    }

    /** Reduce 64 little-endian bytes mod L (Ed25519 scalar reduction). */
    fun reduceToScalar(le: ByteArray): BigInteger {
        require(le.size == 64) { "expected 64 bytes" }
        return fromLe(le).mod(L)
    }

    /**
     * BoringSSL's SPAKE2 password scalar: w = SHA-512(password), reduced mod L,
     * then adjusted so the low 3 bits are clear (the "password scalar hack" —
     * adds l, 2l, 4l based on the low bits, so w is a multiple of 8 and the
     * cofactor is cleared in the DH computation).
     */
    fun passwordScalar(password: ByteArray): BigInteger {
        val h = MessageDigest.getInstance("SHA-512").digest(password)
        var w = fromLe(h).mod(L)
        // Clear the low 3 bits by adding multiples of l (l is odd, so adding l
        // flips the LSB; adding 2l flips the next bit; adding 4l the next).
        if (w.testBit(0)) w = w.add(L)
        if (w.testBit(1)) w = w.add(L.shiftLeft(1))
        if (w.testBit(2)) w = w.add(L.shiftLeft(2))
        check(!w.testBit(0) && !w.testBit(1) && !w.testBit(2)) { "password scalar hack failed" }
        return w
    }

    /** Clamp an ephemeral private scalar: reduce mod L, clear low 3 bits. */
    fun clampScalar(bytes: ByteArray): BigInteger {
        require(bytes.size == 32) { "expected 32 bytes" }
        var s = fromLe(bytes).mod(L)
        // Clear low 3 bits (multiply by cofactor 8).
        s = s.shiftRight(3).shiftLeft(3)
        return s
    }

    fun randomScalar(): BigInteger {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return reduceToScalar(bytes)
    }

    /**
     * Square root mod q (q = 5 mod 8). Returns null if n is not a quadratic
     * residue. Uses x = n^((q+3)/8), with a sqrt(-1) correction when needed.
     */
    private fun modSqrt(n: BigInteger): BigInteger? {
        if (n == BigInteger.ZERO) return BigInteger.ZERO
        val exp = Q.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
        var x = n.modPow(exp, Q)
        if (x.multiply(x).mod(Q) != n.mod(Q)) {
            // Multiply by sqrt(-1) = 2^((q-1)/4).
            val sqrtM1 = BigInteger.TWO.modPow(
                Q.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), Q)
            x = x.multiply(sqrtM1).mod(Q)
            if (x.multiply(x).mod(Q) != n.mod(Q)) return null
        }
        return x
    }
}

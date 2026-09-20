package com.fourgeailabs.bpwatch.mobile.adb

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ADB transport protocol framing (AOSP adb/protocol.txt).
 *
 * Every message is a 24-byte little-endian header followed by an optional payload:
 *   command, arg0, arg1, data_length, data_check, magic
 * where data_check is the unsigned sum of the payload bytes and
 * magic is command XOR 0xFFFFFFFF.
 *
 * Pure java.* — no Android APIs, so this runs on the JVM for unit tests too.
 */
internal object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    /**
     * "STLS": modern wireless debugging asks to upgrade the connection to
     * TLS 1.3 right after the cleartext CNXN. Sent by the daemon, echoed
     * back by the client (empty payload), then both sides handshake TLS on
     * the same socket.
     */
    const val A_STLS = 0x534C5453

    /**
     * Samsung's equivalent of STLS, seen on the Galaxy Watch Ultra
     * (ASCII "SLES"). Same upgrade dance, echo the same command back.
     */
    const val A_SLES = 0x53454C53

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** Protocol version we claim. */
    const val VERSION = 0x01000000

    /** Max payload bytes we advertise and will send per message. Classic safe value. */
    const val MAXDATA = 4096

    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    fun encode(msg: Message): ByteArray {
        val buf = ByteBuffer.allocate(24 + msg.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(msg.command)
        buf.putInt(msg.arg0)
        buf.putInt(msg.arg1)
        buf.putInt(msg.payload.size)
        buf.putInt(checksum(msg.payload))
        buf.putInt(msg.command xor 0xFFFFFFFF.toInt())
        buf.put(msg.payload)
        return buf.array()
    }

    @Throws(EOFException::class)
    fun decode(input: InputStream): Message {
        val header = readFully(input, 24)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        val check = buf.int
        val magic = buf.int
        if (magic != (command xor 0xFFFFFFFF.toInt())) {
            throw IllegalStateException("ADB magic mismatch (command=${command.toString(16)})")
        }
        if (length < 0 || length > 1024 * 1024) {
            throw IllegalStateException("ADB absurd payload length: $length")
        }
        val payload = if (length > 0) readFully(input, length) else ByteArray(0)
        if (checksum(payload) != check) {
            throw IllegalStateException("ADB checksum mismatch")
        }
        return Message(command, arg0, arg1, payload)
    }

    private fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return sum
    }

    /** Read exactly [n] bytes or throw EOFException. */
    internal fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("ADB stream closed mid-message")
            off += r
        }
        return out
    }

    fun commandName(cmd: Int): String = when (cmd) {
        A_SYNC -> "SYNC"
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_STLS -> "STLS"
        A_SLES -> "SLES"
        else -> "0x${cmd.toString(16)}"
    }
}

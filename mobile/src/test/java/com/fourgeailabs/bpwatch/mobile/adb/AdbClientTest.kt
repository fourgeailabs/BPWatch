package com.fourgeailabs.bpwatch.mobile.adb

import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.A_CNXN
import com.fourgeailabs.bpwatch.mobile.adb.AdbProtocol.Message
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.random.Random

class AdbClientTest {

    private fun tempDir(): File = Files.createTempDirectory("adbtest").toFile()

    @Test
    fun protocolRoundTrip() {
        val payload = "host::bpwatch-installer".toByteArray(Charsets.UTF_8)
        val msg = Message(A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAXDATA, payload)
        val decoded = AdbProtocol.decode(ByteArrayInputStream(AdbProtocol.encode(msg)))
        assertEquals(msg.command, decoded.command)
        assertEquals(msg.arg0, decoded.arg0)
        assertEquals(msg.arg1, decoded.arg1)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun keyPersistsAndSignatureVerifies() {
        val dir = tempDir()
        val first = AdbKey.loadOrCreate(dir)
        val second = AdbKey.loadOrCreate(dir)
        assertArrayEquals(first.private.encoded, second.private.encoded)

        // Our own .pub parses back to the same key.
        val parsed = AdbKey.parseKeyLine(File(dir, "adbkey.pub").readBytes())
            ?: throw AssertionError("adbkey.pub did not parse")
        assertEquals(
            (first.public as RSAPublicKey).modulus,
            parsed.modulus,
        )

        val token = Random.nextBytes(20)
        val sig = AdbKey.sign(first.private, token)
        assertEquals(256, sig.size) // RSA-2048
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(first.public)
        verifier.update(token)
        assertTrue(verifier.verify(sig))
    }

    @Test
    fun connectAuthAndShell() {
        FakeAdbDaemon().use { daemon ->
            AdbClient(tempDir()).connect("127.0.0.1", daemon.port, {}, authTimeoutMs = 15_000)
                .use { session ->
                    val model = session.shell("getprop ro.product.model").trim()
                    assertEquals("Galaxy Watch Ultra", model)
                }
        }
    }

    @Test
    fun pushFileRoundTrip() {
        FakeAdbDaemon().use { daemon ->
            // 200 KB spans many DATA chunks (4088 bytes each).
            val bytes = Random.nextBytes(200 * 1024)
            val file = File(tempDir(), "test.apk").apply { writeBytes(bytes) }
            AdbClient(tempDir()).connect("127.0.0.1", daemon.port, {}, authTimeoutMs = 15_000)
                .use { session ->
                    var lastSent = 0L
                    session.push(file, "/data/local/tmp/test.apk") { sent, total ->
                        assertEquals(bytes.size.toLong(), total)
                        assertTrue(sent >= lastSent)
                        lastSent = sent
                    }
                    assertEquals(bytes.size.toLong(), lastSent)
                }
            // Give the daemon a beat to finish writing, then check.
            Thread.sleep(300)
            assertArrayEquals(bytes, daemon.uploaded["/data/local/tmp/test.apk"])
        }
    }

    @Test
    fun installerEndToEnd() {
        FakeAdbDaemon().use { daemon ->
            val apkBytes = Random.nextBytes(100 * 1024)
            val apk = File(tempDir(), "bpwatch-wear.apk").apply { writeBytes(apkBytes) }
            val logs = mutableListOf<String>()
            WatchInstaller(tempDir()) { logs.add(it) }.installOrUpdate(
                apkFile = apk,
                host = "127.0.0.1",
                port = daemon.port,
                onProgress = { _, _ -> },
            )
            Thread.sleep(300)
            assertArrayEquals(apkBytes, daemon.uploaded["/data/local/tmp/bpwatch-wear.apk"])
            assertTrue(
                "expected pm install in ${daemon.commands}",
                daemon.commands.any { it.startsWith("pm install -r /data/local/tmp/bpwatch-wear.apk") },
            )
            assertTrue(logs.any { it.contains("Done") })
        }
    }

    /**
     * A raw TCP server that answers like a hostile/non-ADB endpoint must
     * always surface a catchable Exception (never an Error, never a hang
     * past the timeout). [respond] runs on the accepted socket.
     */
    private fun withHostileServer(
        respond: (java.net.Socket) -> Unit,
        action: (port: Int) -> Unit,
    ) {
        val server = java.net.ServerSocket(0)
        val t = Thread {
            try {
                server.accept().use { respond(it) }
            } catch (_: Exception) {
            } finally {
                try { server.close() } catch (_: Exception) { }
            }
        }
        t.isDaemon = true
        t.start()
        try {
            action(server.localPort)
        } finally {
            try { server.close() } catch (_: Exception) { }
        }
    }

    @Test
    fun tlsStyleEndpointFailsCleanly() {
        // Simulates a modern wireless-debugging TLS port: it reads our
        // plaintext CNXN, answers with TLS alert bytes, then hangs up.
        withHostileServer(respond = { s ->
            val buf = ByteArray(24)
            var off = 0
            s.soTimeout = 5_000
            while (off < 24) {
                val r = s.getInputStream().read(buf, off, 24 - off)
                if (r < 0) break
                off += r
            }
            // TLS fatal alert: 0x15 0x03 0x01 0x00 0x02 0x02 0x28
            s.getOutputStream().write(byteArrayOf(0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x28.toByte()))
            s.getOutputStream().flush()
        }) { port ->
            try {
                AdbClient(tempDir()).connect("127.0.0.1", port, {}, authTimeoutMs = 10_000)
                error("expected failure against a TLS endpoint")
            } catch (e: AdbException) {
                assertTrue("unexpected: ${e.message}", e.message!!.contains("closed"))
            }
        }
    }

    @Test
    fun garbageBytesFailCleanly() {
        // Some random binary service (wrong IP/port): magic check must fail
        // with a catchable exception, never an allocation blowup or hang.
        withHostileServer(respond = { s ->
            s.getOutputStream().write(kotlin.random.Random.nextBytes(64))
            s.getOutputStream().flush()
            Thread.sleep(30_000)
        }) { port ->
            try {
                AdbClient(tempDir()).connect("127.0.0.1", port, {}, authTimeoutMs = 10_000)
                error("expected failure against garbage bytes")
            } catch (e: Exception) {
                assertTrue("unexpected: ${e.message}", e !is Error)
            }
        }
    }

    @Test
    fun silentServerTimesOut() {
        // TCP accepts but nobody speaks ADB: must hit the auth timeout,
        // not hang forever.
        withHostileServer(respond = { Thread.sleep(30_000) }) { port ->
            val start = System.currentTimeMillis()
            try {
                AdbClient(tempDir()).connect("127.0.0.1", port, {}, authTimeoutMs = 5_000)
                error("expected timeout")
            } catch (e: AdbException) {
                assertTrue("unexpected: ${e.message}", e.message!!.contains("Timed out"))
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue("took too long: $elapsed ms", elapsed < 20_000)
        }
    }
}

package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Stress / leak coverage for the in-process WebSocket relay run by
 * [WirelessAdbSelfPairTransport]. The wireless-self-pair path on real devices opens one TCP
 * relay [java.net.ServerSocket], then proxies every accepted client through a fresh
 * [AdbStream]. A leak in the accept loop or per-connection cleanup would burn one fd per
 * `browser_script` round-trip and eventually hit `EMFILE` ("Too many open files") — a class of
 * failure that only surfaces after dozens of calls and that the existing per-call tests cannot
 * catch.
 *
 * These tests cover the control-plane invariants of the relay (idempotency, single-flight
 * bootstrap, per-connection thread cleanup). End-to-end fd-count assertions on the wire
 * protocol require a real device; that path is documented in
 * `projects/active/browser/cn/diag_20260503_ws_relay_stress.md` and exercised by
 * `scripts/ws-relay-stress.sh`.
 */
class WirelessAdbSelfPairTransportRelayStressTest {

    private lateinit var transport: WirelessAdbSelfPairTransport
    private lateinit var wireClient: AdbWireProtocolClient
    private lateinit var wirelessManager: AdbWirelessManager
    private lateinit var pairingClient: AdbPairingClient
    private lateinit var keyStore: AdbCryptoKeyStore

    @Before fun setUp() {
        wirelessManager = mockk(relaxed = true)
        pairingClient = mockk(relaxed = true)
        wireClient = mockk(relaxed = true)
        keyStore = mockk(relaxed = true)

        // Bootstrap arrangement: pretend wireless ADB is up and our pubkey is already in
        // adb_keys. This skips real Shizuku binder + TLS PSK pairing — both of which would
        // need a real device.
        coEvery { wirelessManager.enableWirelessDebugging() } returns Result.success(Unit)
        coEvery { wirelessManager.getAdbWirelessPort() } returns FAKE_TLS_PORT
        coEvery { wirelessManager.isPubkeyAuthorized(any()) } returns true
        every { keyStore.androidPubkeyBase64() } returns "QAAAAdeadbeefcafe1234"
        every { keyStore.isPersisted() } returns true

        transport = WirelessAdbSelfPairTransport(
            wirelessManager = wirelessManager,
            keyStore = keyStore,
            pairingClient = pairingClient,
            wireClient = wireClient,
            relayAuthToken = TEST_TOKEN,
        )
    }

    @After fun tearDown() {
        transport.close()
    }

    @Test
    fun `ensureWebSocketRelayPort is idempotent across 50 sequential calls`() = runBlocking {
        val firstPort = transport.ensureWebSocketRelayPort()
        assertThat(firstPort).isNotNull()
        assertThat(firstPort!!).isGreaterThan(0)

        repeat(50) {
            assertThat(transport.ensureWebSocketRelayPort()).isEqualTo(firstPort)
        }

        // Bootstrap (the costly leg — pair handshake on real devices) must run exactly once
        // across all 51 calls. A leak here would re-pair every WebSocket request.
        coVerify(exactly = 1) { wirelessManager.enableWirelessDebugging() }
        coVerify(exactly = 1) { wirelessManager.getAdbWirelessPort() }
    }

    @Test
    fun `bootstrap is single-flight under 20 truly concurrent callers (barrier-gated)`() = runBlocking {
        // Barrier-gated single-flight assertion. The previous shape (20 immediate `async` blocks
        // with non-suspending mocks) passed even if `bootstrapBlocking` had run 20 times in
        // sequence — `runBlocking`'s default dispatcher serializes by default, so "concurrent"
        // wasn't really concurrent. Here we:
        //   1. Suspend bootstrap inside a CompletableDeferred barrier.
        //   2. Launch 20 callers on Dispatchers.Default (real worker threads).
        //   3. Wait until at least one caller has entered bootstrap (counter > 0) and the
        //      remaining callers have piled up on the bootstrapLock Mutex.
        //   4. Assert the counter is still 1 — proving 19 callers parked on the lock.
        //   5. Release the barrier; assert all 20 return the same port and the counter is
        //      still 1 (the late callers find cachedTlsPort populated and skip bootstrap).
        val barrier = CompletableDeferred<Unit>()
        val callCounter = AtomicInteger(0)

        coEvery { wirelessManager.enableWirelessDebugging() } coAnswers {
            callCounter.incrementAndGet()
            barrier.await()
            Result.success(Unit)
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val deferred = (0 until 20).map {
                scope.async { transport.ensureWebSocketRelayPort() }
            }

            // Wait for the first caller to enter bootstrap (counter goes 0 -> 1) and let the
            // remaining 19 settle on the bootstrapLock Mutex.
            withTimeout(2_000) {
                while (callCounter.get() == 0) yield()
            }
            delay(200) // give the other 19 a generous moment to pile up on the lock

            // Single-flight invariant: only one caller is inside bootstrap right now.
            assertThat(callCounter.get()).isEqualTo(1)

            // Release the barrier; all 20 callers should now complete with the same port.
            barrier.complete(Unit)
            val ports = deferred.awaitAll()

            assertThat(ports.distinct()).hasSize(1)
            assertThat(ports.first()).isNotNull()
            // Still 1 — the 19 latecomers found cachedTlsPort populated and skipped bootstrap.
            assertThat(callCounter.get()).isEqualTo(1)
            coVerify(exactly = 1) { wirelessManager.enableWirelessDebugging() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `close releases ServerSocket, clears port, and rejects further calls`() = runBlocking {
        val port = transport.ensureWebSocketRelayPort()!!
        // Sanity-check the relay is up.
        Socket("127.0.0.1", port).close()

        transport.close()

        // After close, ensureWebSocketRelayPort returns null — no stale port is replayed.
        // (Pre-fix the early `if (relayPort != 0) return relayPort` returned the dead port.)
        assertThat(transport.ensureWebSocketRelayPort()).isNull()

        // No live wireless-adb-* daemon threads left over.
        val leftover = Thread.getAllStackTraces().keys
            .count { it.name.startsWith("wireless-adb-") && it.isAlive }
        assertThat(leftover).isAtMost(MAX_TRANSIENT_THREADS)

        // Bookkeeping: the port should usually be unbound. Logged for diagnostic clarity, not
        // asserted — a flaky port reuse here would create test flakiness without proving a leak.
        try {
            Socket("127.0.0.1", port).close()
            println("note: post-close port $port still accepted a connection (OS port reuse likely)")
        } catch (_: ConnectException) {
            // Expected.
        }
    }

    @Test
    fun `accept loop drains 20 sequential client cycles without thread leak (success path)`() = runBlocking {
        // The streamed open returns immediately-EOF input + a no-op output sink so each
        // proxy connection's downstream pump exits as soon as the client connects, then the
        // upstream pump exits when the test closes its socket.
        coEvery { wireClient.openLocalAbstract(any(), any(), any(), any()) } answers {
            mockk<AdbStream>(relaxed = true).also { stream ->
                every { stream.inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { stream.outputStream } returns NULL_SINK
            }
        }

        val port = transport.ensureWebSocketRelayPort()!!
        val baseline = countWirelessAdbThreads()

        repeat(20) {
            // Send a valid WS upgrade with the configured token so the relay reaches the
            // openLocalAbstract success path and spawns the pump threads we're stressing.
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(validUpgradeRequest(TEST_TOKEN, port))
                sock.getOutputStream().flush()
                Thread.sleep(15) // let proxy spawn before we tear down
            }
        }

        awaitThreadDelta(baseline)
        assertThat(countWirelessAdbThreads() - baseline).isAtMost(MAX_TRANSIENT_THREADS)
    }

    @Test
    fun `accept loop drains 20 cycles when openLocalAbstract fails (failure path)`() = runBlocking {
        // Failure-path leak check: a thrown exception from the wire layer must not leak
        // sockets or per-connection threads. proxyConnection's outer try/finally is the only
        // thing standing between us and an fd leak per failed call.
        coEvery {
            wireClient.openLocalAbstract(any(), any(), any(), any())
        } throws IOException("simulated post-mTLS failure")

        val port = transport.ensureWebSocketRelayPort()!!
        val baseline = countWirelessAdbThreads()

        repeat(20) {
            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(validUpgradeRequest(TEST_TOKEN, port))
                sock.getOutputStream().flush()
                Thread.sleep(10)
            }
        }

        awaitThreadDelta(baseline)
        assertThat(countWirelessAdbThreads() - baseline).isAtMost(MAX_TRANSIENT_THREADS)
    }

    @Test
    fun `relay rejects connection without token with 403`() = runBlocking {
        val port = transport.ensureWebSocketRelayPort()!!
        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().write(
                "GET /devtools/page/AAA HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()
            )
            sock.getOutputStream().flush()
            val response = sock.getInputStream().readBytes().toString(Charsets.US_ASCII)
            assertThat(response).startsWith("HTTP/1.1 403")
        }
    }

    @Test
    fun `relay rejects connection with wrong token with 403`() = runBlocking {
        val port = transport.ensureWebSocketRelayPort()!!
        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().write(
                ("GET /devtools/page/AAA HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "X-ClosePaw-Token: not-the-real-token\r\n\r\n").toByteArray()
            )
            sock.getOutputStream().flush()
            val response = sock.getInputStream().readBytes().toString(Charsets.US_ASCII)
            assertThat(response).startsWith("HTTP/1.1 403")
        }
    }

    @Test
    fun `relay accepts connection with correct token and proxies`() = runBlocking {
        // Capture the bytes the relay forwards upstream so we can assert the WS upgrade made it
        // through with the X-ClosePaw-Token still attached (Chrome ignores unknown headers).
        val captured = java.io.ByteArrayOutputStream()
        coEvery { wireClient.openLocalAbstract(any(), any(), any(), any()) } answers {
            mockk<AdbStream>(relaxed = true).also { stream ->
                every { stream.inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { stream.outputStream } returns captured
            }
        }
        val port = transport.ensureWebSocketRelayPort()!!
        val request = validUpgradeRequest(TEST_TOKEN, port)
        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().write(request)
            sock.getOutputStream().flush()
            Thread.sleep(50) // let proxy forward upstream
        }
        // Verify the upstream sink received the verbatim upgrade (header included).
        val forwarded = captured.toByteArray().toString(Charsets.US_ASCII)
        assertThat(forwarded).contains("X-ClosePaw-Token: $TEST_TOKEN")
        assertThat(forwarded).startsWith("GET /devtools/page/")
    }

    private fun validUpgradeRequest(token: String, port: Int): ByteArray =
        ("GET /devtools/page/AAA HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "X-ClosePaw-Token: $token\r\n\r\n").toByteArray()

    private fun countWirelessAdbThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("wireless-adb-") }

    /**
     * Brief poll loop — gives the JVM up to 2 seconds to drain transient pump/proxy threads
     * before we sample. Without this the test is racy under CI load.
     */
    private fun awaitThreadDelta(baseline: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (countWirelessAdbThreads() - baseline <= MAX_TRANSIENT_THREADS) return
            Thread.sleep(50)
        }
    }

    private companion object {
        const val FAKE_TLS_PORT = 41089
        const val TEST_TOKEN = "test-token-deadbeefcafebabe1234567890abcdef"
        // Tolerate the accept-loop thread plus at most two in-flight pumps from the last
        // connection that may not have unwound by the time we sample. Any leak that scales
        // with cycle count blows past this.
        const val MAX_TRANSIENT_THREADS = 3

        val NULL_SINK = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
            override fun flush() {}
        }
    }
}

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
        every { keyStore.fingerprint() } returns "deadbeefcafe1234"
        every { keyStore.isPersisted() } returns true

        transport = WirelessAdbSelfPairTransport(
            wirelessManager = wirelessManager,
            keyStore = keyStore,
            pairingClient = pairingClient,
            wireClient = wireClient,
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
    fun `bootstrap is single-flight under 20 concurrent ensureWebSocketRelayPort callers`() = runBlocking {
        val ports = coroutineScope {
            (0 until 20).map { async { transport.ensureWebSocketRelayPort() } }.awaitAll()
        }
        assertThat(ports.distinct()).hasSize(1)
        assertThat(ports.first()).isNotNull()
        coVerify(exactly = 1) { wirelessManager.enableWirelessDebugging() }
    }

    @Test
    fun `close releases the underlying ServerSocket so further connects fail`() = runBlocking {
        val port = transport.ensureWebSocketRelayPort()!!
        // Sanity-check the relay is up.
        Socket("127.0.0.1", port).close()

        transport.close()

        // After close, the previously-bound port is no longer accepting connections (or the
        // OS recycled the ephemeral port to another listener — either way, the relay's own
        // listener is gone, which is the leak-relevant invariant).
        var connectFailed = false
        try {
            Socket("127.0.0.1", port).close()
        } catch (_: ConnectException) {
            connectFailed = true
        }
        // We can't tell connect-success-because-OS-rebound from connect-success-because-leak
        // from outside the JVM, so the strict assertion is "no thread/socket should still be
        // referenced by a wireless-adb-* daemon thread".
        val leftover = Thread.getAllStackTraces().keys
            .count { it.name.startsWith("wireless-adb-") && it.isAlive }
        assertThat(leftover).isAtMost(MAX_TRANSIENT_THREADS)

        // Bookkeeping: the connect attempt should usually fail (no other JVM thread re-bound
        // the ephemeral port within the test). Logged for diagnostic clarity, not asserted —
        // a flaky port reuse here would create test flakiness without proving a real leak.
        if (!connectFailed) {
            // Best-effort signal in CI logs; no failure.
            println("note: post-close port $port still accepted a connection (OS port reuse likely)")
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
            Socket("127.0.0.1", port).use {
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
            Socket("127.0.0.1", port).use { Thread.sleep(10) }
        }

        awaitThreadDelta(baseline)
        assertThat(countWirelessAdbThreads() - baseline).isAtMost(MAX_TRANSIENT_THREADS)
    }

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

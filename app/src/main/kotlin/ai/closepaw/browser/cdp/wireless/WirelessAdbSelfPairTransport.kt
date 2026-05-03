package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.shizuku.DevtoolsSocketTransport
import ai.closepaw.browser.cdp.shizuku.ShizukuChromeDevtoolsBridge
import ai.closepaw.browser.cdp.shizuku.TransportLabel
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wireless-ADB self-pair transport: drives the entire spike-proven path
 *   Shizuku → IAdbManager.allowWirelessDebugging → enablePairingByQrCode → embedded TLS-PSK pair
 *   → embedded mTLS adb client → A_OPEN(localabstract:chrome_devtools_remote) → CDP bytes.
 *
 * Lazy: nothing happens on construction. The first call to [exchange] (or [ensureWebSocketRelayPort]
 * for WebSocket) drives the bootstrap. The TLS adb port is cached; the RSA keypair is persisted
 * via [AdbCryptoKeyStore]'s sentinel pattern, so subsequent runs skip pairing entirely once the
 * device's `/data/misc/adb/adb_keys` already contains our pubkey.
 *
 * The TCP relay (for WebSocket) runs in this app process — no Shizuku boundary on the data path.
 * Once paired, the app UID can talk to localhost:tlsAdbPort, do mTLS with our cert, and let adbd
 * (which holds mlstrustedsubject) connect to chrome_devtools_remote on our behalf.
 */
class WirelessAdbSelfPairTransport(
    private val wirelessManager: AdbWirelessManager,
    private val keyStore: AdbCryptoKeyStore,
    private val pairingClient: AdbPairingClient,
    private val wireClient: AdbWireProtocolClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DevtoolsSocketTransport, WirelessAdbRelayHost, AutoCloseable {

    override val label: TransportLabel = TransportLabel.WIRELESS_ADB_SELF_PAIR

    private val bootstrapLock = Mutex()
    @Volatile private var cachedTlsPort: Int = -1

    private val relayLock = Any()
    private var relayServer: ServerSocket? = null
    @Volatile private var relayPort: Int = 0
    private val relayStopped = AtomicBoolean(false)

    /** Cheap probe used by preflight: don't tear up TLS, just confirm Shizuku binder is reachable. */
    override suspend fun isReachable(): Boolean = runCatching {
        runInterruptible(ioDispatcher) {
            // bssid query is a fast `dumpsys wifi` shell-out; failure here means no Wi-Fi or
            // shell-uid binder is missing — both terminal for this transport.
        }
        wirelessManager.currentBssid() != null
    }.getOrDefault(false)

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        return try {
            val tlsPort = ensureBootstrapped()
            try {
                wireClient.exchange(
                    host = LOCALHOST,
                    tlsPort = tlsPort,
                    destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                    request = request,
                    timeoutMs = timeoutMs,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "wireless-adb exchange failed; invalidating cached port and retrying once", t)
                cachedTlsPort = -1
                val fresh = ensureBootstrapped()
                wireClient.exchange(
                    host = LOCALHOST,
                    tlsPort = fresh,
                    destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                    request = request,
                    timeoutMs = timeoutMs,
                )
            }
        } catch (t: Throwable) {
            // The bridge wraps us in runCatching but does not log; surface the actual cause
            // here so logcat carries the diagnosis. Re-throw so the cascade falls through.
            Log.e(TAG, "wireless-adb transport failed: ${t.javaClass.simpleName}: ${t.message}", t)
            throw t
        }
    }

    override suspend fun ensureWebSocketRelayPort(): Int? {
        // WebSocket relay needs the bootstrapped tunnel; if bootstrap hasn't happened yet
        // (caller invoked WS resolution before any HTTP exchange), do it now.
        ensureBootstrapped()
        synchronized(relayLock) {
            if (relayPort != 0) return relayPort
            if (relayStopped.get()) return null
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(LOCALHOST), 0), 16)
            relayServer = server
            relayPort = server.localPort
            Thread({ relayAcceptLoop(server) }, "wireless-adb-relay-accept").apply {
                isDaemon = true
            }.start()
            Log.i(TAG, "wireless-adb WS relay on 127.0.0.1:$relayPort")
            return relayPort
        }
    }

    private suspend fun ensureBootstrapped(): Int {
        cachedTlsPort.takeIf { it > 0 }?.let { return it }
        return bootstrapLock.withLock {
            cachedTlsPort.takeIf { it > 0 } ?: bootstrapBlocking().also { cachedTlsPort = it }
        }
    }

    private suspend fun bootstrapBlocking(): Int {
        Log.i(TAG, "wireless-adb bootstrap: starting")
        // (a) Enable wireless adb (idempotent on adbd; no-op if already on).
        val enable = wirelessManager.enableWirelessDebugging()
        enable.exceptionOrNull()?.let {
            Log.w(TAG, "wireless-adb bootstrap step (a) enableWirelessDebugging failed", it)
            throw IOException("allowWirelessDebugging failed", it)
        }
        Log.i(TAG, "wireless-adb bootstrap: (a) enableWirelessDebugging ok")

        // (b) Discover the TLS adb port. If wireless adb wasn't on a moment ago this may be
        //     -1; retry a couple of times to give AdbDebuggingManager its property-poller window.
        val tlsPort = pollTlsPort(retries = 5, intervalMs = 250)
            ?: throw IOException("getAdbWirelessPort returned -1 after enableWirelessDebugging")
        Log.i(TAG, "wireless-adb bootstrap: (b) tls port = $tlsPort")

        // (c) Pair-once. Skip if our key is already authorized.
        ensurePaired(tlsPort)
        Log.i(TAG, "wireless-adb bootstrap: complete")
        return tlsPort
    }

    private suspend fun pollTlsPort(retries: Int, intervalMs: Long): Int? {
        repeat(retries) {
            val p = wirelessManager.getAdbWirelessPort()
            if (p > 0) return p
            runInterruptible(ioDispatcher) { Thread.sleep(intervalMs) }
        }
        val p = wirelessManager.getAdbWirelessPort()
        return p.takeIf { it > 0 }
    }

    private suspend fun ensurePaired(tlsPort: Int) {
        val pubkeyBase64 = keyStore.androidPubkeyBase64()
        if (keyStore.isPersisted() && wirelessManager.isPubkeyAuthorized(pubkeyBase64)) {
            Log.i(TAG, "wireless-adb pair skipped: pubkey already authorized")
            return
        }

        val psk = randomPsk()
        val pairPort = wirelessManager.openPairPort(name = PAIR_NAME, psk = psk.toByteArray(Charsets.UTF_8))
        try {
            pairingClient.pair(host = LOCALHOST, port = pairPort, psk = psk.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "wireless-adb pairing complete; future runs reuse persisted key")
        } finally {
            runCatching { wirelessManager.closePairPort() }
        }
        // adbd's adb_keys reload is event-driven (file watch) but isn't strictly synchronous —
        // give it a beat before mTLS so the freshly-paired key is in adbd's accepted set.
        runInterruptible(ioDispatcher) { Thread.sleep(POST_PAIR_SETTLE_MS) }
    }

    private fun randomPsk(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun relayAcceptLoop(server: ServerSocket) {
        while (!relayStopped.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (relayStopped.get() || server.isClosed) return
                Log.w(TAG, "relay accept failed", e)
                continue
            }
            Thread({ proxyConnection(client) }, "wireless-adb-relay-${client.port}").apply {
                isDaemon = true
            }.start()
        }
    }

    private fun proxyConnection(client: Socket) {
        var stream: AdbStream? = null
        try {
            client.tcpNoDelay = true
            val tlsPort = cachedTlsPort
            if (tlsPort <= 0) throw IOException("relay invoked before bootstrap")
            // Block on a synchronous open — the relay accepts callers serially per Thread.
            stream = kotlinx.coroutines.runBlocking {
                wireClient.openLocalAbstract(
                    host = LOCALHOST,
                    tlsPort = tlsPort,
                    destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                )
            }
            val streamFinal = stream
            val downstream = Thread({
                runCatching { pump(streamFinal.inputStream, client.getOutputStream()) }
                runCatching { client.shutdownOutput() }
            }, "wireless-adb-down").apply { isDaemon = true }
            val upstream = Thread({
                runCatching { pump(client.getInputStream(), streamFinal.outputStream) }
                runCatching { streamFinal.outputStream.flush() }
            }, "wireless-adb-up").apply { isDaemon = true }
            downstream.start()
            upstream.start()
            downstream.join()
            upstream.join()
        } catch (e: Throwable) {
            Log.w(TAG, "wireless-adb relay proxy error", e)
        } finally {
            runCatching { stream?.close() }
            runCatching { client.close() }
        }
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            if (n > 0) {
                output.write(buf, 0, n)
                output.flush()
            }
        }
    }

    override fun close() {
        if (!relayStopped.compareAndSet(false, true)) return
        runCatching { relayServer?.close() }
        relayServer = null
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val PAIR_NAME = "ClosePaw"
        private const val POST_PAIR_SETTLE_MS = 500L
        private const val TAG = "WirelessAdbSelfPair"
    }
}

/**
 * Bridge integration hook: returned by [WirelessAdbSelfPairTransport] (and any future
 * wireless-self-pair variant) so the bridge's [ShizukuChromeDevtoolsBridge.resolveWebSocketHost]
 * can request the in-process TCP relay port without referencing the concrete class.
 */
interface WirelessAdbRelayHost {
    suspend fun ensureWebSocketRelayPort(): Int?
}

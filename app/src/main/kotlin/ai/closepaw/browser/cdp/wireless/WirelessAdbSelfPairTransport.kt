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
 * Wireless-ADB self-pair transport: drives the spike-proven path
 *   Shizuku → IAdbManager.allowWirelessDebugging → enablePairingByQrCode → embedded TLS-PSK pair
 *   → embedded mTLS adb client → A_OPEN(localabstract:chrome_devtools_remote) → CDP bytes.
 *
 * Lazy: bootstrap is deferred to the first [exchange] / [ensureWebSocketRelayPort] call. The TLS
 * adb port is cached; the RSA keypair is persisted via [AdbCryptoKeyStore], so subsequent runs
 * skip pairing once `/data/misc/adb/adb_keys` already contains our pubkey.
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
    @Volatile private var prunedThisSession = false

    private val relayLock = Any()
    private var relayServer: ServerSocket? = null
    @Volatile private var relayPort: Int = 0
    private val relayStopped = AtomicBoolean(false)

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        val tlsPort = ensureBootstrapped()
        return try {
            wireClient.exchange(
                host = LOCALHOST,
                tlsPort = tlsPort,
                destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                request = request,
                handshakeTimeoutMs = timeoutMs,
            )
        } catch (t: Throwable) {
            // adbd may have rotated the TLS port (Wi-Fi flap, daemon restart) between bootstrap
            // and now; invalidate the cached port and retry once with a fresh bootstrap.
            // TODO: if this retry path fires more than rarely in production, wire a counter so
            //  port-rotation rate becomes observable rather than silent.
            Log.w(TAG, "wireless-adb exchange failed; invalidating cached port and retrying once", t)
            cachedTlsPort = -1
            val fresh = ensureBootstrapped()
            wireClient.exchange(
                host = LOCALHOST,
                tlsPort = fresh,
                destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                request = request,
                handshakeTimeoutMs = timeoutMs,
            )
        }
    }

    override suspend fun ensureWebSocketRelayPort(): Int? {
        if (relayStopped.get()) return null
        ensureBootstrapped()
        synchronized(relayLock) {
            if (relayStopped.get()) return null
            if (relayPort != 0) return relayPort
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
        val enable = wirelessManager.enableWirelessDebugging()
        enable.exceptionOrNull()?.let {
            Log.w(TAG, "wireless-adb bootstrap step (a) enableWirelessDebugging failed", it)
            throw IOException("allowWirelessDebugging failed", it)
        }
        Log.i(TAG, "wireless-adb bootstrap: (a) enableWirelessDebugging ok")

        // AdbDebuggingManager publishes the TLS port via a property poller, so getAdbWirelessPort
        // can briefly return -1 right after enable; retry a handful of times before giving up.
        val tlsPort = pollTlsPort(retries = 5, intervalMs = 250)
            ?: throw IOException("getAdbWirelessPort returned -1 after enableWirelessDebugging")
        Log.i(TAG, "wireless-adb bootstrap: (b) tls port = $tlsPort")

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
            pruneStaleAdbKeysOnce(pubkeyBase64)
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
        // adbd's adb_keys reload is event-driven (file watch) and not strictly synchronous with
        // pair completion; settle briefly so the freshly-paired key is in adbd's accepted set
        // before the immediately-following mTLS handshake.
        runInterruptible(ioDispatcher) { Thread.sleep(POST_PAIR_SETTLE_MS) }
    }

    /**
     * One-shot cleanup of historical `ClosePaw@*` accumulation in `/data/misc/adb/adb_keys`
     * (pre-pair-once each cold session appended a fresh entry). Best-effort: failures here are
     * non-fatal — the existing tunnel keeps working.
     */
    private suspend fun pruneStaleAdbKeysOnce(pubkeyBase64: String) {
        if (prunedThisSession) return
        prunedThisSession = true
        runCatching { wirelessManager.pruneAdbKeys(pubkeyBase64) }
            .onSuccess { pruned ->
                if (pruned) Log.i(TAG, "wireless-adb pruned stale ClosePaw entries from adb_keys")
            }
            .onFailure { Log.w(TAG, "wireless-adb adb_keys prune failed (non-fatal)", it) }
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
        synchronized(relayLock) {
            runCatching { relayServer?.close() }
            relayServer = null
            relayPort = 0
        }
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val PAIR_NAME = "ClosePaw"
        private const val POST_PAIR_SETTLE_MS = 500L
        private const val TAG = "WirelessAdbSelfPair"
    }
}

/**
 * Bridge integration hook for [WirelessAdbSelfPairTransport]'s in-process TCP relay so
 * [ShizukuChromeDevtoolsBridge.resolveWebSocketHost] doesn't depend on the concrete class.
 */
interface WirelessAdbRelayHost {
    suspend fun ensureWebSocketRelayPort(): Int?
}

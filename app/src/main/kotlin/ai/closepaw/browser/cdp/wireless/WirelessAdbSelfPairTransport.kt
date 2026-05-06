package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.RelayAuthToken
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
    /**
     * Per-session unguessable token expected in the WS Upgrade `X-ClosePaw-Token` header. Any
     * other local app can dial 127.0.0.1:[relayPort], so the token is the only thing keeping
     * them out of Chrome's CDP. Must be non-empty.
     */
    private val relayAuthToken: String,
    /**
     * Optional witness that the persisted pubkey was paired before. When non-null, [ensurePaired]
     * skips the SPAKE2 round-trip on cold sessions where adb_keys is unreadable but the cached
     * fingerprint matches the current pubkey — relying on the immediately-following mTLS
     * handshake as the authoritative test. Null disables the optimization (default; existing
     * pre-cache behaviour).
     */
    private val pairOnceCache: PairOnceCache? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DevtoolsSocketTransport, WirelessAdbRelayHost, AutoCloseable {

    init {
        require(relayAuthToken.isNotEmpty()) { "relayAuthToken must not be empty" }
    }

    override val label: TransportLabel = TransportLabel.WIRELESS_ADB_SELF_PAIR

    private val bootstrapLock = Mutex()
    @Volatile private var cachedTlsPort: Int = -1
    @Volatile private var prunedThisSession = false
    /**
     * True iff the most-recent bootstrap skipped pairing on the strength of [pairOnceCache].
     * If the very next mTLS handshake fails, that cache entry is the prime suspect — the
     * exchange-retry path uses this flag to invalidate before re-bootstrapping (which then
     * forces a real pair). Reset on every successful pair / authorized-skip path so a stale
     * "true" from a previous bootstrap can't trigger a spurious invalidation later.
     */
    @Volatile private var lastBootstrapUsedCache = false
    /**
     * Fingerprint earned by a fresh pair that has NOT yet been confirmed by an mTLS handshake.
     * The cache is only written after the next [wireClient] op succeeds — adbd's adb_keys
     * reload is event-driven and a successful SPAKE2 round-trip does not guarantee adbd
     * trusts the new key yet. Cleared on exchange failure (so a bad pair doesn't poison the
     * cache) and on next-call success (so the witness is durable).
     */
    @Volatile private var pendingFingerprintCommit: String? = null

    private val relayLock = Any()
    private var relayServer: ServerSocket? = null
    @Volatile private var relayPort: Int = 0
    private val relayStopped = AtomicBoolean(false)

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        val tlsPort = ensureBootstrapped()
        return try {
            val response = wireClient.exchange(
                host = LOCALHOST,
                tlsPort = tlsPort,
                destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                request = request,
                handshakeTimeoutMs = timeoutMs,
            )
            commitPendingFingerprint()
            response
        } catch (t: Throwable) {
            // adbd may have rotated the TLS port (Wi-Fi flap, daemon restart) between bootstrap
            // and now; invalidate the cached port and retry once with a fresh bootstrap.
            // If the previous bootstrap skipped pairing on the strength of [pairOnceCache], the
            // failure is most likely "adbd doesn't actually trust us" — drop the cache entry so
            // the re-bootstrap re-pairs for real instead of short-circuiting again.
            // If the previous bootstrap performed a fresh pair, drop the pending commit too:
            // the pair did not survive mTLS, so we have no business writing the fingerprint to
            // the cache.
            // TODO: if this retry path fires more than rarely in production, wire a counter so
            //  port-rotation rate becomes observable rather than silent.
            Log.w(TAG, "wireless-adb exchange failed; invalidating cached port and retrying once", t)
            cachedTlsPort = -1
            pendingFingerprintCommit = null
            if (lastBootstrapUsedCache) {
                Log.w(TAG, "wireless-adb exchange failed after pair-once cache short-circuit; invalidating cache")
                lastBootstrapUsedCache = false
                pairOnceCache?.let { runCatching { it.invalidate() } }
            }
            val fresh = ensureBootstrapped()
            val response = wireClient.exchange(
                host = LOCALHOST,
                tlsPort = fresh,
                destination = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                request = request,
                handshakeTimeoutMs = timeoutMs,
            )
            commitPendingFingerprint()
            response
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
        val keyPersisted = keyStore.isPersisted()

        if (keyPersisted && wirelessManager.isPubkeyAuthorized(pubkeyBase64)) {
            // adbd's adb_keys file is readable AND already contains our pubkey — no need to
            // wait for an mTLS round-trip to confirm. Record the cache witness immediately.
            Log.i(TAG, "wireless-adb pair skipped: pubkey already authorized")
            recordPairedFingerprint(pubkeyBase64)
            pruneStaleAdbKeysOnce(pubkeyBase64)
            lastBootstrapUsedCache = false
            pendingFingerprintCommit = null
            return
        }

        // [isPubkeyAuthorized] returned false: either adb_keys is genuinely missing our key, or
        // the file was unreadable to us (locked OEMs whose shell uid is dropped from the `adb`
        // group — e.g. nubia P0110). Only the unreadable case justifies trusting the pair-once
        // cache: if the file is readable and our key is missing, we MUST re-pair regardless of
        // what we previously cached.
        val cache = pairOnceCache
        if (keyPersisted && cache != null) {
            val status = wirelessManager.pubkeyAuthorizationStatus(pubkeyBase64)
            if (status == AdbWirelessManager.AuthorizationStatus.UNREADABLE) {
                val currentFp = PairOnceCache.fingerprintOf(pubkeyBase64)
                if (cache.getCachedFingerprint() == currentFp) {
                    Log.i(TAG, "wireless-adb pair skipped: adb_keys unreadable, pair-once cache hit")
                    lastBootstrapUsedCache = true
                    pendingFingerprintCommit = null
                    return
                }
            }
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
        // DEFER: don't write the cache yet. SPAKE2 success doesn't prove adbd actually loaded
        // our key — only the next mTLS handshake does. The exchange-success path commits this;
        // the exchange-failure path drops it.
        lastBootstrapUsedCache = false
        pendingFingerprintCommit = if (pairOnceCache != null) {
            PairOnceCache.fingerprintOf(pubkeyBase64)
        } else {
            null
        }
    }

    private suspend fun commitPendingFingerprint() {
        val fp = pendingFingerprintCommit ?: return
        pendingFingerprintCommit = null
        val cache = pairOnceCache ?: return
        runCatching { cache.recordSuccessfulPair(fp) }
            .onFailure { Log.w(TAG, "wireless-adb pair-once cache write failed (non-fatal)", it) }
    }

    private suspend fun recordPairedFingerprint(pubkeyBase64: String) {
        val cache = pairOnceCache ?: return
        runCatching { cache.recordSuccessfulPair(PairOnceCache.fingerprintOf(pubkeyBase64)) }
            .onFailure { Log.w(TAG, "wireless-adb pair-once cache write failed (non-fatal)", it) }
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
            // Slowloris defense — see ChromeDevtoolsUserService.proxyConnection for rationale.
            client.soTimeout = RelayAuthToken.PRE_AUTH_DEADLINE_MS
            // Token gate: read WS Upgrade headers before opening the upstream adb stream so a
            // rejected client never costs a remote socket round-trip.
            val parsed = try {
                RelayAuthToken.readHttpRequestHead(
                    input = client.getInputStream(),
                    totalDeadlineMs = RelayAuthToken.PRE_AUTH_DEADLINE_MS,
                    setReadTimeout = { client.soTimeout = it },
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "wireless-adb relay token gate: pre-auth deadline exceeded (slowloris?): ${e.message}")
                RelayAuthToken.write408(client.getOutputStream())
                return
            }
            if (parsed !is RelayAuthToken.ParseResult.Success) {
                Log.w(TAG, "wireless-adb relay token gate: rejected (${(parsed as RelayAuthToken.ParseResult.Failure).reason})")
                RelayAuthToken.write403(client.getOutputStream())
                return
            }
            if (!RelayAuthToken.verify(relayAuthToken, parsed.token)) {
                Log.w(TAG, "wireless-adb relay token gate: rejected (token missing or mismatched)")
                RelayAuthToken.write403(client.getOutputStream())
                return
            }
            // Auth OK — restore infinite read timeout for the long-lived proxied stream.
            client.soTimeout = 0
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
            // Replay buffered request bytes upstream verbatim — Chrome ignores the unknown
            // [RelayAuthToken.HEADER_NAME] header.
            streamFinal.outputStream.write(parsed.bytes)
            streamFinal.outputStream.flush()
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

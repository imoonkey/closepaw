package ai.closepaw.browser.script

import android.content.Context
import ai.closepaw.browser.cdp.CdpConnection
import ai.closepaw.browser.cdp.CdpConnectionFactory
import ai.closepaw.browser.cdp.CdpConnectionClosedException
import ai.closepaw.browser.cdp.ChromeCdpClient
import ai.closepaw.browser.cdp.ChromeCdpTarget
import ai.closepaw.browser.cdp.shizuku.DefaultDevtoolsDiagnostics
import ai.closepaw.browser.cdp.shizuku.DevtoolsSetupError
import ai.closepaw.browser.cdp.shizuku.DevtoolsVersion
import ai.closepaw.browser.cdp.shizuku.PageTarget
import ai.closepaw.browser.cdp.shizuku.ShizukuChromeDevtoolsBridge
import ai.closepaw.browser.cdp.shizuku.ShizukuChromeRunningProbe
import ai.closepaw.browser.cdp.shizuku.ShizukuStatusAdapter
import ai.closepaw.browser.cdp.shizuku.ShizukuUserServiceProvider
import ai.closepaw.browser.cdp.shizuku.UserServiceTransport
import ai.closepaw.browser.cdp.wireless.AdbCryptoKeyStore
import ai.closepaw.browser.cdp.wireless.AdbPairingClient
import ai.closepaw.browser.cdp.wireless.AdbWireProtocolClient
import ai.closepaw.browser.cdp.wireless.AdbWirelessManager
import ai.closepaw.browser.cdp.wireless.WirelessAdbSelfPairTransport
import ai.closepaw.trace.TraceRecorder
import java.io.Closeable
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Session-scoped owner for the browser runtime.
 *
 * Construction is cheap: no WebView, CDP socket, or Shizuku UserService binding is created until
 * `browser_script` is actually invoked. The manager owns the warm CDP client for the session and
 * tears down both CDP and Shizuku resources when the session shuts down.
 */
class BrowserSessionManager(
    context: Context,
    sessionScope: CoroutineScope,
    @Suppress("unused") private val traceRecorder: TraceRecorder,
    /**
     * Per-CDP-command timeout passed to [ChromeCdpClient]. The script's outer `timeout_ms` is
     * a separate, larger budget for the whole script; this cap fires when a single CDP method
     * (most importantly `Page.loadEventFired { awaitEvent: true }`) blocks longer than the
     * cap. Defaults to [ChromeCdpClient.DEFAULT_COMMAND_TIMEOUT_MS] (30s) which leaves headroom
     * for cellular page loads through the wireless-ADB self-pair relay.
     */
    private val cdpCommandTimeoutMs: Long = ChromeCdpClient.DEFAULT_COMMAND_TIMEOUT_MS,
    private val bridgeFactory: () -> BrowserDevtoolsBridge = {
        ShizukuBrowserDevtoolsBridge(
            createDefaultBridge(context.applicationContext)
        )
    },
    private val cdpConnectionFactory: CdpConnectionFactory = OkHttpCdpConnectionFactory(),
    private val cdpClientFactory: (CdpConnectionFactory, (Throwable) -> Unit) -> ChromeCdpClient =
        { factory, onTransportFailure ->
            ChromeCdpClient(
                connectionFactory = factory,
                commandTimeoutMs = cdpCommandTimeoutMs,
                onTransportFailure = onTransportFailure,
            )
        },
    private val runnerFactory: (Context, ChromeCdpClient) -> BrowserScriptExecutor = { ctx, client ->
        val runner = BrowserScriptRunner(ctx, client)
        BrowserScriptExecutor { script, timeoutMs -> runner.run(script, timeoutMs) }
    },
) : Closeable {
    private val appContext = context.applicationContext
    private val runtimeJob = SupervisorJob(sessionScope.coroutineContext[Job])
    private val scriptLease = Mutex()
    private val resourceLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var bridge: BrowserDevtoolsBridge? = null

    private var bridgeGeneration: Long = 0L

    @Volatile
    private var resources: BrowserRuntimeResources? = null

    suspend fun preflight() {
        if (closed) throw IllegalStateException("Browser session is closed")
        bridge().bridge.preflight()
    }

    suspend fun run(script: String, timeoutMs: Long): ScriptResult {
        if (closed) return ScriptResult.Cancelled("Browser session is closed")
        if (!scriptLease.tryLock()) {
            return ScriptResult.Failure("browser_script is already running for this session", null)
        }
        return try {
            withContext(runtimeJob) {
                ensureResources().runner.run(script, timeoutMs)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            markBroken()
            ScriptResult.HostError(t)
        } finally {
            scriptLease.unlock()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runtimeJob.cancel("BrowserSessionManager closed")
        closeResources()
        closeBridge()
    }

    private suspend fun ensureResources(): BrowserRuntimeResources {
        if (closed) throw IllegalStateException("Browser session is closed")
        resources?.let { return it }

        val bridgeHandle = bridge()
        lateinit var client: ChromeCdpClient
        client = cdpClientFactory(cdpConnectionFactory) {
            markBroken(bridgeHandle.generation, client)
        }
        return try {
            val version = bridgeHandle.bridge.fetchVersion()
            val targets = bridgeHandle.bridge.listPageTargets()
            val rawEndpoint = websocketEndpoint(version, targets)
            val tunnelHost = bridgeHandle.bridge.resolveWebSocketHost()
            val endpoint = if (tunnelHost != null) {
                rawEndpoint.copy(url = rewriteHost(rawEndpoint.url, tunnelHost))
            } else {
                rawEndpoint
            }
            client.connect(endpoint.url)
            if (endpoint.directTargetId != null) {
                client.useDirectPageTarget(endpoint.directTargetId, endpoint.url)
            } else {
                client.attachToFirstRealPage(targets)
            }
            enableCoreDomains(client)
            val newResources = BrowserRuntimeResources(
                cdpClient = client,
                runner = runnerFactory(appContext, client),
            )
            synchronized(resourceLock) {
                resources ?: newResources.also { resources = it }
            }
        } catch (t: Throwable) {
            client.close()
            throw t
        }
    }

    private fun bridge(): BrowserBridgeHandle {
        bridge?.let { return BrowserBridgeHandle(it, bridgeGeneration) }
        return synchronized(resourceLock) {
            bridge?.let { BrowserBridgeHandle(it, bridgeGeneration) } ?: run {
                val newBridge = bridgeFactory()
                bridgeGeneration += 1
                bridge = newBridge
                BrowserBridgeHandle(newBridge, bridgeGeneration)
            }
        }
    }

    private fun websocketEndpoint(version: DevtoolsVersion, targets: List<PageTarget>): WebSocketEndpoint {
        ChromeCdpTarget.firstRealPage(targets)
            ?.takeIf { !it.webSocketDebuggerUrl.isNullOrBlank() }
            ?.let { return WebSocketEndpoint(it.webSocketDebuggerUrl!!, directTargetId = it.id) }
        version.webSocketDebuggerUrl?.takeIf { it.isNotBlank() }?.let {
            return WebSocketEndpoint(it, directTargetId = null)
        }
        targets.firstOrNull { !it.webSocketDebuggerUrl.isNullOrBlank() }?.webSocketDebuggerUrl
            ?.let { return WebSocketEndpoint(it, directTargetId = null) }
        throw DevtoolsSetupError.MalformedResponse("missing webSocketDebuggerUrl")
    }

    /**
     * Replace the `[scheme]://[host[:port]]` prefix of a `ws://...` URL with the given
     * `host:port`. Path/query are preserved verbatim. Used to redirect Chrome's
     * `webSocketDebuggerUrl` (which has no port — defaults to 80, unreachable from app UID)
     * onto the device-side TCP relay served by the Shizuku UserService.
     */
    private fun rewriteHost(url: String, hostAndPort: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3).let { if (it < 0) url.length else it }
        val scheme = url.substring(0, schemeEnd)
        val rest = url.substring(pathStart)
        return "$scheme://$hostAndPort$rest"
    }

    private suspend fun enableCoreDomains(client: ChromeCdpClient) {
        client.send("Page.enable")
        client.send("Runtime.enable")
        client.send("DOM.enable")
        client.send("Network.enable")
    }

    private fun markBroken(
        expectedBridgeGeneration: Long? = null,
        failedClient: ChromeCdpClient? = null,
    ) {
        val old = synchronized(resourceLock) {
            if (expectedBridgeGeneration != null && bridgeGeneration != expectedBridgeGeneration) {
                return
            }
            val currentResources = resources
            if (failedClient != null &&
                currentResources != null &&
                currentResources.cdpClient !== failedClient
            ) {
                return
            }
            val currentBridge = bridge
            resources = null
            bridge = null
            bridgeGeneration += 1
            currentResources to currentBridge
        }
        old.first?.close()
        old.second?.close()
    }

    private fun closeResources() {
        val old = synchronized(resourceLock) {
            val r = resources
            resources = null
            r
        }
        old?.close()
    }

    private fun closeBridge() {
        val old = synchronized(resourceLock) {
            val b = bridge
            bridge = null
            if (b != null) bridgeGeneration += 1
            b
        }
        old?.close()
    }

    private data class BrowserBridgeHandle(
        val bridge: BrowserDevtoolsBridge,
        val generation: Long,
    )

    private data class BrowserRuntimeResources(
        val cdpClient: ChromeCdpClient,
        val runner: BrowserScriptExecutor,
    ) : Closeable {
        override fun close() {
            cdpClient.close()
        }
    }

    private data class WebSocketEndpoint(
        val url: String,
        val directTargetId: String?,
    )

    companion object {
        private fun createDefaultBridge(context: Context): ShizukuChromeDevtoolsBridge {
            val userServiceProvider = ShizukuUserServiceProvider(context)
            val keyStoreDir = File(context.applicationContext.filesDir, "adb_self_pair")
            val keyStore = AdbCryptoKeyStore(keyStoreDir)
            val pairingClient = AdbPairingClient(keyStore)
            val wireClient = AdbWireProtocolClient(keyStore)
            val wirelessManager = AdbWirelessManager(
                binderProvider = {
                    val transport = userServiceProvider.obtain() as? UserServiceTransport
                        ?: error("UserServiceProvider returned non-UserServiceTransport")
                    transport.binder
                }
            )
            val wirelessTransport = WirelessAdbSelfPairTransport(
                wirelessManager = wirelessManager,
                keyStore = keyStore,
                pairingClient = pairingClient,
                wireClient = wireClient,
            )
            return ShizukuChromeDevtoolsBridge(
                status = ShizukuStatusAdapter(),
                diagnostics = DefaultDevtoolsDiagnostics(
                    chromeRunningProbe = ShizukuChromeRunningProbe(),
                ),
                userServiceProvider = userServiceProvider,
                wirelessAdbSelfPairTransport = wirelessTransport,
            )
        }
    }
}

fun interface BrowserScriptExecutor {
    suspend fun run(script: String, timeoutMs: Long): ScriptResult
}

interface BrowserDevtoolsBridge : Closeable {
    suspend fun preflight()
    suspend fun fetchVersion(): DevtoolsVersion
    suspend fun listPageTargets(): List<PageTarget>
    /**
     * Returns `host:port` to substitute into `webSocketDebuggerUrl` from Chrome, or null to
     * use the URL as-is. Used by the Shizuku transport to route CDP through the device-side
     * TCP relay instead of the app-UID-unreachable abstract socket.
     */
    suspend fun resolveWebSocketHost(): String? = null
}

private class ShizukuBrowserDevtoolsBridge(
    private val delegate: ShizukuChromeDevtoolsBridge,
) : BrowserDevtoolsBridge {
    override suspend fun preflight() = delegate.preflight()
    override suspend fun fetchVersion(): DevtoolsVersion = delegate.fetchVersion()
    override suspend fun listPageTargets(): List<PageTarget> = delegate.listPageTargets()
    override suspend fun resolveWebSocketHost(): String? = delegate.resolveWebSocketHost()
    override fun close() = delegate.close()
}

private class OkHttpCdpConnectionFactory(
    private val client: OkHttpClient = OkHttpClient(),
) : CdpConnectionFactory {
    override suspend fun connect(
        url: String,
        onMessage: (String) -> Unit,
        onFailure: (Throwable) -> Unit,
        onClosed: (CdpConnectionClosedException) -> Unit,
    ): CdpConnection = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(url).build()
        val notifyClosed = onClosed
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (cont.isActive) cont.resume(OkHttpCdpConnection(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) {
                    cont.resumeWithException(t)
                } else {
                    onFailure(t)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                notifyClosed(CdpConnectionClosedException(code, reason))
            }
        }
        val socket = client.newWebSocket(request, listener)
        cont.invokeOnCancellation { socket.cancel() }
    }
}

private class OkHttpCdpConnection(
    private val webSocket: WebSocket,
) : CdpConnection {
    override fun send(text: String) {
        check(webSocket.send(text)) { "CDP WebSocket rejected outbound message" }
    }

    override fun close() {
        webSocket.close(1000, "browser session closed")
    }
}

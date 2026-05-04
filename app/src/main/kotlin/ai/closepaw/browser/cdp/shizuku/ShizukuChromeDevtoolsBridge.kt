package ai.closepaw.browser.cdp.shizuku

import ai.closepaw.browser.cdp.wireless.WirelessAdbRelayHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge from CDP traffic to a working device-side transport. Cascade is
 * USER_SERVICE → WIRELESS_ADB_SELF_PAIR; failures map to distinct [DevtoolsSetupError] codes
 * so the capability gate can render an actionable reason instead of a generic IOException.
 */
class ShizukuChromeDevtoolsBridge(
    private val status: ShizukuStatusProvider,
    private val diagnostics: DevtoolsDiagnostics,
    private val userServiceProvider: UserServiceProvider,
    private val wirelessAdbSelfPairTransport: DevtoolsSocketTransport? = null,
    /**
     * Per-session unguessable token expected on the WS Upgrade `X-ClosePaw-Token` header by
     * both relays. Required: with no token, any local app can dial 127.0.0.1:<relayPort> and
     * drive Chrome's CDP. See [ai.closepaw.browser.cdp.RelayAuthToken].
     */
    private val relayAuthToken: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    init {
        require(relayAuthToken.isNotEmpty()) { "relayAuthToken must not be empty" }
        require(wirelessAdbSelfPairTransport == null ||
                wirelessAdbSelfPairTransport.label == TransportLabel.WIRELESS_ADB_SELF_PAIR) {
            "wirelessAdbSelfPairTransport must declare TransportLabel.WIRELESS_ADB_SELF_PAIR"
        }
    }

    /**
     * Tracks which transport satisfied the most recent successful HTTP fetch so
     * [resolveWebSocketHost] can pick the right WS URL transformation. Both transports terminate
     * on Chrome's port-less abstract socket; the bridge rewrites `webSocketDebuggerUrl`'s host
     * onto a transport-specific TCP relay so OkHttp can reach it from the app UID.
     */
    @Volatile
    private var lastSuccessfulTransport: TransportLabel? = null

    /** Reads `/json/version` and returns the parsed payload. */
    suspend fun fetchVersion(): DevtoolsVersion = withContext(ioDispatcher) {
        val body = httpGet(JSON_VERSION_PATH)
        DevtoolsHttpProtocol.parseVersion(body)
    }

    /** Reads `/json/list` and returns the parsed page-target array. */
    suspend fun listPageTargets(): List<PageTarget> = withContext(ioDispatcher) {
        val body = httpGet(JSON_LIST_PATH)
        DevtoolsHttpProtocol.parsePageTargets(body)
    }

    /** Run preflight only — useful for explicit diagnostics endpoints. Throws on failure. */
    suspend fun preflight(): Unit = withContext(ioDispatcher) { runPreflight() }

    /**
     * Resolve the host:port the CDP WebSocket should connect to. Chrome reflects our
     * `Host: localhost` request header verbatim, so the WS URL has no port — point it at the
     * transport-owned device-side TCP relay instead.
     */
    suspend fun resolveWebSocketHost(): String? = withContext(ioDispatcher) {
        when (lastSuccessfulTransport) {
            TransportLabel.USER_SERVICE -> {
                val transport = userServiceProvider.obtain() as? UserServiceTransport
                    ?: return@withContext null
                val port = transport.ensureRelayPortSuspend(relayAuthToken)
                "127.0.0.1:$port"
            }
            TransportLabel.WIRELESS_ADB_SELF_PAIR -> {
                val transport = wirelessAdbSelfPairTransport as? WirelessAdbRelayHost
                    ?: return@withContext null
                transport.ensureWebSocketRelayPort()?.let { "127.0.0.1:$it" }
            }
            else -> null
        }
    }

    /** Release any Shizuku UserService binding owned by this bridge. */
    fun close() {
        userServiceProvider.close()
        (wirelessAdbSelfPairTransport as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private suspend fun httpGet(path: String): String {
        runPreflight()
        val request = DevtoolsHttpProtocol.buildGet(path)

        // Some OEM builds (e.g. nubia P0110) deny shell-domain connectto on appdomain abstract
        // sockets, so the UserService leg can fail; the wireless leg routes through adbd instead.
        val userResponse = runCatching {
            DevtoolsHttpProtocol.parseHttpBody(
                userServiceProvider.obtain().exchange(request, requestTimeoutMs)
            )
        }
        userResponse.getOrNull()?.let {
            lastSuccessfulTransport = TransportLabel.USER_SERVICE
            return it
        }
        val userError = userResponse.exceptionOrNull()
        val wireless = wirelessAdbSelfPairTransport
            ?: throw toUserServiceError(userError)
        val wirelessResponse = runCatching {
            DevtoolsHttpProtocol.parseHttpBody(wireless.exchange(request, requestTimeoutMs))
        }
        wirelessResponse.getOrNull()?.let {
            lastSuccessfulTransport = TransportLabel.WIRELESS_ADB_SELF_PAIR
            return it
        }
        val wirelessError = wirelessResponse.exceptionOrNull()
        if (wirelessError is DevtoolsSetupError) throw wirelessError
        throw DevtoolsSetupError.WirelessAdbSelfPairUnavailable(wirelessError ?: userError)
    }

    private fun toUserServiceError(error: Throwable?): DevtoolsSetupError = when (error) {
        is DevtoolsSetupError -> error
        else -> DevtoolsSetupError.UserServiceSocketInaccessible(error)
    }

    private suspend fun runPreflight() {
        // Distinguish "DevTools socket missing" vs "Chrome not running" only when the probes are
        // *certain* — Unknown means defer to the transport so we never lie about device state.
        val socketProbe = diagnostics.isDevtoolsSocketBound()
        if (socketProbe == SocketProbeResult.NotBound) {
            when (diagnostics.isChromeRunning()) {
                ChromeRunningResult.Running -> throw DevtoolsSetupError.DevtoolsSocketMissing
                ChromeRunningResult.NotRunning -> throw DevtoolsSetupError.ChromeNotRunning
                ChromeRunningResult.Unknown -> Unit
            }
        }

        if (!status.isAvailable()) throw DevtoolsSetupError.ShizukuUnavailable
        if (!status.hasPermission()) throw DevtoolsSetupError.ShizukuPermissionMissing
    }

    companion object {
        const val CHROME_DEVTOOLS_SOCKET = "chrome_devtools_remote"
        const val JSON_VERSION_PATH = "/json/version"
        const val JSON_LIST_PATH = "/json/list"
        const val DEFAULT_TIMEOUT_MS = 4_000
    }
}

/** Adapter over the real Shizuku binder so the bridge stays testable. */
interface ShizukuStatusProvider {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
}

/**
 * Side-channel diagnostics so the bridge can distinguish "Chrome not running" from "DevTools
 * socket missing" without depending on `connect()`'s `ECONNREFUSED` ambiguity. Tri-state
 * results so we never claim certainty we don't have.
 */
interface DevtoolsDiagnostics {
    fun isDevtoolsSocketBound(): SocketProbeResult
    fun isChromeRunning(): ChromeRunningResult
}

enum class SocketProbeResult { Bound, NotBound, Unknown }

enum class ChromeRunningResult { Running, NotRunning, Unknown }

/** A single round-trip transport over Chrome's DevTools abstract socket. */
interface DevtoolsSocketTransport {
    val label: TransportLabel
    /** Send the HTTP request bytes and read the full response (Connection: close). */
    suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray
}

enum class TransportLabel { USER_SERVICE, WIRELESS_ADB_SELF_PAIR }

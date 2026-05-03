package ai.closepaw.browser.cdp.shizuku

import ai.closepaw.browser.cdp.wireless.WirelessAdbRelayHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge that resolves Chrome's DevTools traffic to a working transport:
 *
 * 1. Validate Shizuku binder + permission.
 * 2. Probe whether Chrome's `chrome_devtools_remote` abstract socket is bound and Chrome is
 *    actually running. Distinguish "DevTools socket missing" vs "Chrome not running" only when
 *    the probes are *certain*; defer to the transport otherwise so we never lie about state.
 * 3. Try the Shizuku UserService transport (LocalSocket from the shell UID).
 * 4. Fall back to the wireless-ADB self-pair transport (in-app TLS-PSK pair + mTLS adb wire
 *    protocol) for OEM builds where step 3 is denied by SELinux.
 * 5. Speak strict HTTP/1.1 over the chosen transport and parse `/json/version` and `/json/list`.
 *
 * Every failure mode surfaces as a distinct [DevtoolsSetupError] so the BrowserScriptTool layer
 * can hand the user an actionable next step rather than a generic "couldn't connect".
 */
class ShizukuChromeDevtoolsBridge(
    private val status: ShizukuStatusProvider,
    private val diagnostics: DevtoolsDiagnostics,
    private val userServiceProvider: UserServiceProvider,
    private val wirelessAdbSelfPairTransport: DevtoolsSocketTransport? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    init {
        require(wirelessAdbSelfPairTransport == null ||
                wirelessAdbSelfPairTransport.label == TransportLabel.WIRELESS_ADB_SELF_PAIR) {
            "wirelessAdbSelfPairTransport must declare TransportLabel.WIRELESS_ADB_SELF_PAIR"
        }
    }

    /**
     * Tracks which transport satisfied the most recent successful HTTP fetch so
     * [resolveWebSocketHost] can pick the right WS URL transformation.
     *
     * Both supported transports terminate on a Unix-domain abstract socket with no port concept,
     * so Chrome reflects the inbound `Host: localhost` request header into a port-less
     * `ws://localhost/devtools/...`. We must rewrite that onto the device-side TCP relay each
     * transport exposes on demand.
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
     * Resolve the host:port the CDP WebSocket should connect to.
     *
     * Chrome reflects the inbound HTTP request's `Host` header into the `webSocketDebuggerUrl`
     * payload. The bridge always builds requests with `Host: localhost`, so the resulting URL
     * has no port (which OkHttp then defaults to 80, unreachable). For each transport we point
     * the WS at the device-side TCP relay it owns.
     */
    suspend fun resolveWebSocketHost(): String? = withContext(ioDispatcher) {
        when (lastSuccessfulTransport) {
            TransportLabel.USER_SERVICE -> {
                val transport = userServiceProvider.obtain() as? UserServiceTransport
                    ?: return@withContext null
                val port = transport.ensureRelayPortSuspend()
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

        // 1. Shizuku UserService — works on AOSP/userdebug + properly-bootstrapped Shizuku.
        //    Some OEM builds (e.g. nubia P0110) deny shell-domain connectto on appdomain
        //    abstract sockets, so the call returns IOException("Permission denied").
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

        // 2. Wireless-ADB self-pair — autonomous in-device path. Shizuku spawns a shell-uid
        //    helper that calls IAdbManager AIDL to enable wireless adb + open a pair port with
        //    our chosen PSK; the app then runs an in-process TLS-PSK pair + mTLS adb client to
        //    A_OPEN(localabstract:chrome_devtools_remote) through adbd. No PC, no root.
        val wireless = wirelessAdbSelfPairTransport
            ?: throw toUserServiceError(userError)
        val wirelessResponse = runCatching {
            DevtoolsHttpProtocol.parseHttpBody(
                wireless.exchange(request, requestTimeoutMs)
            )
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
        // Chrome's DevTools socket has to actually be bound for any transport to succeed.
        // We surface DevtoolsSocketMissing / ChromeNotRunning only when the diagnostics are
        // CERTAIN — Unknown means defer to the transport's own error.
        val socketProbe = diagnostics.isDevtoolsSocketBound()
        if (socketProbe == SocketProbeResult.NotBound) {
            when (diagnostics.isChromeRunning()) {
                ChromeRunningResult.Running -> throw DevtoolsSetupError.DevtoolsSocketMissing
                ChromeRunningResult.NotRunning -> throw DevtoolsSetupError.ChromeNotRunning
                ChromeRunningResult.Unknown -> Unit
            }
        }

        // Both supported transports require Shizuku.
        if (!status.isAvailable()) throw DevtoolsSetupError.ShizukuUnavailable
        if (!status.hasPermission()) throw DevtoolsSetupError.ShizukuPermissionMissing
    }

    companion object {
        const val CHROME_DEVTOOLS_SOCKET = "chrome_devtools_remote"
        const val JSON_VERSION_PATH = "/json/version"
        const val JSON_LIST_PATH = "/json/list"
        const val DEFAULT_TIMEOUT_MS = 30_000
    }
}

/** Adapter over the real Shizuku binder so the bridge stays testable. */
interface ShizukuStatusProvider {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
}

/**
 * Side-channel diagnostics so the bridge can distinguish "Chrome not running" from "DevTools
 * socket missing" without depending on `connect()` failure modes (which collapse both into
 * `ECONNREFUSED`). Both probes return a tri-state — Bound/NotBound/Unknown and
 * Running/NotRunning/Unknown — so we never claim certainty we don't have.
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

package ai.closepaw.browser.cdp.shizuku

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spike-quality bridge that proves the Chrome DevTools transport story end-to-end:
 *
 * 1. Validate Shizuku binder + permission.
 * 2. Probe whether Chrome's `chrome_devtools_remote` abstract socket is bound and Chrome is
 *    actually running. Distinguish "DevTools socket missing" vs "Chrome not running" only when
 *    the probes are *certain*; defer to the transport otherwise so we never lie about state.
 * 3. Try the direct app-process [DevtoolsSocketTransport] (LocalSocket from the app UID).
 * 4. Fall back to a Shizuku UserService transport (LocalSocket from the shell UID), produced
 *    lazily by [UserServiceProvider] so binding only happens when needed.
 * 5. Speak strict HTTP/1.1 over the chosen transport and parse `/json/version` and `/json/list`.
 *
 * The constructor takes the two transport seats by name — not a free-form list — so the
 * production order (app-process first, UserService second) is enforced by this class, not by
 * the caller.
 *
 * Every failure mode surfaces as a distinct [DevtoolsSetupError] so the BrowserScriptTool layer
 * can hand the user an actionable next step rather than a generic "couldn't connect".
 */
class ShizukuChromeDevtoolsBridge(
    private val status: ShizukuStatusProvider,
    private val diagnostics: DevtoolsDiagnostics,
    private val appProcessTransport: DevtoolsSocketTransport,
    private val userServiceProvider: UserServiceProvider? = null,
    private val chromeTcpLoopbackTransport: DevtoolsSocketTransport? = null,
    private val fallbackTransport: DevtoolsSocketTransport? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    init {
        require(appProcessTransport.label == TransportLabel.APP_PROCESS) {
            "appProcessTransport must declare TransportLabel.APP_PROCESS"
        }
        require(chromeTcpLoopbackTransport == null ||
                chromeTcpLoopbackTransport.label == TransportLabel.CHROME_TCP_LOOPBACK) {
            "chromeTcpLoopbackTransport must declare TransportLabel.CHROME_TCP_LOOPBACK"
        }
        require(fallbackTransport == null || fallbackTransport.label == TransportLabel.DEBUG_TCP) {
            "fallbackTransport must declare TransportLabel.DEBUG_TCP"
        }
    }

    /**
     * Tracks which transport satisfied the most recent successful HTTP fetch so
     * [resolveWebSocketHost] can pick the right WS URL transformation:
     *  - APP_PROCESS / DEBUG_TCP / CHROME_TCP_LOOPBACK → use Chrome's URL as-is (Chrome
     *    knows the port it's bound to, so the embedded `webSocketDebuggerUrl` is correct).
     *  - USER_SERVICE → rewrite host to `127.0.0.1:<relayPort>` because the URL has no port
     *    (Chrome thinks it's serving the abstract socket).
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
     * Resolve the host:port the CDP WebSocket should connect to. When the UserService transport
     * was the most recent success, returns `127.0.0.1:<relayPort>` from the device-side TCP
     * relay (CDP traffic is tunneled through the shell-UID UserService because Chrome's
     * `webSocketDebuggerUrl` has no port and the abstract socket is unreachable from app UID).
     * For every other transport — including Phase 2 [TransportLabel.CHROME_TCP_LOOPBACK] —
     * Chrome's response already contains the correct port, so this returns null.
     */
    suspend fun resolveWebSocketHost(): String? = withContext(ioDispatcher) {
        if (lastSuccessfulTransport != TransportLabel.USER_SERVICE) return@withContext null
        val provider = userServiceProvider ?: return@withContext null
        val transport = provider.obtain() as? UserServiceTransport
            ?: return@withContext null
        val port = transport.ensureRelayPortSuspend()
        "127.0.0.1:$port"
    }

    /** Release any Shizuku UserService binding owned by this bridge. */
    fun close() {
        userServiceProvider?.close()
    }

    private suspend fun httpGet(path: String): String {
        runPreflight()
        val request = DevtoolsHttpProtocol.buildGet(path)

        val appResponse = runCatching {
            DevtoolsHttpProtocol.parseHttpBody(
                appProcessTransport.exchange(request, requestTimeoutMs)
            )
        }
        appResponse.getOrNull()?.let {
            lastSuccessfulTransport = TransportLabel.APP_PROCESS
            return it
        }
        val appError = appResponse.exceptionOrNull()

        val provider = userServiceProvider
        var userError: Throwable? = null
        if (provider != null) {
            val userResponse = runCatching {
                DevtoolsHttpProtocol.parseHttpBody(
                    provider.obtain().exchange(request, requestTimeoutMs)
                )
            }
            userResponse.getOrNull()?.let {
                lastSuccessfulTransport = TransportLabel.USER_SERVICE
                return it
            }
            userError = userResponse.exceptionOrNull()
        }

        // Phase 2: Chrome --remote-debugging-port=N TCP loopback (works on locked OEM devices).
        val chromeTcp = chromeTcpLoopbackTransport
        if (chromeTcp != null) {
            val chromeResponse = runCatching {
                DevtoolsHttpProtocol.parseHttpBody(
                    chromeTcp.exchange(request, requestTimeoutMs)
                )
            }
            chromeResponse.getOrNull()?.let {
                lastSuccessfulTransport = TransportLabel.CHROME_TCP_LOOPBACK
                return it
            }
            // ChromeRemoteDebuggingFlagNotEnabled is actionable — surface it; otherwise fall
            // through to debug TCP fallback so we don't mask the more specific error.
            (chromeResponse.exceptionOrNull() as? DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled)
                ?.let { throw it }
        }

        if (provider == null) {
            val fallbackResponse = tryFallbackTransport(request, userServiceCause = null)
            if (fallbackResponse != null) {
                lastSuccessfulTransport = TransportLabel.DEBUG_TCP
                return DevtoolsHttpProtocol.parseHttpBody(fallbackResponse)
            }
            if (appError is DevtoolsSetupError) throw appError
            throw DevtoolsSetupError.AppProcessSocketInaccessible(appError)
        }

        val fallbackResponse = tryFallbackTransport(request, userError)
        if (fallbackResponse != null) {
            lastSuccessfulTransport = TransportLabel.DEBUG_TCP
            return DevtoolsHttpProtocol.parseHttpBody(fallbackResponse)
        }

        throw toUserServiceError(userError)
    }

    private suspend fun tryFallbackTransport(
        request: ByteArray,
        userServiceCause: Throwable?,
    ): ByteArray? {
        val fallback = fallbackTransport ?: return null
        return try {
            fallback.exchange(request, requestTimeoutMs)
        } catch (e: DevtoolsSetupError) {
            throw e
        } catch (e: Throwable) {
            throw DevtoolsSetupError.DebugTcpFallbackInaccessible(e, userServiceCause)
        }
    }

    private fun toUserServiceError(error: Throwable?): DevtoolsSetupError.UserServiceSocketInaccessible {
        return when (error) {
            is DevtoolsSetupError.UserServiceSocketInaccessible -> error
            is DevtoolsSetupError -> throw error
            else -> DevtoolsSetupError.UserServiceSocketInaccessible(error)
        }
    }

    private fun runPreflight() {
        if (!status.isAvailable()) throw DevtoolsSetupError.ShizukuUnavailable
        if (!status.hasPermission()) throw DevtoolsSetupError.ShizukuPermissionMissing

        val socketProbe = diagnostics.isDevtoolsSocketBound()
        if (socketProbe == SocketProbeResult.NotBound) {
            // Only emit the distinct preflight verdict when we're CERTAIN about Chrome's state.
            // If the chrome probe returned Unknown we don't know whether the missing socket means
            // Chrome isn't running or DevTools isn't enabled, so defer to the transport — it will
            // produce a precise app/user-service-inaccessible error instead.
            when (diagnostics.isChromeRunning()) {
                ChromeRunningResult.Running -> throw DevtoolsSetupError.DevtoolsSocketMissing
                ChromeRunningResult.NotRunning -> throw DevtoolsSetupError.ChromeNotRunning
                ChromeRunningResult.Unknown -> Unit
            }
        }
        // SocketProbeResult.Bound and SocketProbeResult.Unknown both proceed to the transport.
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

enum class TransportLabel { APP_PROCESS, USER_SERVICE, DEBUG_TCP, CHROME_TCP_LOOPBACK }

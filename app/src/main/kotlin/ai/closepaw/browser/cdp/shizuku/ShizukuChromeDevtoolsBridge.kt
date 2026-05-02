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
    private val hostMediatedRelayTransport: DevtoolsSocketTransport? = null,
    private val chromeTcpLoopbackTransport: DevtoolsSocketTransport? = null,
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
        require(hostMediatedRelayTransport == null ||
                hostMediatedRelayTransport.label == TransportLabel.HOST_MEDIATED_RELAY) {
            "hostMediatedRelayTransport must declare TransportLabel.HOST_MEDIATED_RELAY"
        }
    }

    /**
     * Tracks which transport satisfied the most recent successful HTTP fetch so
     * [resolveWebSocketHost] can pick the right WS URL transformation.
     *
     * - APP_PROCESS / CHROME_TCP_LOOPBACK / HOST_MEDIATED_RELAY: the Host header that the
     *   inbound connection presents matches `127.0.0.1:<port>`, and Chrome reflects that
     *   into `webSocketDebuggerUrl`. So the URL Chrome returns is already correct — no
     *   rewrite needed.
     * - USER_SERVICE: the abstract socket has no port concept, so Chrome emits a port-less
     *   `ws://localhost/devtools/...`. We must rewrite that onto the device-side TCP relay
     *   the UserService starts on demand.
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
     * payload. The bridge always builds requests with `Host: localhost`, so any transport
     * that does NOT itself terminate on a port Chrome can name back to us yields a port-less
     * `ws://localhost/devtools/...` (which OkHttp then defaults to port 80, unreachable).
     * The two affected transports are:
     *
     *  - [TransportLabel.USER_SERVICE]: the abstract Unix socket has no TCP port at all;
     *    we rewrite onto `127.0.0.1:<relayPort>` exposed by the device-side TCP relay the
     *    UserService starts on demand.
     *  - [TransportLabel.HOST_MEDIATED_RELAY]: TCP loopback through `adb forward`+`adb reverse`,
     *    so we rewrite onto the same `127.0.0.1:<resolvedPort>` the transport probed.
     *
     * For [TransportLabel.APP_PROCESS] and [TransportLabel.CHROME_TCP_LOOPBACK] this returns
     * null and the caller uses Chrome's URL as-is.
     */
    suspend fun resolveWebSocketHost(): String? = withContext(ioDispatcher) {
        when (lastSuccessfulTransport) {
            TransportLabel.USER_SERVICE -> {
                val provider = userServiceProvider ?: return@withContext null
                val transport = provider.obtain() as? UserServiceTransport
                    ?: return@withContext null
                val port = transport.ensureRelayPortSuspend()
                "127.0.0.1:$port"
            }
            TransportLabel.HOST_MEDIATED_RELAY -> {
                val transport = hostMediatedRelayTransport as? HostMediatedCdpRelayTransport
                    ?: return@withContext null
                transport.resolvePort()?.let { "127.0.0.1:$it" }
            }
            else -> null
        }
    }

    /** Release any Shizuku UserService binding owned by this bridge. */
    fun close() {
        userServiceProvider?.close()
    }

    private suspend fun httpGet(path: String): String {
        runPreflight()
        val request = DevtoolsHttpProtocol.buildGet(path)

        // 1. App-process LocalSocket — works only when SELinux happens to allow the app UID
        //    to connect to Chrome's abstract socket (rare; depends on category overlap).
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

        // 2. Shizuku UserService — works on AOSP/userdebug + properly-bootstrapped Shizuku.
        //    Some OEM builds (e.g. nubia P0110) deny shell-domain connectto on appdomain
        //    abstract sockets, so the call returns IOException("Permission denied").
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

        // 3. Host-mediated relay — when the user has run scripts/setup-cdp-relay.sh, ADB has
        //    forward+reverse tunnels chaining device 127.0.0.1:<port> back through host adbd
        //    into Chrome's abstract socket. Works on any device that can attach to a host
        //    over ADB, including the locked OEM builds where (2) is blocked.
        val relay = hostMediatedRelayTransport
        var relayUnreachable: HostMediatedRelayUnreachableException? = null
        if (relay != null) {
            val relayResponse = runCatching {
                DevtoolsHttpProtocol.parseHttpBody(
                    relay.exchange(request, requestTimeoutMs)
                )
            }
            relayResponse.getOrNull()?.let {
                lastSuccessfulTransport = TransportLabel.HOST_MEDIATED_RELAY
                return it
            }
            // Track unreachable separately from MalformedResponse / DevtoolsSetupError so the
            // bridge can choose between "relay isn't running" and "relay returned junk".
            when (val e = relayResponse.exceptionOrNull()) {
                is HostMediatedRelayUnreachableException -> relayUnreachable = e
                is DevtoolsSetupError -> throw e
                else -> Unit
            }
        }

        // 4. Phase 2 chrome --remote-debugging-port=N path. Empirically broken on stock
        //    Chrome (per-profile chrome://flags toggle is required and cannot be set
        //    programmatically); kept wired only for hosts that opt in by passing the
        //    transport explicitly. Production wiring leaves this null.
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
            (chromeResponse.exceptionOrNull()
                as? DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled)
                ?.let { throw it }
        }

        if (provider == null) {
            // No Shizuku, no working relay, nothing else to try. The most actionable error
            // is "set up Shizuku" — the relay is the alternative when Shizuku is denied,
            // not when it is missing entirely.
            if (appError is DevtoolsSetupError) throw appError
            if (relay != null) {
                throw DevtoolsSetupError.HostMediatedRelayUnreachable(relayUnreachable ?: appError)
            }
            throw DevtoolsSetupError.AppProcessSocketInaccessible(appError)
        }

        // Shizuku was tried and failed; the relay either wasn't wired or its probe came up
        // empty. Surface the relay error since that is the user-actionable one.
        if (relay != null) {
            throw DevtoolsSetupError.HostMediatedRelayUnreachable(relayUnreachable ?: userError)
        }
        throw toUserServiceError(userError)
    }

    private fun toUserServiceError(error: Throwable?): DevtoolsSetupError.UserServiceSocketInaccessible {
        return when (error) {
            is DevtoolsSetupError.UserServiceSocketInaccessible -> error
            is DevtoolsSetupError -> throw error
            else -> DevtoolsSetupError.UserServiceSocketInaccessible(error)
        }
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

        // At least one transport path must be available. Shizuku is no longer mandatory: the
        // host-mediated relay works without it on devices whose SELinux denies the shell
        // domain connectto. We probe each path and only fail closed if NONE are usable.
        val shizukuPathOk = userServiceProvider != null &&
            status.isAvailable() && status.hasPermission()
        val relayReachable = hostMediatedRelayTransport?.isReachable() == true

        if (shizukuPathOk || relayReachable) return

        // Choose the most actionable error to surface back to the gate.
        if (hostMediatedRelayTransport != null) {
            // Relay is wired but unreachable — actionable: run setup-cdp-relay.sh.
            throw DevtoolsSetupError.HostMediatedRelayUnreachable(null)
        }
        if (userServiceProvider != null && !status.isAvailable()) {
            throw DevtoolsSetupError.ShizukuUnavailable
        }
        if (userServiceProvider != null && !status.hasPermission()) {
            throw DevtoolsSetupError.ShizukuPermissionMissing
        }
        // No relay, no Shizuku — only the app-process LocalSocket is left, and its viability
        // is decided at the actual exchange call (we cannot probe it cheaply without an
        // unbalanced socket connect that itself can hang on some builds).
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
    /**
     * Cheap probe used by the bridge during preflight to decide whether to even consult
     * this transport. Default `true` keeps existing transports — those whose reachability
     * is determined by [exchange] itself — backwards-compatible. The host-mediated relay
     * overrides this with a fast TCP connect probe so the gate can fail closed early when
     * the user has not run `scripts/setup-cdp-relay.sh`.
     */
    suspend fun isReachable(): Boolean = true
}

enum class TransportLabel { APP_PROCESS, USER_SERVICE, HOST_MEDIATED_RELAY, CHROME_TCP_LOOPBACK }

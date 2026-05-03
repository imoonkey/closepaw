package ai.closepaw.browser.cdp.shizuku

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Adapter wrapping an [IChromeDevtoolsUserService] binder as a [DevtoolsSocketTransport].
 * Binder calls are not coroutine-cancellable from the client side — [runInterruptible] gives a
 * best-effort by interrupting the IO thread carrying the binder transaction. The remote socket
 * also enforces `soTimeout` inside [ChromeDevtoolsUserService.exchange] so a wedged remote
 * eventually unwinds even without cancellation.
 */
class UserServiceTransport(
    // Public so [AdbWirelessManager] can share the same Shizuku binder for IAdbManager calls
    // without spawning a second user service.
    val binder: IChromeDevtoolsUserService,
) : DevtoolsSocketTransport {

    override val label: TransportLabel = TransportLabel.USER_SERVICE

    /**
     * Lazily start the device-side TCP relay (one process == one relay) and return its
     * 127.0.0.1 port. Required because Chrome's `webSocketDebuggerUrl` has no port (defaults to
     * 80, unreachable from the app UID), so the WebSocket has to tunnel through the relay.
     */
    suspend fun ensureRelayPortSuspend(): Int = runInterruptible(Dispatchers.IO) {
        val port = binder.startTcpRelay()
        if (port <= 0) throw IOException("UserService.startTcpRelay returned invalid port=$port")
        port
    }

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            val response: ByteArray? = binder.exchange(request, timeoutMs)
            response ?: throw IOException(
                "UserService exchange returned null; check logcat for the remote exception"
            )
        }
}

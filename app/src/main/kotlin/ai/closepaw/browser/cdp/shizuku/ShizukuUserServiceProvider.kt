package ai.closepaw.browser.cdp.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Owns the lifecycle of the Shizuku-backed [DevtoolsSocketTransport]. The bridge calls
 * [obtain] lazily on the first httpGet so we don't spawn a Shizuku helper process before
 * `browser_script` is actually invoked.
 */
interface UserServiceProvider {
    /** Lazily produce a transport. Idempotent: subsequent calls return the same instance. */
    suspend fun obtain(): DevtoolsSocketTransport

    /** Tear down any binding owned by this provider. Safe to call multiple times. */
    fun close()
}

/**
 * Real Shizuku-backed [UserServiceProvider]. Binds [ChromeDevtoolsUserService] through
 * `Shizuku.bindUserService` and wraps the resulting binder as a [UserServiceTransport].
 *
 * Lifecycle hardening (review HIGH rounds 3–5):
 *
 * - [obtain] guards each caller's wait with [withTimeout] so a Shizuku helper that never
 *   delivers a binder cannot suspend the caller forever. Default [DEFAULT_BIND_TIMEOUT_MS]
 *   is 10s.
 * - **Single-flight bind.** Concurrent [obtain] callers share a single in-flight bind
 *   cycle via a [CompletableDeferred]. Without this, a second [obtain] would overwrite
 *   the first cycle's [ServiceConnection] and leak it (only the latest connection can be
 *   unbound by a callback or by [close]). With single-flight there is at most one
 *   outstanding `ServiceConnection` per provider.
 * - **Refcounted teardown.** Each awaiter is counted; when the last awaiter cancels or
 *   times out before the bind completes, the cycle is torn down (unbind + drop the
 *   shared deferred). This bounds the helper process's lifetime to "at least one caller
 *   wants it" without requiring a separate bind-cycle timer.
 * - The internal [ServiceConnection] resumes the shared deferred with
 *   [DevtoolsSetupError.UserServiceSocketInaccessible] on every failure callback —
 *   [ServiceConnection.onServiceDisconnected] before connect, [ServiceConnection.onNullBinding],
 *   and [ServiceConnection.onBindingDied] — instead of silently waiting for an
 *   onServiceConnected that never comes. Every terminal failure callback also calls
 *   [Binder.unbind] before propagating the error so a failed bind never leaks the helper
 *   process.
 * - Each bind cycle is tagged with a monotonic [bindGeneration]. Every callback verifies
 *   its generation matches the current one (and that the provider is not [closed]) before
 *   mutating state. Stale callbacks from prior bind cycles — those that fire after a
 *   timeout, [close], or an earlier failure callback — are silently ignored. This prevents
 *   a delayed framework callback from corrupting state or resuming a newer pending
 *   [obtain].
 * - The success path (`onServiceConnected`) atomically transitions the cycle out of
 *   in-flight before the deferred completes. This forecloses on the race where a
 *   cancellation arriving after delivery could unbind the now-active service while
 *   leaving [transport] cached: there is no in-flight cycle for the cancellation handler
 *   to tear down.
 * - [close] is idempotent and resumes a pending bind with `UserServiceSocketInaccessible`
 *   instead of leaking the deferred. All state transitions are guarded by an internal
 *   monitor.
 *
 * The [Binder] indirection lets unit tests drive the bind/unbind/callback flow without a real
 * Shizuku binder; production calls go through [ShizukuBinder].
 */
class ShizukuUserServiceProvider internal constructor(
    private val binder: Binder,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) : UserServiceProvider, Closeable {

    constructor(
        context: Context,
        versionCode: Int = USER_SERVICE_VERSION,
        processNameSuffix: String = DEFAULT_PROCESS_SUFFIX,
        debuggable: Boolean = false,
        bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    ) : this(
        binder = ShizukuBinder(context, versionCode, processNameSuffix, debuggable),
        bindTimeoutMs = bindTimeoutMs,
    )

    private val lock = Any()

    /** Active [ServiceConnection] across both phases (in-flight and delivered). */
    private var connection: ServiceConnection? = null

    /** Cached transport once `onServiceConnected` has delivered the binder. */
    private var transport: DevtoolsSocketTransport? = null

    /** Single in-flight bind cycle. Null when no bind is in progress. */
    private var inflight: InflightBind? = null

    /** Number of [obtain] callers awaiting the current [inflight]. */
    private var inflightAwaiters: Int = 0

    /**
     * Monotonically increases on every new bind attempt and on every terminal event
     * (failure callback, refcount-zero teardown, [close]). A captured value identifies a
     * single bind cycle: callbacks that no longer match the current generation are stale.
     */
    private var bindGeneration: Long = 0L
    private var closed = false

    override suspend fun obtain(): DevtoolsSocketTransport {
        val (flight, isOwner) = synchronized(lock) {
            transport?.let { return it }
            if (closed) throw closedError()
            val existing = inflight
            if (existing != null) {
                inflightAwaiters++
                existing to false
            } else {
                val gen = ++bindGeneration
                val d = CompletableDeferred<DevtoolsSocketTransport>()
                val newFlight = InflightBind(d)
                val c = createConnection(gen)
                connection = c
                inflight = newFlight
                inflightAwaiters = 1
                newFlight to true
            }
        }
        if (isOwner) startBind(flight)
        return awaitBind(flight)
    }

    private fun startBind(flight: InflightBind) {
        val conn = synchronized(lock) { connection } ?: return
        try {
            binder.bind(conn)
        } catch (t: Throwable) {
            // Synchronous bind failure: tear down state and fail the shared deferred so
            // every awaiter wakes up with the same error. No unbind — bind() never took.
            val ours = synchronized(lock) {
                if (inflight === flight) {
                    inflight = null
                    inflightAwaiters = 0
                    connection = null
                    bindGeneration++
                    true
                } else {
                    false
                }
            }
            if (ours) {
                flight.deferred.completeExceptionally(
                    DevtoolsSetupError.UserServiceSocketInaccessible(t)
                )
            }
        }
    }

    private suspend fun awaitBind(flight: InflightBind): DevtoolsSocketTransport {
        return try {
            try {
                withTimeout(bindTimeoutMs) { flight.deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw DevtoolsSetupError.UserServiceSocketInaccessible(
                    IOException("Shizuku UserService bind timed out after ${bindTimeoutMs}ms", e)
                )
            }
        } finally {
            // releaseAwaiter is a no-op when [flight] is no longer the current inflight
            // (success path cleared it, or a failure callback / close already tore it down),
            // so it is safe to call on every exit path including normal completion.
            releaseAwaiter(flight)
        }
    }

    /**
     * Decrement the awaiter count for [flight]. If we are the last awaiter for an
     * in-flight bind cycle, tear it down: clear inflight, bump generation, unbind.
     * No-op when [flight] is no longer the current inflight (already completed or
     * torn down by a callback / close).
     */
    private fun releaseAwaiter(flight: InflightBind) {
        val (shouldUnbind, conn) = synchronized(lock) {
            if (inflight !== flight) return
            inflightAwaiters--
            if (inflightAwaiters > 0) return
            inflight = null
            val c = connection
            connection = null
            bindGeneration++
            true to c
        }
        if (shouldUnbind) {
            // Complete the deferred so any in-flight callback that races with us wakes
            // up cleanly (idempotent — onServiceConnected/handleFailure may have already
            // completed it). The IOException records why the bind was abandoned.
            flight.deferred.completeExceptionally(
                DevtoolsSetupError.UserServiceSocketInaccessible(
                    IOException("Shizuku UserService bind abandoned by all callers")
                )
            )
            if (conn != null) runCatching { binder.unbind(conn, true) }
        }
    }

    private fun createConnection(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                handleFailure(
                    generation,
                    IllegalStateException("UserService connected with null binder"),
                )
                return
            }
            val tr = UserServiceTransport(IChromeDevtoolsUserService.Stub.asInterface(service))
            val deferred = synchronized(lock) {
                if (closed || bindGeneration != generation) return
                transport = tr
                val flight = inflight
                inflight = null
                inflightAwaiters = 0
                // Do NOT bump bindGeneration: a future onServiceDisconnected for this
                // same connection should still be allowed to clear `transport` so a
                // dead-after-delivery service doesn't masquerade as live.
                flight?.deferred
            } ?: return
            deferred.complete(tr)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleFailure(
                generation,
                IOException("UserService disconnected before binder was delivered"),
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            handleFailure(
                generation,
                IOException("UserService binding died before binder was delivered"),
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            handleFailure(
                generation,
                IllegalStateException("UserService.onBind returned null"),
            )
        }
    }

    /**
     * Process a terminal failure for a specific bind cycle. Stale callbacks (those whose
     * generation no longer matches, or that arrive after [close]) are silently dropped so
     * they cannot corrupt state or resume a newer pending [obtain]. For current-generation
     * callbacks: clear connection state, bump generation to invalidate any subsequent
     * callbacks for the same cycle, release the binding via [Binder.unbind], and complete
     * the shared deferred with the error so every awaiter wakes up.
     *
     * Also handles the post-delivery service-death case (where [inflight] is already null
     * but [connection] still references the now-dead binding): clears [transport] so a
     * future [obtain] starts a fresh cycle.
     */
    private fun handleFailure(generation: Long, cause: Throwable) {
        val (flight, conn) = synchronized(lock) {
            if (closed || bindGeneration != generation) return
            val f = inflight
            val c = connection
            inflight = null
            inflightAwaiters = 0
            connection = null
            transport = null
            bindGeneration++
            f to c
        }
        if (conn != null) runCatching { binder.unbind(conn, true) }
        flight?.deferred?.completeExceptionally(
            DevtoolsSetupError.UserServiceSocketInaccessible(cause)
        )
    }

    override fun close() {
        val (flight, conn) = synchronized(lock) {
            if (closed) return
            closed = true
            bindGeneration++
            val f = inflight
            val c = connection
            inflight = null
            inflightAwaiters = 0
            connection = null
            transport = null
            f to c
        }
        flight?.deferred?.completeExceptionally(
            DevtoolsSetupError.UserServiceSocketInaccessible(
                IOException("UserService provider closed while bind was pending")
            )
        )
        if (conn != null) runCatching { binder.unbind(conn, true) }
    }

    private fun closedError(): DevtoolsSetupError =
        DevtoolsSetupError.UserServiceSocketInaccessible(
            IllegalStateException("ShizukuUserServiceProvider is closed")
        )

    /** Indirection so tests don't depend on the real Shizuku static. */
    internal interface Binder {
        fun bind(conn: ServiceConnection)
        fun unbind(conn: ServiceConnection, remove: Boolean)
    }

    private class InflightBind(
        val deferred: CompletableDeferred<DevtoolsSocketTransport>,
    )

    companion object {
        // Bump whenever IChromeDevtoolsUserService.aidl changes shape. Shizuku keys cached
        // user-service processes on (ComponentName, version), so an unchanged version + a
        // changed AIDL would let the new client transact against an old stub via shifted
        // transaction IDs — silently calling the wrong method. v4 adds the authToken
        // parameter to startTcpRelay; an upgraded client hitting a cached v3 stub would
        // bypass token gating entirely. v5 adds adbKeysReadStatus() for the pair-once cache
        // EACCES-vs-missing distinction; without the bump, a v4 stub would 404-style fail
        // the new transaction and the cache would silently degrade.
        const val USER_SERVICE_VERSION = 5
        const val DEFAULT_PROCESS_SUFFIX = "chrome_devtools"
        const val DEFAULT_BIND_TIMEOUT_MS = 10_000L
    }
}

private class ShizukuBinder(
    context: Context,
    versionCode: Int,
    processNameSuffix: String,
    debuggable: Boolean,
) : ShizukuUserServiceProvider.Binder {

    private val args: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context, ChromeDevtoolsUserService::class.java)
    )
        .daemon(false)
        .processNameSuffix(processNameSuffix)
        .debuggable(debuggable)
        .version(versionCode)

    override fun bind(conn: ServiceConnection) {
        Shizuku.bindUserService(args, conn)
    }

    override fun unbind(conn: ServiceConnection, remove: Boolean) {
        Shizuku.unbindUserService(args, conn, remove)
    }
}

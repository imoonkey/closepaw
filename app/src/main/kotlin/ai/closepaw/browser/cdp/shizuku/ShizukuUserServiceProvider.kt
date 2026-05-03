package ai.closepaw.browser.cdp.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * Lifecycle hardening (review HIGH round 3 + round 4):
 *
 * - [obtain] guards the bind with [withTimeout] so a Shizuku helper that never delivers a
 *   binder cannot suspend the caller forever. Default [DEFAULT_BIND_TIMEOUT_MS] is 10s.
 * - The internal [ServiceConnection] resumes the pending continuation with
 *   [DevtoolsSetupError.UserServiceSocketInaccessible] on every failure callback —
 *   [ServiceConnection.onServiceDisconnected] before connect, [ServiceConnection.onNullBinding],
 *   and [ServiceConnection.onBindingDied] — instead of silently waiting for an
 *   onServiceConnected that never comes.
 * - Each bind cycle is tagged with a monotonic [bindGeneration]. Every callback verifies its
 *   generation matches the current one (and that the provider is not [closed]) before
 *   mutating state. Stale callbacks from prior bind cycles — those that fire after a
 *   timeout, [close], or an earlier failure callback — are silently ignored. This prevents
 *   a delayed framework callback from corrupting state or resuming a newer pending
 *   [obtain].
 * - Every terminal failure callback (disconnect-before-connect, onNullBinding,
 *   onBindingDied) clears connection state AND calls [Binder.unbind] before propagating
 *   the error. Without this, a failed bind would leak the helper process until the
 *   provider was closed.
 * - [close] is idempotent and resumes a pending bind with `UserServiceSocketInaccessible`
 *   instead of leaking the continuation. All state transitions are guarded by an internal
 *   monitor so concurrent obtain/close calls never resume a continuation twice.
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
    private var connection: ServiceConnection? = null
    private var transport: DevtoolsSocketTransport? = null
    private var pendingCont: CancellableContinuation<DevtoolsSocketTransport>? = null

    /**
     * Monotonically increases on every new bind attempt and on every terminal event
     * (failure callback, cancellation, [close]). A captured value identifies a single
     * bind cycle: callbacks that no longer match the current generation are stale.
     */
    private var bindGeneration: Long = 0L
    private var closed = false

    override suspend fun obtain(): DevtoolsSocketTransport {
        synchronized(lock) {
            transport?.let { return it }
            if (closed) throw closedError()
        }
        return try {
            withTimeout(bindTimeoutMs) { bindAndAwait() }
        } catch (e: DevtoolsSetupError) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw DevtoolsSetupError.UserServiceSocketInaccessible(
                IOException("Shizuku UserService bind timed out after ${bindTimeoutMs}ms", e)
            )
        }
    }

    private suspend fun bindAndAwait(): DevtoolsSocketTransport =
        suspendCancellableCoroutine { cont ->
            var capturedConn: ServiceConnection? = null
            var capturedGen: Long = 0L
            val rejected = synchronized(lock) {
                if (closed) {
                    true
                } else {
                    val gen = ++bindGeneration
                    val c = createConnection(gen)
                    capturedConn = c
                    capturedGen = gen
                    connection = c
                    pendingCont = cont
                    false
                }
            }
            if (rejected) {
                cont.resumeWithException(closedError())
                return@suspendCancellableCoroutine
            }
            val conn = capturedConn!!
            val gen = capturedGen
            cont.invokeOnCancellation {
                val shouldUnbind = synchronized(lock) {
                    if (bindGeneration == gen) {
                        if (pendingCont === cont) pendingCont = null
                        connection = null
                        bindGeneration++ // invalidate any in-flight stale callbacks
                        true
                    } else {
                        false
                    }
                }
                if (shouldUnbind) runCatching { binder.unbind(conn, true) }
            }
            try {
                binder.bind(conn)
            } catch (t: Throwable) {
                val ours = synchronized(lock) {
                    if (bindGeneration == gen && pendingCont === cont) {
                        pendingCont = null
                        connection = null
                        bindGeneration++
                        true
                    } else {
                        false
                    }
                }
                if (ours && cont.isActive) {
                    cont.resumeWithException(
                        DevtoolsSetupError.UserServiceSocketInaccessible(t)
                    )
                }
            }
        }

    private fun createConnection(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                handleFailure(
                    generation, this,
                    IllegalStateException("UserService connected with null binder"),
                )
                return
            }
            val tr = UserServiceTransport(IChromeDevtoolsUserService.Stub.asInterface(service))
            val cont = synchronized(lock) {
                if (closed || bindGeneration != generation) return
                transport = tr
                val c = pendingCont
                pendingCont = null
                c
            } ?: return
            if (cont.isActive) cont.resume(tr)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleFailure(
                generation, this,
                IOException("UserService disconnected before binder was delivered"),
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            handleFailure(
                generation, this,
                IOException("UserService binding died before binder was delivered"),
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            handleFailure(
                generation, this,
                IllegalStateException("UserService.onBind returned null"),
            )
        }
    }

    /**
     * Process a terminal failure for a specific bind cycle. Stale callbacks (those whose
     * generation no longer matches, or that arrive after [close]) are silently dropped so
     * they cannot corrupt state or resume a newer pending [obtain]. For current-generation
     * callbacks: clear connection state, bump generation to invalidate any subsequent
     * callbacks for the same cycle, release the binding via [Binder.unbind], and resume
     * any pending continuation with the error.
     */
    private fun handleFailure(generation: Long, conn: ServiceConnection, cause: Throwable) {
        val cont = synchronized(lock) {
            if (closed || bindGeneration != generation) return
            val c = pendingCont
            pendingCont = null
            connection = null
            transport = null
            bindGeneration++
            c
        }
        runCatching { binder.unbind(conn, true) }
        if (cont != null && cont.isActive) {
            cont.resumeWithException(
                DevtoolsSetupError.UserServiceSocketInaccessible(cause)
            )
        }
    }

    override fun close() {
        val (conn, cont) = synchronized(lock) {
            if (closed) return
            closed = true
            bindGeneration++ // invalidate any in-flight callbacks
            val c = connection
            val p = pendingCont
            connection = null
            pendingCont = null
            transport = null
            c to p
        }
        if (cont != null && cont.isActive) {
            cont.resumeWithException(
                DevtoolsSetupError.UserServiceSocketInaccessible(
                    IOException("UserService provider closed while bind was pending")
                )
            )
        }
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

    companion object {
        // Bump whenever IChromeDevtoolsUserService.aidl changes shape. Shizuku keys cached
        // user-service processes on (ComponentName, version), so an unchanged version + a
        // changed AIDL would let the new client transact against an old stub via shifted
        // transaction IDs — silently calling the wrong method.
        const val USER_SERVICE_VERSION = 2
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

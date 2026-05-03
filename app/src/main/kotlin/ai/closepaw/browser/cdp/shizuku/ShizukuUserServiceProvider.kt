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
 * Lifecycle hardening (review HIGH round 3):
 *
 * - [obtain] guards the bind with [withTimeout] so a Shizuku helper that never delivers a
 *   binder cannot suspend the caller forever. Default [DEFAULT_BIND_TIMEOUT_MS] is 10s.
 * - The internal [ServiceConnection] resumes the pending continuation with
 *   [DevtoolsSetupError.UserServiceSocketInaccessible] on every failure callback —
 *   [ServiceConnection.onServiceDisconnected] before connect, [ServiceConnection.onNullBinding],
 *   and [ServiceConnection.onBindingDied] — instead of silently waiting for an
 *   onServiceConnected that never comes.
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
            val conn = createConnection()
            val rejected = synchronized(lock) {
                if (closed) {
                    true
                } else {
                    connection = conn
                    pendingCont = cont
                    false
                }
            }
            if (rejected) {
                cont.resumeWithException(closedError())
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                synchronized(lock) {
                    if (pendingCont === cont) pendingCont = null
                }
                runCatching { binder.unbind(conn, true) }
            }
            try {
                binder.bind(conn)
            } catch (t: Throwable) {
                val ours = synchronized(lock) {
                    val mine = pendingCont === cont
                    if (mine) {
                        pendingCont = null
                        connection = null
                    }
                    mine
                }
                if (ours && cont.isActive) {
                    cont.resumeWithException(
                        DevtoolsSetupError.UserServiceSocketInaccessible(t)
                    )
                }
            }
        }

    private fun createConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                completeWithError(IllegalStateException("UserService connected with null binder"))
                return
            }
            val tr = UserServiceTransport(IChromeDevtoolsUserService.Stub.asInterface(service))
            val cont = synchronized(lock) {
                transport = tr
                val c = pendingCont
                pendingCont = null
                c
            } ?: return
            if (cont.isActive) cont.resume(tr)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { transport = null }
            completeWithError(IOException("UserService disconnected before binder was delivered"))
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { transport = null }
            completeWithError(IOException("UserService binding died before binder was delivered"))
        }

        override fun onNullBinding(name: ComponentName?) {
            completeWithError(IllegalStateException("UserService.onBind returned null"))
        }
    }

    private fun completeWithError(cause: Throwable) {
        val cont = synchronized(lock) {
            val c = pendingCont
            pendingCont = null
            c
        } ?: return
        if (cont.isActive) cont.resumeWithException(
            DevtoolsSetupError.UserServiceSocketInaccessible(cause)
        )
    }

    override fun close() {
        val (conn, cont) = synchronized(lock) {
            if (closed) return
            closed = true
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

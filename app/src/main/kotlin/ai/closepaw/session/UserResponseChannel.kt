package ai.closepaw.session

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * UserResponseChannel — suspension bridge between ask_user tool and UI.
 *
 * The ask_user tool suspends on [awaitResponse]. The session delivers the
 * user's answer via [deliver] when Op.UserResponse arrives. Only one
 * pending request is allowed at a time.
 *
 * ## Timeout vs Cancellation
 *
 * Two exit paths exist:
 * - **Timeout**: `AskUserInvocation` wraps `awaitResponse` in `withTimeoutOrNull`.
 *   On timeout, the coroutine returns `null`, the `finally` block clears pending
 *   state, and the tool returns a "timed out" success result.
 * - **Cancellation**: [cancel] is called on stop/shutdown. It cancels the deferred,
 *   which throws `CancellationException` in the awaiting coroutine. The `finally`
 *   block clears state. `AskUserInvocation` catches this and returns `Cancelled`.
 */
class UserResponseChannel {

    private data class PendingRequest(
        val callId: String,
        val deferred: CompletableDeferred<String>
    )

    private val pending = AtomicReference<PendingRequest?>(null)

    /**
     * Suspend until the user responds. Called by the ask_user tool.
     *
     * @throws IllegalStateException if another request is already pending
     * @throws kotlinx.coroutines.CancellationException if cancelled (stop/timeout)
     */
    suspend fun awaitResponse(callId: String): String {
        val deferred = CompletableDeferred<String>()
        val request = PendingRequest(callId = callId, deferred = deferred)
        check(pending.compareAndSet(null, request)) { "Only one pending ask_user request allowed" }
        return try {
            deferred.await()
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    /**
     * Deliver the user's response. Called by AgentSession on Op.UserResponse.
     *
     * @return true if delivered, false if no matching pending request.
     */
    fun deliver(callId: String, response: String): Boolean {
        val request = pending.get() ?: return false
        if (request.callId != callId) return false
        if (!pending.compareAndSet(request, null)) return false
        return request.deferred.complete(response)
    }

    /** Cancel any pending request (called on stop/shutdown). */
    fun cancel() {
        pending.getAndSet(null)?.deferred?.cancel()
    }

    val hasPending: Boolean get() = pending.get() != null
}

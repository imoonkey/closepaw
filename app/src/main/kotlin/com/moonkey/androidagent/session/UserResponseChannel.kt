package com.moonkey.androidagent.session

import kotlinx.coroutines.CompletableDeferred

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

    @Volatile private var pending: CompletableDeferred<String>? = null
    @Volatile private var pendingCallId: String? = null

    /**
     * Suspend until the user responds. Called by the ask_user tool.
     *
     * @throws IllegalStateException if another request is already pending
     * @throws kotlinx.coroutines.CancellationException if cancelled (stop/timeout)
     */
    suspend fun awaitResponse(callId: String): String {
        check(pending == null) { "Only one pending ask_user request allowed" }
        val deferred = CompletableDeferred<String>()
        pending = deferred
        pendingCallId = callId
        return try {
            deferred.await()
        } finally {
            pending = null
            pendingCallId = null
        }
    }

    /**
     * Deliver the user's response. Called by AgentSession on Op.UserResponse.
     *
     * @return true if delivered, false if no matching pending request.
     */
    fun deliver(callId: String, response: String): Boolean {
        val p = pending ?: return false
        if (pendingCallId != callId) return false
        pending = null
        pendingCallId = null
        return p.complete(response)
    }

    /** Cancel any pending request (called on stop/shutdown). */
    fun cancel() {
        pending?.cancel()
        pending = null
        pendingCallId = null
    }

    val hasPending: Boolean get() = pending != null
}

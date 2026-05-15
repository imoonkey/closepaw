package ai.closepaw.protocol

/**
 * Op - Operations sent from the UI layer to the agent session.
 * 
 * This defines the "Submission Queue" (SQ) in the Codex pattern.
 * All user intents are expressed as operations submitted to the session.
 * 
 * Operations are:
 * - Immutable data classes
 * - Thread-safe to create and pass around
 * - Processed asynchronously by the session
 */
sealed interface Op {

    // ===== Session Lifecycle =====

    /**
     * User takes control of the device.
     *
     * The agent finishes its current action, then enters Paused state.
     * The capsule transitions through TakeoverPending → Takeover.
     *
     * Valid in: Running state
     * Transitions to: Paused state
     */
    data object Takeover : Op

    /**
     * Resume from takeover (user returns control to agent).
     *
     * Valid in: Paused state
     * Transitions to: Running state
     */
    data object Resume : Op

    /**
     * Interrupt the current turn.
     *
     * Cooperative — the agent completes its current action before stopping.
     *
     * Valid in: Running state
     * Stays in: Running state (ready for next turn)
     */
    data object Interrupt : Op

    /**
     * Shutdown the session gracefully.
     *
     * Valid in: Any state
     * Transitions to: Shutdown state
     */
    data object Shutdown : Op

    // ===== User Interaction =====

    /**
     * User provides input to the agent.
     *
     * - If session is idle/created, starts a new Task.
     * - If session is running, rejects (agent is busy).
     *
     * This is the primary way to interact with the agent.
     */
    data class UserInput(
        val text: String
    ) : Op

    /**
     * User injects a mid-task message into the agent's conversation history.
     *
     * The agent sees this message on its next turn alongside perception data.
     * Does not interrupt the current turn — the supplement is passive.
     *
     * Valid in: Running or Paused state
     */
    data class Supplement(
        val text: String
    ) : Op

    /**
     * User responds to an ask_user request (question answer or action completion).
     */
    data class UserResponse(
        val callId: String,
        val response: String
    ) : Op

    /**
     * User responds to an approval request.
     */
    data class Approve(
        /** ID of the action being approved/denied */
        val actionId: String,

        /** User's decision */
        val decision: ApprovalDecision,

        /** Lifetime of the app-level allow decision. Ignored for rejections. */
        val scope: ApprovalScope = ApprovalScope.SESSION,

        /** Package name that owns the app-level approval. */
        val packageName: String
    ) : Op
}

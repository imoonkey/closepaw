package ai.closepaw.protocol

import org.json.JSONObject

/**
 * ApprovalScope - Lifetime of a user's app-level allow decision.
 *
 * SESSION: Allow all actions for this package for the rest of the session.
 * ALWAYS: Persist the allow-list entry across sessions (SharedPreferences).
 */
enum class ApprovalScope { SESSION, ALWAYS }

/**
 * ApprovalDecision - User's response to an approval request.
 */
enum class ApprovalDecision {
    /** User approved the action */
    APPROVED,

    /** User denied the action (skip this action, continue session) */
    DENIED,

    /** User wants to abort the entire session */
    ABORT
}

/**
 * ApprovalDetails - Information about what is being approved.
 */
data class ApprovalDetails(
    /** Unique ID for this approval request (from ToolRouter) */
    val callId: String,

    /** Name of the tool being invoked */
    val toolName: String,

    /** Arguments passed to the tool */
    val args: JSONObject,

    /** Human-readable description of the pending action, for logs and traces. */
    val description: String = "",

    /** Package that owns the app-level approval. */
    val packageName: String,

    /** Effective security tier that triggered the approval. */
    val appTier: AppTier? = null,

    /** Reason for requiring approval */
    val reason: String = ""
)

package ai.closepaw.ui.chat.model

import ai.closepaw.protocol.CompletionHandoff

/**
 * ChatMessage - UI data classes for the chat conversation.
 *
 * These classes represent the visual representation of messages in the chat interface.
 * They map from AgentEvents emitted by the session to UI-friendly data structures.
 */
sealed interface ChatMessage {
    val id: String
    val timestamp: Long

    /**
     * User message bubble.
     */
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage

    /**
     * Agent message bubble with streaming support and interleaved content.
     *
     * Content blocks allow text and actions to be displayed in the order they occurred,
     * supporting the natural flow: "I'll click this" → [Action Card] → "Now I see..."
     */
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val contentBlocks: List<ContentBlock>,
        val state: AgentMessageState,
        val rowState: RowState = RowState.Live,
        /**
         * User prompt that opened this turn — drives the collapsed-row headline
         * per Track A spec §5.2 (top of the ladder). Null when restored from
         * historical records or for synthetic agent rows (startup errors).
         */
        val userPrompt: String? = null,
        /**
         * Wall-clock when the row sealed (TaskCompleted / SessionError / supplement).
         * Null while Live or Waiting. Used by the collapsed/footer summary to
         * show elapsed wall time per Track A spec §4.5/§5.2.
         */
        val completedTimestamp: Long? = null,
        /**
         * VD completion handoff metadata — populated only when [TaskCompleted]
         * carries one (i.e. the row finished on the Virtual Display). Drives
         * the explicit "Open <App>" / "View virtual screen" CTAs in the final
         * region. Null for accessibility completions and historical rows.
         */
        val handoff: CompletionHandoff? = null,
    ) : ChatMessage
}

/**
 * RowState — explicit row-level state machine (Track A spec §5).
 *
 * Independent of [AgentMessageState] (which tracks streaming lifecycle).
 * Drives the collapse/expand UX and locked-open invariants.
 */
enum class RowState {
    /** Task in progress — auto-tracking trace, no collapse. */
    Live,

    /** Awaiting user reply (AskUser/Approval) — locked open. */
    Waiting,

    /** Task finished — collapsible (default collapsed). */
    Complete,

    /** Task errored — locked open until acknowledged, then collapsible. */
    Error
}

/**
 * AgentMessageState - The visual state of an agent message.
 */
enum class AgentMessageState {
    /** Agent is processing before first delta (shows thinking indicator) */
    Thinking,
    
    /** Agent is streaming text (shows blinking cursor) */
    Streaming,
    
    /** Message is complete (no cursor) */
    Complete
}

/**
 * ActionCardData - Data for displaying a tool execution in the chat.
 */
data class ActionCardData(
    val id: String,
    val toolName: String,
    val description: String,
    val state: ActionState,
    val resultSummary: String? = null,
    val expandedContent: String? = null
)

/**
 * ActionState - The execution state of an action.
 */
enum class ActionState {
    /** Action has been proposed but not yet executed */
    Proposed,
    
    /** Action is currently executing */
    Executing,
    
    /** Action completed successfully */
    Success,
    
    /** Action failed */
    Failed,
    
    /** Action was skipped (e.g., denied by user or policy) */
    Skipped
}

/**
 * ChatUiState - Overall UI state for the chat screen.
 */
data class ChatUiState(
    val showEmptyState: Boolean = true
)

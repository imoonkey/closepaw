package com.moonkey.androidagent.protocol

/**
 * AgentEvent - Events emitted by the agent session to the UI layer.
 * 
 * This defines the "Event Queue" (EQ) in the Codex pattern.
 * All state changes, progress updates, and results are expressed as events.
 * 
 * Events are:
 * - Immutable data classes
 * - Thread-safe to pass around
 * - Collected via Kotlin Flow
 */
sealed interface AgentEvent {
    /** Session this event belongs to */
    val sessionId: SessionId
    
    /** When this event occurred */
    val timestamp: Long
    
    // ===== Session Lifecycle Events =====
    
    /**
     * Session has started.
     */
    data class SessionStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val goal: String
    ) : AgentEvent
    
    /**
     * Session completed (successfully or via user stop).
     */
    data class SessionCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val result: String?,
        val reason: CompletionReason
    ) : AgentEvent
    
    /**
     * Session encountered an error.
     */
    data class SessionError(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val error: AgentError
    ) : AgentEvent
    
    /**
     * Session was paused.
     */
    data class SessionPaused(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : AgentEvent
    
    /**
     * Session was resumed.
     */
    data class SessionResumed(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : AgentEvent

    // ===== Task Events (New) =====

    /**
     * A new task has started within the session.
     */
    data class TaskStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val input: String
    ) : AgentEvent

    /**
     * A task has completed.
     */
    data class TaskCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val result: String?
    ) : AgentEvent
    
    // ===== Turn Events =====
    
    /**
     * A new turn has started.
     */
    data class TurnStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val turnNumber: Int,
        val phase: TurnPhase
    ) : AgentEvent
    
    /**
     * A turn has completed.
     */
    data class TurnCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val turnNumber: Int
    ) : AgentEvent
    
    /**
     * Turn phase has changed.
     */
    data class TurnPhaseChanged(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val phase: TurnPhase
    ) : AgentEvent

    // ===== Streaming Events (New) =====

    /**
     * A text delta from the streaming response.
     */
    data class MessageDelta(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val delta: String
    ) : AgentEvent
    
    // ===== Agent Thinking Events =====
    
    /**
     * An agent is "thinking" (LLM call in progress or completed).
     */
    data class AgentThinking(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,  // "manager", "executor", "reflector"
        val thought: String
    ) : AgentEvent
    
    // ===== Action Events =====
    
    /**
     * An action has been proposed (before execution).
     */
    data class ActionProposed(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val toolName: String,
        val description: String
    ) : AgentEvent
    
    /**
     * An action has been executed.
     */
    data class ActionExecuted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val toolName: String,
        val success: Boolean,
        val result: String?
    ) : AgentEvent
    
    /**
     * An action was skipped (e.g., denied by user or policy).
     */
    data class ActionSkipped(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val reason: String
    ) : AgentEvent
    
    // ===== Perception Events =====
    
    /**
     * Screen has been captured and analyzed.
     */
    data class ScreenCaptured(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val elementCount: Int,
        val packageName: String?,
        val activityName: String?
    ) : AgentEvent
    
    // ===== Approval Events =====
    
    /**
     * User approval is required for an action.
     */
    data class ApprovalRequired(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val description: String,
        val details: ApprovalDetails
    ) : AgentEvent
    
    /**
     * Approval request was resolved (approved, denied, or timed out).
     */
    data class ApprovalResolved(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val decision: ApprovalDecision
    ) : AgentEvent
    
    // ===== Status Events =====
    
    /**
     * General status update for simple UI display.
     * 
     * This is a convenience event for UIs that just want to show
     * a single status line with an emoji.
     */
    data class StatusUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val status: String,
        val emoji: String? = null
    ) : AgentEvent
}

/**
 * TurnPhase - Phases within a single agent turn.
 */
enum class TurnPhase {
    /** Capturing and analyzing the screen */
    PERCEPTION,
    
    /** 
     * Verifying the outcome of the previous action.
     * TODO: Planned for action verification - not yet implemented.
     */
    REFLECTION,
    
    /** Deciding what to do (LLM reasoning) */
    PLANNING,
    
    /** Executing an action (tool call) */
    EXECUTION
}

/**
 * CompletionReason - Why a session completed.
 */
enum class CompletionReason {
    /** Goal was achieved successfully */
    GOAL_ACHIEVED,
    
    /** User requested shutdown */
    USER_STOPPED,
    
    /** Maximum turns reached */
    MAX_TURNS,
    
    /** Agent decided the task cannot be completed */
    TASK_IMPOSSIBLE,
    
    /** An error occurred */
    ERROR,
    
    /** Session was interrupted */
    INTERRUPTED
}

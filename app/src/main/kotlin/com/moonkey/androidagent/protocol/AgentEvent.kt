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
     *
     * [reason] indicates why the task ended — goal achieved, max turns, error, etc.
     * The handoff (VD → real screen) triggers only on [CompletionReason.GOAL_ACHIEVED].
     */
    data class TaskCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val result: String?,
        val reason: CompletionReason
    ) : AgentEvent

    // ===== Planning State Events =====

    /**
     * Todo list has been updated.
     */
    data class TodosUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val todos: List<Todo>
    ) : AgentEvent

    /**
     * Scratchpad has been updated (write/delete).
     */
    data class ScratchpadUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val key: String,
        val action: String
    ) : AgentEvent

    // ===== Sub-Agent Events =====

    /**
     * Parent agent delegated a task to a sub-agent.
     */
    data class SubAgentStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val query: String
    ) : AgentEvent

    /**
     * Bridged activity emitted from a running sub-agent.
     */
    data class SubAgentActivity(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val activity: String
    ) : AgentEvent

    /**
     * Sub-agent completed with success/failure status.
     */
    data class SubAgentCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val success: Boolean,
        val message: String
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
    
    // ===== Perception Events =====
    
    /**
     * Screen has been captured and analyzed.
     */
    data class ScreenCaptured(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val elementCount: Int,
        val packageName: String?,
        val activityName: String?,
        val turnId: String,
        val turnNumber: Int,
        val phase: ScreenStatePhase,
        val rawA11yTreePath: String?,
        val sanitizedA11yTreePath: String?,
        val screenshotPath: String?,
        val traceRunId: String?
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
    
    // ===== Thought Events =====

    /**
     * Agent thought update for the Smart Capsule.
     *
     * Extracted from `agent_thought` in tool call parameters.
     * One line, user-facing, concrete.
     */
    data class ThoughtUpdate(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val thought: String
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

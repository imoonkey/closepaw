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
    ) : SessionLifecycleEvent
    
    /**
     * Session completed (successfully or via user stop).
     */
    data class SessionCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val result: String?,
        val reason: CompletionReason
    ) : SessionLifecycleEvent
    
    /**
     * Session encountered an error.
     */
    data class SessionError(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val error: AgentError
    ) : SessionLifecycleEvent
    
    /**
     * User took over control (agent paused).
     */
    data class SessionTakeover(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : SessionLifecycleEvent

    /**
     * Session was resumed after takeover.
     */
    data class SessionResumed(
        override val sessionId: SessionId,
        override val timestamp: Long
    ) : SessionLifecycleEvent

    /**
     * User injected a mid-task supplement message.
     */
    data class SupplementReceived(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val text: String
    ) : SessionLifecycleEvent

    // ===== Task Events (New) =====

    /**
     * A new task has started within the session.
     */
    data class TaskStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val taskId: String,
        val input: String
    ) : TaskLifecycleEvent

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
    ) : TaskLifecycleEvent

    // ===== Planning State Events =====

    /**
     * Todo list has been updated.
     */
    data class TodosUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val todos: List<Todo>
    ) : PlanningStateEvent

    /**
     * Scratchpad has been updated (write/delete).
     */
    data class ScratchpadUpdated(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val key: String,
        val action: String
    ) : PlanningStateEvent

    // ===== Sub-Agent Events =====

    /**
     * Parent agent delegated a task to a sub-agent.
     */
    data class SubAgentStarted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val query: String
    ) : SubAgentDomainEvent

    /**
     * Bridged activity emitted from a running sub-agent.
     */
    data class SubAgentActivity(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val activity: String
    ) : SubAgentDomainEvent

    /**
     * Sub-agent completed with success/failure status.
     */
    data class SubAgentCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val agentName: String,
        val success: Boolean,
        val message: String
    ) : SubAgentDomainEvent
    
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
    ) : TurnDomainEvent
    
    /**
     * A turn has completed.
     */
    data class TurnCompleted(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val turnNumber: Int
    ) : TurnDomainEvent
    
    /**
     * Turn phase has changed.
     */
    data class TurnPhaseChanged(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val phase: TurnPhase
    ) : TurnDomainEvent

    // ===== Streaming Events (New) =====

    /**
     * A text delta from the streaming response.
     */
    data class MessageDelta(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val turnId: String,
        val delta: String
    ) : StreamingDomainEvent
    
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
    ) : ActionDomainEvent
    
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
    ) : ActionDomainEvent
    
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
    ) : PerceptionDomainEvent
    
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
    ) : ApprovalDomainEvent
    
    /**
     * Approval request was resolved (approved, denied, or timed out).
     */
    data class ApprovalResolved(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val actionId: String,
        val decision: ApprovalDecision
    ) : ApprovalDomainEvent
    
    // ===== Ask User Events =====

    /**
     * Agent is asking the user for help.
     *
     * Capsule transitions to WaitingForInput (question) or WaitingForAction (action).
     */
    data class AskUser(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val type: AskUserType,
        val message: String,
        val callId: String
    ) : AskUserDomainEvent

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
    ) : ThoughtDomainEvent

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
    ) : StatusDomainEvent
}

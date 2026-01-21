package com.moonkey.androidagent.tool

import com.moonkey.androidagent.protocol.ApprovalDecision
import org.json.JSONObject

/**
 * ToolCallState - State machine for tracking tool call lifecycle.
 * 
 * State transitions:
 * 
 *   VALIDATING ──valid──► SCHEDULED ──────────────► EXECUTING ──► SUCCESS
 *       │                     │                         │           
 *       │invalid              │ policy=ASK             │error
 *       ▼                     ▼                        ▼
 *     ERROR           AWAITING_APPROVAL ──────────► ERROR
 *                            │
 *                     ┌──────┼──────┐
 *                     ▼      │      ▼
 *                 EXECUTING  │  CANCELLED
 *                   (approved) (denied/abort)
 * 
 * Pattern from Gemini CLI's CoreToolScheduler.
 */
sealed class ToolCallState {
    /** Unique identifier for this tool call */
    abstract val callId: String
    
    /** Tool name */
    abstract val toolName: String
    
    /** Original parameters */
    abstract val params: JSONObject
    
    /**
     * Tool call is being validated.
     */
    data class Validating(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject
    ) : ToolCallState()
    
    /**
     * Tool call passed validation and is scheduled for execution.
     * (Policy allowed auto-execution)
     */
    data class Scheduled(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val invocation: ToolInvocation
    ) : ToolCallState()
    
    /**
     * Tool call requires user approval before execution.
     */
    data class AwaitingApproval(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val invocation: ToolInvocation,
        val description: String,
        val requestedAt: Long = System.currentTimeMillis()
    ) : ToolCallState()
    
    /**
     * Tool call is currently executing.
     */
    data class Executing(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val invocation: ToolInvocation,
        val startedAt: Long = System.currentTimeMillis()
    ) : ToolCallState()
    
    /**
     * Tool call completed successfully.
     */
    data class Success(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val result: ToolExecutionResult.Success,
        val completedAt: Long = System.currentTimeMillis()
    ) : ToolCallState()
    
    /**
     * Tool call failed (validation, execution, or policy error).
     */
    data class Error(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val error: String,
        val exception: Throwable? = null
    ) : ToolCallState()
    
    /**
     * Tool call was cancelled (by user, policy, or timeout).
     */
    data class Cancelled(
        override val callId: String,
        override val toolName: String,
        override val params: JSONObject,
        val reason: String,
        val decision: ApprovalDecision? = null
    ) : ToolCallState()
    
    /**
     * Check if this is a terminal state.
     */
    fun isTerminal(): Boolean = this is Success || this is Error || this is Cancelled
}


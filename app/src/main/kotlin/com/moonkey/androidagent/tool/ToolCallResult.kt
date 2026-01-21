package com.moonkey.androidagent.tool

/**
 * ToolCallResult - Final result of a tool call through the ToolRouter.
 * 
 * This is the result returned to the caller after the tool call
 * has gone through the full lifecycle (validation, approval, execution).
 */
sealed class ToolCallResult {
    /** Unique identifier for this tool call */
    abstract val callId: String
    
    /**
     * Tool call executed successfully.
     */
    data class Success(
        override val callId: String,
        val output: String,
        val data: Any? = null,
        /** Post-action observation (screen state after tool execution) */
        val observation: ToolObservation? = null
    ) : ToolCallResult()
    
    /**
     * Tool call failed.
     */
    data class Error(
        override val callId: String,
        val error: String,
        val exception: Throwable? = null
    ) : ToolCallResult()
    
    /**
     * Tool call was cancelled.
     */
    data class Cancelled(
        override val callId: String,
        val reason: String
    ) : ToolCallResult()
    
    /**
     * Check if this result indicates success.
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * Get the output string, or null if not successful.
     */
    fun getOutputOrNull(): String? = (this as? Success)?.output
    
    /**
     * Convert to a string suitable for including in LLM context.
     */
    fun toContextString(): String {
        return when (this) {
            is Success -> output
            is Error -> "Error: $error"
            is Cancelled -> "Cancelled: $reason"
        }
    }
}


package com.moonkey.androidagent.protocol

/**
 * AgentError - Categorized errors for the agent system.
 * 
 * Each error type indicates:
 * - What went wrong
 * - Whether it's potentially recoverable (via retry)
 * - Enough context for debugging and user feedback
 */
sealed class AgentError {
    abstract val message: String
    abstract val isRecoverable: Boolean
    
    // ===== LLM Errors =====
    
    /**
     * Error communicating with the LLM API.
     */
    data class LLMError(
        override val message: String,
        val statusCode: Int? = null,
        val retryAfterMs: Long? = null
    ) : AgentError() {
        override val isRecoverable: Boolean
            get() = statusCode in listOf(429, 503, 504) // Rate limit, service unavailable
    }
    
    /**
     * LLM response was malformed or unparseable.
     */
    data class LLMParseError(
        override val message: String,
        val rawResponse: String? = null
    ) : AgentError() {
        override val isRecoverable: Boolean = true // LLM can try again with clarification
    }
    
    // ===== Platform Errors =====
    
    /**
     * Error from the Android platform (accessibility, gestures, etc.).
     */
    data class PlatformError(
        override val message: String,
        val cause: Throwable? = null
    ) : AgentError() {
        override val isRecoverable: Boolean = false
    }
    
    /**
     * Required permission is missing.
     */
    data class PermissionError(
        override val message: String,
        val requiredPermission: String
    ) : AgentError() {
        override val isRecoverable: Boolean = false
    }
    
    // ===== Validation Errors =====
    
    /**
     * Tool parameters failed validation.
     */
    data class ValidationError(
        override val message: String,
        val field: String? = null,
        val details: List<String> = emptyList()
    ) : AgentError() {
        override val isRecoverable: Boolean = true // LLM can provide better params
    }
    
    /**
     * Requested tool does not exist.
     */
    data class UnknownToolError(
        val toolName: String
    ) : AgentError() {
        override val message: String = "Unknown tool: $toolName"
        override val isRecoverable: Boolean = true // LLM can use correct tool name
    }
    
    // ===== State Errors =====
    
    /**
     * Operation not valid in current state.
     */
    data class InvalidStateError(
        override val message: String,
        val currentState: String,
        val attemptedOperation: String
    ) : AgentError() {
        override val isRecoverable: Boolean = false
    }
    
    /**
     * Session has already been cancelled or shutdown.
     */
    data class SessionClosedError(
        override val message: String = "Session is closed"
    ) : AgentError() {
        override val isRecoverable: Boolean = false
    }
    
    // ===== Approval Errors =====
    
    /**
     * User denied the action.
     */
    data class ApprovalDeniedError(
        val actionId: String,
        val toolName: String
    ) : AgentError() {
        override val message: String = "User denied action: $toolName"
        override val isRecoverable: Boolean = true // Can propose alternative
    }
    
    /**
     * Action was forbidden by policy.
     */
    data class PolicyDeniedError(
        val toolName: String,
        val reason: String
    ) : AgentError() {
        override val message: String = "Action forbidden by policy: $reason"
        override val isRecoverable: Boolean = false
    }
    
    // ===== Generic/Fallback =====
    
    /**
     * Unexpected error that doesn't fit other categories.
     */
    data class UnexpectedError(
        override val message: String,
        val cause: Throwable? = null
    ) : AgentError() {
        override val isRecoverable: Boolean = false
    }
    
    companion object {
        /**
         * Create appropriate AgentError from a generic exception.
         */
        fun from(e: Throwable): AgentError {
            return when (e) {
                is java.net.SocketTimeoutException -> LLMError(
                    message = "Request timed out",
                    retryAfterMs = 5000
                )
                is java.net.UnknownHostException -> LLMError(
                    message = "Network unavailable: ${e.message}",
                    retryAfterMs = 10000
                )
                is java.io.IOException -> LLMError(
                    message = "Network error: ${e.message}",
                    retryAfterMs = 5000
                )
                is SecurityException -> PermissionError(
                    message = e.message ?: "Permission denied",
                    requiredPermission = "unknown"
                )
                else -> UnexpectedError(
                    message = e.message ?: "Unknown error",
                    cause = e
                )
            }
        }
    }
}


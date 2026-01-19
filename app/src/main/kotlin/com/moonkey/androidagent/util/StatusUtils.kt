package com.moonkey.androidagent.util

/**
 * Utility functions for status message processing.
 * 
 * Centralizes emoji cleanup and status type detection to avoid duplication
 * across OverlayManager, AgentScreen, and MainActivity.
 */
object StatusUtils {
    
    /**
     * Regex pattern to match all status emojis in a single pass.
     * More efficient than iterating and replacing one by one.
     */
    private val EMOJI_PATTERN = Regex("[✅❌⚠️🧠🔧💡👀🚀🛑✓]")
    
    /**
     * Remove emojis from status text for cleaner display.
     * Uses regex for efficient single-pass replacement.
     * 
     * @param status The raw status message
     * @return Clean text without emojis, trimmed
     */
    fun cleanStatusText(status: String): String {
        return EMOJI_PATTERN.replace(status, "").trim()
    }
    
    /**
     * Represents the semantic type of a status message.
     */
    enum class StatusType {
        SUCCESS,
        ERROR,
        WARNING,
        THINKING,
        TOOL,
        RUNNING,
        NEUTRAL
    }
    
    /**
     * Determine the type of status from the message content.
     * Note: "retrying" errors are classified as WARNING, not ERROR,
     * since they represent recoverable states.
     * 
     * @param status The status message to analyze
     * @return The detected StatusType
     */
    fun getStatusType(status: String): StatusType = when {
        status.contains("✅") || status.contains("Goal achieved") || status.contains("✓") || status.contains("achieved") ->
            StatusType.SUCCESS
        // Retrying errors are warnings, not terminal errors
        status.contains("retrying", ignoreCase = true) ->
            StatusType.WARNING
        status.contains("❌") || status.contains("Failed") ->
            StatusType.ERROR
        // "Error" without retry indication is also an error
        status.contains("Error") && !status.contains("retrying", ignoreCase = true) ->
            StatusType.ERROR
        status.contains("⚠️") || status.contains("Warning") || status.contains("Required") ->
            StatusType.WARNING
        status.contains("🧠") || status.contains("Thinking") ->
            StatusType.THINKING
        status.contains("🔧") || status.contains("Tool:") || status.contains("executed") ->
            StatusType.TOOL
        status.contains("Starting") || status.contains("Running") ->
            StatusType.RUNNING
        else ->
            StatusType.NEUTRAL
    }
    
    /**
     * Check if the status indicates a terminal state (session completed or failed).
     * 
     * Terminal states are:
     * - Success (goal achieved)
     * - Final errors (not retrying)
     * - Explicit stop/cancellation
     * - Max turns reached
     * 
     * Non-terminal: warnings, retrying errors, thinking, tool execution
     * 
     * @param status The status message to check
     * @return true if this represents a terminal state
     */
    fun isTerminalStatus(status: String): Boolean {
        // Explicitly exclude retrying errors - they are NOT terminal
        if (status.contains("retrying", ignoreCase = true)) {
            return false
        }
        
        val type = getStatusType(status)
        
        // Terminal states: success or final error
        return type == StatusType.SUCCESS || 
               type == StatusType.ERROR ||
               status.contains("stopped") ||
               status.contains("completed") ||
               status.contains("🛑") ||
               status.contains("Max turns reached") ||
               status.contains("Cancelled")
    }
}

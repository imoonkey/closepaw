package com.moonkey.androidagent.util

/**
 * Utility functions for status message processing.
 * 
 * Centralizes emoji cleanup and status type detection to avoid duplication
 * across OverlayManager, AgentScreen, and MainActivity.
 */
object StatusUtils {
    
    /**
     * Emojis used in status messages that should be stripped for clean display.
     */
    private val STATUS_EMOJIS = listOf(
        "✅", "❌", "⚠️", "🧠", "🔧", "💡", "👀", "🚀", "🛑", "✓"
    )
    
    /**
     * Remove emojis from status text for cleaner display.
     * 
     * @param status The raw status message
     * @return Clean text without emojis, trimmed
     */
    fun cleanStatusText(status: String): String {
        var result = status
        STATUS_EMOJIS.forEach { emoji ->
            result = result.replace(emoji, "")
        }
        return result.trim()
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
     * 
     * @param status The status message to analyze
     * @return The detected StatusType
     */
    fun getStatusType(status: String): StatusType = when {
        status.contains("✅") || status.contains("Goal achieved") || status.contains("✓") || status.contains("achieved") ->
            StatusType.SUCCESS
        status.contains("❌") || status.contains("Error") || status.contains("Failed") ->
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
     * @param status The status message to check
     * @return true if this represents a terminal state
     */
    fun isTerminalStatus(status: String): Boolean {
        val type = getStatusType(status)
        // Terminal states: success, error (but not warnings/retries), or explicit stop
        return type == StatusType.SUCCESS || 
               type == StatusType.ERROR ||
               status.contains("stopped") ||
               status.contains("completed") ||
               status.contains("🛑") ||
               status.contains("Max turns reached") ||
               status.contains("Cancelled")
    }
}

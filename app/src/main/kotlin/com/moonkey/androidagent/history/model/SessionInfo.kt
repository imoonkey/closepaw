package com.moonkey.androidagent.history.model

/**
 * Summary information for a session (for listing without loading full content).
 * 
 * This is a lightweight representation used in the session list UI.
 * It avoids loading the full message history when only preview info is needed.
 */
data class SessionInfo(
    /** Session ID (UUID) */
    val id: String,
    
    /** File path (relative to sessions directory) */
    val fileName: String,
    
    /** When session started (epoch millis) */
    val startTime: Long,
    
    /** When session was last updated (epoch millis) */
    val lastUpdated: Long,
    
    /** Number of messages */
    val messageCount: Int,
    
    /** Display title (summary or first user message truncated) */
    val displayTitle: String,
    
    /** First user message text (for preview) */
    val firstUserMessage: String,
    
    /** Whether this is the currently active session */
    val isActive: Boolean = false,

    /**
     * True when the session file could not be parsed and this entry is a
     * placeholder surfaced so the user knows the file exists but is unreadable.
     */
    val isCorrupted: Boolean = false
)

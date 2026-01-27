package com.moonkey.androidagent.history.model

import kotlinx.serialization.Serializable

/**
 * A complete chat session record stored on disk.
 * 
 * This is the main container for a persisted session. It includes all messages,
 * timestamps, and metadata needed to restore a conversation.
 */
@Serializable
data class SessionRecord(
    /** Unique session identifier (UUID) */
    val sessionId: String,
    
    /** When the session started (epoch millis) */
    val startTime: Long,
    
    /** When the session was last updated (epoch millis) */
    val lastUpdated: Long,
    
    /** All messages in the session */
    val messages: List<MessageRecord>,
    
    /** AI-generated or extracted summary (optional) */
    val summary: String? = null,
    
    /** Session metadata */
    val metadata: SessionMetadata = SessionMetadata()
)

/**
 * Metadata about a session.
 */
@Serializable
data class SessionMetadata(
    /** App version that created this session */
    val appVersion: String? = null,
    
    /** Model used (e.g., "gpt-5.2") */
    val model: String? = null,
    
    /** Total number of turns */
    val turnCount: Int = 0,
    
    /** Whether session completed normally */
    val completedNormally: Boolean = false
)

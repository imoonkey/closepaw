package com.moonkey.androidagent.agent

import com.moonkey.androidagent.protocol.SessionId

/**
 * Configuration for Agent execution.
 * 
 * Simplified from OrchestrationConfig - removes multi-agent specific fields.
 */
data class AgentConfig(
    /** The user's goal (or task input) */
    val goal: String,
    
    /** Session ID for event emission */
    val sessionId: SessionId,
    
    /** Task ID for this execution (optional, defaults to session ID if not provided) */
    val taskId: String = sessionId.value,
    
    /** Maximum number of turns before stopping */
    val maxTurns: Int = 50,
    
    /** Delay after action execution (for UI settling) */
    val uiSettleDelayMs: Long = 3000,
    
    /** Whether to enable debug logging */
    val debugMode: Boolean = false,
    
    /** System prompt template (null = use default) */
    val systemPrompt: String? = null
)

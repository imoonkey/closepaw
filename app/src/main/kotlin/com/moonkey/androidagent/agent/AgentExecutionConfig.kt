package com.moonkey.androidagent.agent

import com.moonkey.androidagent.protocol.SessionId

/**
 * Configuration for Agent execution.
 */
/**
 * Execution role used for trace classification and replay grouping.
 */
enum class AgentExecutionRole {
    /** Main planner/orchestrator. */
    PLANNER,
    /** Delegated executor for atomic actions. */
    EXECUTOR,
    /** Single-agent mode without delegation. */
    STANDALONE
}

data class AgentExecutionConfig(
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
    val systemPrompt: String? = null,

    /**
     * Optional allowlist of tools exposed to this agent in LLM function schemas.
     * Null means all registered tools are exposed.
     */
    val allowedToolNames: Set<String>? = null,

    /**
     * Stable id used for trace grouping in multi-agent runs.
     * Defaults to session id when not in a delegation chain.
     */
    val agentId: String = sessionId.value,

    /**
     * Runtime role of this agent instance.
     */
    val agentRole: AgentExecutionRole = AgentExecutionRole.STANDALONE,

    /**
     * Parent session id when spawned by delegation.
     */
    val parentSessionId: SessionId? = null,

    /**
     * Parent delegate tool call id that spawned this agent.
     */
    val delegationCallId: String? = null,

    /**
     * Model name (key in llm_models.json) for this agent's LLM calls.
     * Set by SessionAgentRunner based on agent role.
     */
    val modelName: String = "gpt-5.2"
)

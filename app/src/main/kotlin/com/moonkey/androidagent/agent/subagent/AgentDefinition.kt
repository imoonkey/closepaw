package com.moonkey.androidagent.agent.subagent

/**
 * Defines a sub-agent that can be invoked through delegate_task.
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolNames: List<String>,
    val maxTurns: Int = 10,
    val timeoutMs: Long = 60_000
)

/**
 * Delegation payload passed from parent to a sub-agent.
 */
data class SubAgentRequest(
    val query: String,
    val currentSubgoal: String? = null,
    val importantNotes: List<String> = emptyList()
)

/**
 * Result returned after running a sub-agent.
 */
data class SubAgentResult(
    val success: Boolean,
    val message: String
)

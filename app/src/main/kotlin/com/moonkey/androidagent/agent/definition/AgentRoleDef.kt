package com.moonkey.androidagent.agent.definition

import com.moonkey.androidagent.agent.AgentExecutionRole

/**
 * Unified definition of one agent role.
 *
 * Used by both top-level agent startup (SessionAgentRunner) and sub-agent delegation
 * (DelegateTaskTool / IsolatedSubAgentRunner). Prompt, tools, and delegation properties
 * live in one place — no bridge objects or parallel type hierarchies.
 */
internal data class AgentRoleDef(
    val name: String,
    val executionRole: AgentExecutionRole,
    val systemPrompt: String,
    val allowedTools: Set<String>,
    /** Whether this role can be invoked as a sub-agent via delegate_task. */
    val delegatable: Boolean = false,
    /** Human-readable description shown in the delegate_task directory prompt. */
    val description: String = "",
    /** Turn budget when invoked as a sub-agent (ignored for top-level agents). */
    val maxTurns: Int = 50,
    /** Timeout when invoked as a sub-agent (ignored for top-level agents). */
    val timeoutMs: Long = 60_000
)

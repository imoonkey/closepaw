package com.moonkey.androidagent.agent.definition

import com.moonkey.androidagent.agent.AgentExecutionRole

/**
 * Static definition of one agent persona.
 *
 * All role differences (prompt/tools/delegation requirement) are centralized here.
 */
internal abstract class AgentDef {
    abstract val id: String
    abstract val executionRole: AgentExecutionRole
    abstract val systemPrompt: String
    abstract val allowedTools: Set<String>
    abstract val requiresDelegationToolRegistration: Boolean
}

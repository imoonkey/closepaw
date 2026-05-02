package ai.closepaw.agent.definition

import ai.closepaw.agent.AgentExecutionRole
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.ToolName

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
) {
    fun resolve(
        snapshot: TermuxCapabilitySnapshot,
        excludedTools: Set<ToolName> = emptySet()
    ): ResolvedAgentRole {
        val excludedToolNames = excludedTools.map { it.canonical }.toSet()
        val baseTools = allowedTools
            .map { ToolName.from(it) }
            .filter { it.canonical !in excludedToolNames }

        val canExposeTermux =
            snapshot.available && ToolName.TermuxShell.canonical !in excludedToolNames
        val resolvedTools =
            if (canExposeTermux && baseTools.none { it.canonical == ToolName.TermuxShell.canonical }) {
                baseTools + ToolName.TermuxShell
            } else {
                baseTools
            }

        val prompt = if (resolvedTools.any { it.canonical == ToolName.TermuxShell.canonical }) {
            systemPrompt + "\n\n" + WORKSPACE_SHELL_PROMPT_SECTION
        } else {
            systemPrompt
        }

        return ResolvedAgentRole(
            name = name,
            executionRole = executionRole,
            allowedTools = resolvedTools,
            systemPrompt = prompt,
            timeoutMs = timeoutMs,
            delegatable = delegatable,
            description = description,
            maxTurns = maxTurns
        )
    }
}

internal data class ResolvedAgentRole(
    val name: String,
    val executionRole: AgentExecutionRole,
    val allowedTools: List<ToolName>,
    val systemPrompt: String,
    val timeoutMs: Long,
    val delegatable: Boolean = false,
    val description: String = "",
    val maxTurns: Int = 50
) {
    val allowedToolNames: Set<String> = allowedTools.map { it.raw }.toSet()
}

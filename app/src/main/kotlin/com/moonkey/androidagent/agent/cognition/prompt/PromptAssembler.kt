package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.agent.cognition.AgentRole
import com.moonkey.androidagent.protocol.LLMBackendType

internal data class PromptBuildContext(
    val basePrompt: String?,
    val llmBackend: LLMBackendType,
    val visibleToolNames: Set<String>,
    val stateContext: String
)

internal interface PromptAssembler {
    fun build(context: PromptBuildContext): String
}

internal class DefaultPromptAssembler : PromptAssembler {
    override fun build(context: PromptBuildContext): String {
        val role = AgentRole.fromToolNames(context.visibleToolNames)
        val prompt = context.basePrompt ?: PlannerPromptTemplate.defaultSystemPrompt
        val backendPrompt =
            if (context.llmBackend == LLMBackendType.LOCAL) {
                "$prompt\n\n${SharedPromptRules.localModelToolCalling}"
            } else {
                prompt
            }
        val withStateContext =
            if (context.stateContext.isNotBlank()) {
                "$backendPrompt\n\n${context.stateContext}"
            } else {
                backendPrompt
            }
        val roleRules =
            if (role == AgentRole.PLANNER) {
                SharedPromptRules.plannerRoleRules
            } else {
                SharedPromptRules.executorRoleRules
            }

        return """
            $withStateContext

            $roleRules
        """.trimIndent()
    }
}

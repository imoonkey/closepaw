package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.agent.cognition.AgentRole
import com.moonkey.androidagent.agent.cognition.profile.CognitionProfile
import com.moonkey.androidagent.agent.cognition.profile.PromptVariant
import com.moonkey.androidagent.protocol.LLMBackendType

internal data class PromptBuildContext(
    val basePrompt: String?,
    val llmBackend: LLMBackendType,
    val visibleToolNames: Set<String>,
    val stateContext: String,
    val profile: CognitionProfile
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
        val roleRules = selectRoleRules(role, context.profile.promptVariant)

        return """
            $withStateContext

            $roleRules
        """.trimIndent()
    }

    private fun selectRoleRules(role: AgentRole, variant: PromptVariant): String {
        return when (variant) {
            PromptVariant.BASELINE ->
                if (role == AgentRole.PLANNER) {
                    SharedPromptRules.plannerRoleRules
                } else {
                    SharedPromptRules.executorRoleRules
                }

            PromptVariant.CONCISE ->
                if (role == AgentRole.PLANNER) {
                    SharedPromptRules.plannerRoleRulesConcise
                } else {
                    SharedPromptRules.executorRoleRulesConcise
                }
        }
    }
}

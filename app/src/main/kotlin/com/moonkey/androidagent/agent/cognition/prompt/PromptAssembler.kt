package com.moonkey.androidagent.agent.cognition.prompt

import com.moonkey.androidagent.agent.cognition.AgentRole

internal data class PromptBuildContext(
    val basePrompt: String?,
    val visibleToolNames: Set<String>,
    val stateContext: String
)

internal interface PromptAssembler {
    fun build(context: PromptBuildContext): String
}

internal class DefaultPromptAssembler : PromptAssembler {
    override fun build(context: PromptBuildContext): String {
        val role = AgentRole.fromToolNames(context.visibleToolNames)
        val prompt =
            context.basePrompt
                ?: if (role == AgentRole.EXECUTOR) {
                    ExecutorPromptTemplate.systemPrompt
                } else {
                    PlannerPromptTemplate.defaultSystemPrompt
                }

        return if (context.stateContext.isNotBlank()) {
            "$prompt\n\n${context.stateContext}"
        } else {
            prompt
        }
    }
}

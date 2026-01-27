package com.moonkey.androidagent.agent

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.tool.ToolRegistry

class AgentPromptBuilder(
    private val basePrompt: String?,
    private val defaultPrompt: String,
    private val localPromptSuffix: String,
    private val llmBackend: LLMBackendType,
    private val toolRegistry: ToolRegistry
) {
    fun buildSystemPrompt(): String {
        val prompt = basePrompt ?: defaultPrompt
        return if (llmBackend == LLMBackendType.LOCAL) {
            "$prompt\n\n$localPromptSuffix"
        } else {
            prompt
        }
    }

    fun buildUserContext(snapshot: ScreenSnapshot): String {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val toolNames = toolRegistry.getNames().joinToString(", ")

        return """
            Current screen state (${snapshot.elements.size} elements):
            ```json
            $screenJson
            ```
            
            Available tools: $toolNames
            
            What action should I take next to achieve the goal?
        """.trimIndent()
    }
}

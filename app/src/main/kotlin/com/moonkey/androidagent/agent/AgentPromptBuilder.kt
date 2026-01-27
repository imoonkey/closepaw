package com.moonkey.androidagent.agent

import com.moonkey.androidagent.model.ScreenImage
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
    data class UserContext(
        val text: String,
        val image: ScreenImage?
    )

    fun buildSystemPrompt(): String {
        val prompt = basePrompt ?: defaultPrompt
        return if (llmBackend == LLMBackendType.LOCAL) {
            "$prompt\n\n$localPromptSuffix"
        } else {
            prompt
        }
    }

    fun buildUserContext(snapshot: ScreenSnapshot): UserContext {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val toolNames = toolRegistry.getNames().joinToString(", ")
        val image = snapshot.image?.takeIf { llmBackend == LLMBackendType.OPENAI }
        val imageHint = if (image != null) {
            "\nScreenshot attached (compressed)."
        } else {
            ""
        }

        val text = """
            Current screen state (${snapshot.elements.size} elements):
            ```json
            $screenJson
            ```
            
            Available tools: $toolNames
            
            $imageHint
            What action should I take next to achieve the goal?
        """.trimIndent()

        return UserContext(
            text = text,
            image = image
        )
    }
}

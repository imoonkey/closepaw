package com.moonkey.androidagent.agent

import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.tool.ToolRegistry

class AgentPromptBuilder(
    private val basePrompt: String?,
    private val defaultPrompt: String,
    private val localPromptSuffix: String,
    private val llmBackend: LLMBackendType,
    private val toolRegistry: ToolRegistry,
    private val sessionState: AgentSessionState,
    private val visibleToolNames: Set<String>? = null
) {
    data class UserContext(
        val text: String,
        val image: ScreenImage?
    )

    fun buildSystemPrompt(): String {
        val prompt = basePrompt ?: defaultPrompt
        val base = if (llmBackend == LLMBackendType.LOCAL) {
            "$prompt\n\n$localPromptSuffix"
        } else {
            prompt
        }
        val stateContext = buildStateContext()
        return if (stateContext.isNotEmpty()) {
            "$base\n\n$stateContext"
        } else {
            base
        }
    }

    fun buildUserContext(snapshot: ScreenSnapshot): UserContext {
        val screenJson = Perceptor.toPromptJson(snapshot)
        val toolNames = toolRegistry.getNames()
            .asSequence()
            .filter { name -> visibleToolNames?.contains(name) != false }
            .sorted()
            .joinToString(", ")
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

    private fun buildStateContext(): String {
        return buildString {
            val todosContext = sessionState.todos.toPromptContext()
            if (todosContext.isNotEmpty()) {
                appendLine("## Current Todos")
                appendLine(todosContext)
                appendLine()
            }

            val scratchpadContext = sessionState.scratchpad.toPromptContext()
            if (scratchpadContext.isNotEmpty()) {
                appendLine("## Scratchpad")
                appendLine(scratchpadContext)
            }
        }.trimEnd()
    }
}

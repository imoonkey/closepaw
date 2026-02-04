package com.moonkey.androidagent.agent.cognition.context

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.AgentPromptBuilder
import com.moonkey.androidagent.agent.cognition.profile.BuiltinCognitionProfiles
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ContextPackagerTest {

    @Test
    fun `buildTurnInput packages user context through prompt builder`() {
        val registry = ToolRegistry().apply {
            register(TestContextTool("delegate_task"))
            register(TestContextTool("complete_task"))
        }
        val promptBuilder =
            AgentPromptBuilder(
                basePrompt = "planner",
                llmBackend = LLMBackendType.OPENAI,
                toolRegistry = registry,
                sessionState = AgentSessionState(),
                visibleToolNames = setOf("delegate_task", "complete_task")
            )
        val packager = DefaultContextPackager(promptBuilder)

        val packaged =
            packager.buildTurnInput(
                profile = BuiltinCognitionProfiles.baseline,
                raw = RawTurnData(snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()))
            )

        assertThat(packaged.userContext.text).contains("Current screen state (0 elements):")
        assertThat(packaged.userContext.text).contains("Available tools:")
        assertThat(packaged.userContext.text).contains("delegate_task")
    }
}

private class TestContextTool(override val name: String) : ToolSpec {
    override val description: String = "test"
    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
            put("required", JSONArray())
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation {
        return object : ToolInvocation {
            override val toolName: String = name
            override val params: JSONObject = params
            override fun getDescription(): String = "test"

            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                return ToolExecutionResult.Success("ok")
            }
        }
    }
}

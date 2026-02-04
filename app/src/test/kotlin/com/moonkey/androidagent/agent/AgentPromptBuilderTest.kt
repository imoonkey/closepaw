package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
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

class AgentPromptBuilderTest {

    @Test
    fun `buildUserContext only lists visible tools`() {
        val context = createBuilder(
            visibleToolNames = setOf("delegate_task", "complete_task", "write_todos", "scratchpad")
        ).buildUserContext(
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())
        )

        assertThat(context.text).contains("delegate_task")
        assertThat(context.text).contains("write_todos")
        assertThat(context.text).contains("scratchpad")
        assertThat(context.text).doesNotContain("mobile_action")
        assertThat(context.text).doesNotContain("app_control")
    }

    @Test
    fun `buildUserContext lists all tools when filter is null`() {
        val context = createBuilder(visibleToolNames = null).buildUserContext(
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())
        )

        assertThat(context.text).contains("mobile_action")
        assertThat(context.text).contains("app_control")
        assertThat(context.text).contains("delegate_task")
        assertThat(context.text).contains("complete_task")
    }

    @Test
    fun `buildUserContext lists no tools when filter is empty`() {
        val context = createBuilder(visibleToolNames = emptySet()).buildUserContext(
            snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())
        )

        assertThat(context.text).contains("Available tools:")
        assertThat(context.text).doesNotContain("mobile_action")
        assertThat(context.text).doesNotContain("app_control")
        assertThat(context.text).doesNotContain("delegate_task")
    }

    private fun createBuilder(visibleToolNames: Set<String>?): AgentPromptBuilder {
        val registry = ToolRegistry().apply {
            register(TestPromptTool("mobile_action"))
            register(TestPromptTool("app_control"))
            register(TestPromptTool("delegate_task"))
            register(TestPromptTool("complete_task"))
            register(TestPromptTool("write_todos"))
            register(TestPromptTool("scratchpad"))
        }
        return AgentPromptBuilder(
            basePrompt = "planner",
            defaultPrompt = "planner",
            localPromptSuffix = "",
            llmBackend = LLMBackendType.OPENAI,
            toolRegistry = registry,
            sessionState = AgentSessionState(),
            visibleToolNames = visibleToolNames
        )
    }
}

private class TestPromptTool(override val name: String) : ToolSpec {
    override val description: String = "test"
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation = object : ToolInvocation {
        override val toolName: String = name
        override val params: JSONObject = params
        override fun getDescription(): String = "test"
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            return ToolExecutionResult.Success("ok")
        }
    }
}

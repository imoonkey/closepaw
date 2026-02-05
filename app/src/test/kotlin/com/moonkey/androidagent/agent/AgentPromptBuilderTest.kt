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

    @Test
    fun `buildSystemPrompt uses base prompt when provided`() {
        val prompt = createBuilder(
            visibleToolNames = setOf("delegate_task", "complete_task", "write_todos"),
            basePrompt = "custom planner prompt"
        ).buildSystemPrompt()

        assertThat(prompt).contains("custom planner prompt")
    }

    @Test
    fun `buildSystemPrompt falls back to default planner template when base prompt missing`() {
        val prompt = createBuilder(
            visibleToolNames = setOf("delegate_task"),
            basePrompt = null
        ).buildSystemPrompt()

        assertThat(prompt).contains("You are the MAIN PLANNER agent for Android automation.")
    }

    @Test
    fun `buildSystemPrompt falls back to executor template when base prompt missing`() {
        val prompt = createBuilder(
            visibleToolNames = setOf("mobile_action", "complete_task"),
            basePrompt = null
        ).buildSystemPrompt()

        assertThat(prompt).contains("You are an Executor agent.")
    }

    private fun createBuilder(
        visibleToolNames: Set<String>?,
        llmBackend: LLMBackendType = LLMBackendType.OPENAI,
        basePrompt: String? = "planner"
    ): AgentPromptBuilder {
        val registry = ToolRegistry().apply {
            register(TestPromptTool("mobile_action"))
            register(TestPromptTool("app_control"))
            register(TestPromptTool("delegate_task"))
            register(TestPromptTool("complete_task"))
            register(TestPromptTool("write_todos"))
            register(TestPromptTool("scratchpad"))
        }
        return AgentPromptBuilder(
            basePrompt = basePrompt,
            llmBackend = llmBackend,
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

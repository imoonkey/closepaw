package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus
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

class AgentPromptBuilderContextTest {

    @Test
    fun `buildUserContext includes screen and tools`() {
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
        val userContext =
            promptBuilder.buildUserContext(
                snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())
            )

        assertThat(userContext.text).contains("Current screen state (0 elements):")
        assertThat(userContext.text).contains("Available tools:")
        assertThat(userContext.text).contains("delegate_task")
    }

    @Test
    fun `buildUserContext appends loop and memory reminders`() {
        val registry = ToolRegistry().apply {
            register(TestContextTool("delegate_task"))
            register(TestContextTool("write_todos"))
            register(TestContextTool("scratchpad"))
        }
        val sessionState = AgentSessionState()
        sessionState.todos.update(
            listOf(
                Todo(description = "Open Gmail", status = TodoStatus.IN_PROGRESS),
                Todo(description = "Count unread emails", status = TodoStatus.PENDING)
            )
        )
        sessionState.scratchpad.write("email_count", "7")

        val promptBuilder =
            AgentPromptBuilder(
                basePrompt = "planner",
                llmBackend = LLMBackendType.OPENAI,
                toolRegistry = registry,
                sessionState = sessionState,
                visibleToolNames = setOf("delegate_task", "write_todos", "scratchpad")
            )
        val userContext =
            promptBuilder.buildUserContext(
                snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList()),
                loopWarning =
                    LoopWarning(
                        message = "Screen unchanged for 3 turns.",
                        severity = LoopWarningSeverity.CRITICAL
                    )
            )

        assertThat(userContext.text).contains("LOOP WARNING")
        assertThat(userContext.text).contains("Todo status")
        assertThat(userContext.text).contains("Scratchpad has 1 key")
    }

    @Test
    fun `buildUserContext skips todo reminder when all todos are completed`() {
        val registry = ToolRegistry().apply {
            register(TestContextTool("delegate_task"))
            register(TestContextTool("write_todos"))
        }
        val sessionState = AgentSessionState()
        sessionState.todos.update(
            listOf(
                Todo(description = "Open Gmail", status = TodoStatus.COMPLETED),
                Todo(description = "Read inbox", status = TodoStatus.CANCELLED)
            )
        )

        val promptBuilder =
            AgentPromptBuilder(
                basePrompt = "planner",
                llmBackend = LLMBackendType.OPENAI,
                toolRegistry = registry,
                sessionState = sessionState,
                visibleToolNames = setOf("delegate_task", "write_todos")
            )
        val userContext =
            promptBuilder.buildUserContext(
                snapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())
            )

        assertThat(userContext.text).doesNotContain("Todo status:")
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

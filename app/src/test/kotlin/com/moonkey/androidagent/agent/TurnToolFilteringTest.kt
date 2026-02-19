package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class TurnToolFilteringTest {

    private val minimalInputItems = listOf(
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content("Screen state (0 elements):\n```json\n[]\n```")
                .build()
        )
    )

    @Test
    fun `run exposes only allowed tools to llm`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = "planning",
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                )
                )
        val registry =
                ToolRegistry().apply {
                    register(TestTurnTool("mobile_action"))
                    register(TestTurnTool("open_app"))
                    register(TestTurnTool("delegate_task"))
                    register(TestTurnTool("complete_task"))
                    register(TestTurnTool("write_todos"))
                }

        val turn =
                Turn(
                        toolRegistry = registry,
                        llmClient = llm,
                        allowedToolNames = setOf("delegate_task", "complete_task", "write_todos")
                )

        val result =
                turn.run(
                        systemPrompt = "planner",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).isEmpty()
        assertThat(llm.lastToolNames)
                .containsExactly("delegate_task", "complete_task", "write_todos")
    }

    @Test
    fun `run drops tool calls outside allowlist`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = null,
                                        toolCalls =
                                                listOf(
                                                        LLMToolCall(
                                                                callId = "call-1",
                                                                name = "mobile_action",
                                                                arguments = "{}"
                                                        )
                                                ),
                                        responseId = "resp"
                                )
                )
        val registry =
                ToolRegistry().apply {
                    register(TestTurnTool("mobile_action"))
                    register(TestTurnTool("delegate_task"))
                }

        val turn =
                Turn(
                        toolRegistry = registry,
                        llmClient = llm,
                        allowedToolNames = setOf("delegate_task")
                )

        val result =
                turn.run(
                        systemPrompt = "planner",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).isEmpty()
    }

    @Test
    fun `run synthesizes non-empty tool call ids when provider returns blank id`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = null,
                                        toolCalls =
                                                listOf(
                                                        LLMToolCall(
                                                                callId = "",
                                                                name = "mobile_action",
                                                                arguments = "{}"
                                                        )
                                                ),
                                        responseId = "resp"
                                )
                )
        val registry = ToolRegistry().apply { register(TestTurnTool("mobile_action")) }
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val result =
                turn.run(
                        systemPrompt = "planner",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).hasSize(1)
        assertThat(result.toolCalls.single().id).isNotEmpty()
        assertThat(result.toolCalls.single().id).startsWith("synthetic_mobile_action_0_")
    }

    @Test
    fun `run recovers inline tool call from text payload`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent =
                                                """mobile_action{"action":"type","element_index":1,"input_text":"张韶涵"}""",
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                )
                )
        val registry = ToolRegistry().apply { register(TestTurnTool("mobile_action")) }
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val result =
                turn.run(
                        systemPrompt = "standalone",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).hasSize(1)
        val toolCall = result.toolCalls.single()
        assertThat(toolCall.name).isEqualTo("mobile_action")
        assertThat(toolCall.arguments.getString("action")).isEqualTo("type")
        assertThat(toolCall.arguments.getInt("element_index")).isEqualTo(1)
        assertThat(toolCall.arguments.getString("input_text")).isEqualTo("张韶涵")
        assertThat(result.isComplete).isFalse()
        assertThat(result.content).isNull()
    }

    @Test
    fun `run recovers inline tool call wrapped in prose`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent =
                                                """
                                                I will perform the requested action now.
                                                mobile_action{"action":"click","element_index":7}
                                                Action prepared.
                                                """.trimIndent(),
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                )
                )
        val registry = ToolRegistry().apply { register(TestTurnTool("mobile_action")) }
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val result =
                turn.run(
                        systemPrompt = "standalone",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).hasSize(1)
        val toolCall = result.toolCalls.single()
        assertThat(toolCall.name).isEqualTo("mobile_action")
        assertThat(toolCall.arguments.getString("action")).isEqualTo("click")
        assertThat(toolCall.arguments.getInt("element_index")).isEqualTo(7)
        assertThat(result.isComplete).isFalse()
        assertThat(result.content).isNull()
    }

    @Test
    fun `run does not complete when known inline call marker is malformed`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = """Trying now: mobile_action{"action":"click","element_index":7""",
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                )
                )
        val registry = ToolRegistry().apply { register(TestTurnTool("mobile_action")) }
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val result =
                turn.run(
                        systemPrompt = "standalone",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).isEmpty()
        assertThat(result.isComplete).isFalse()
        assertThat(result.content).contains("mobile_action")
    }

    @Test
    fun `run treats regular text as completion when no tool calls exist`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = "Done. Task finished.",
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                )
                )
        val registry = ToolRegistry().apply { register(TestTurnTool("mobile_action")) }
        val turn = Turn(toolRegistry = registry, llmClient = llm)

        val result =
                turn.run(
                        systemPrompt = "standalone",
                        inputItems = minimalInputItems
                )

        assertThat(result.toolCalls).isEmpty()
        assertThat(result.isComplete).isTrue()
        assertThat(result.content).isEqualTo("Done. Task finished.")
    }

    @Test
    fun `runStreaming suppresses disallowed tool events`() = runTest {
        val llm =
                CapturingTurnLLMClient(
                        response =
                                ResponsesResult(
                                        textContent = null,
                                        toolCalls = emptyList(),
                                        responseId = "resp"
                                ),
                        streamEvents =
                                listOf(
                                        LLMStreamEvent.ToolCallDone(
                                                LLMToolCall(
                                                        callId = "call-1",
                                                        name = "mobile_action",
                                                        arguments = "{}"
                                                )
                                        ),
                                        LLMStreamEvent.Completed
                                )
                )
        val registry =
                ToolRegistry().apply {
                    register(TestTurnTool("mobile_action"))
                    register(TestTurnTool("delegate_task"))
                }
        val turn =
                Turn(
                        toolRegistry = registry,
                        llmClient = llm,
                        allowedToolNames = setOf("delegate_task")
                )

        val events =
                turn.runStreaming(
                                systemPrompt = "planner",
                                inputItems = minimalInputItems
                        )
                        .toList()

        assertThat(events.filterIsInstance<TurnStreamEvent.ToolCallReceived>()).isEmpty()
        val complete = events.filterIsInstance<TurnStreamEvent.Complete>().single()
        assertThat(complete.result.toolCalls).isEmpty()
    }
}

private class CapturingTurnLLMClient(
        private val response: ResponsesResult,
        private val streamEvents: List<LLMStreamEvent> = listOf(LLMStreamEvent.Completed)
) : LLMClient() {
    var lastToolNames: List<String> = emptyList()

    override suspend fun chatWithTools(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): ResponsesResult {
        lastToolNames = tools.map { it.name() }
        return response
    }

    override fun chatWithToolsStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): Flow<LLMStreamEvent> = flow {
        streamEvents.forEach { emit(it) }
    }
}

private class TestTurnTool(override val name: String) : ToolSpec {
    override val description: String = "test"
    override val parameterSchema: JSONObject =
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
                put("required", JSONArray())
                put("additionalProperties", false)
            }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation =
            object : ToolInvocation {
                override val toolName: String = name
                override val params: JSONObject = params
                override fun getDescription(): String = "test"
                override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                    return ToolExecutionResult.Success("ok")
                }
            }
}

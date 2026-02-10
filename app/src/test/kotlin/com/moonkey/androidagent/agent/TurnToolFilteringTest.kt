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
}

private class CapturingTurnLLMClient(private val response: ResponsesResult) : LLMClient() {
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
    ): Flow<LLMStreamEvent> = flow { emit(LLMStreamEvent.Completed) }
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

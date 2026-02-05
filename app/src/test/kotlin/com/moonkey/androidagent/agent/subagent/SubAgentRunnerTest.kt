package com.moonkey.androidagent.agent.subagent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.tool.impl.CompleteTaskTool
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentRunnerTest {

    @Test
    fun `runner returns success when child reaches goal`() = runTest {
        val services = buildServices(SubAgentTestLLMClient(delayMs = 0))
        val runner = IsolatedSubAgentRunner(
            definition = AgentDefinition(
                name = "executor",
                description = "Exec",
                systemPrompt = "prompt",
                toolNames = emptyList(),
                maxTurns = 1,
                timeoutMs = 5_000
            ),
            parentServices = services,
            parentSessionId = SessionId("session-1"),
            eventEmitter = { }
        )

        val result = runner.run(SubAgentRequest(query = "do it"))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `runner returns timeout when child exceeds timeout`() = runTest {
        val services = buildServices(SubAgentTestLLMClient(delayMs = 200))
        val events = mutableListOf<AgentEvent>()
        val runner = IsolatedSubAgentRunner(
            definition = AgentDefinition(
                name = "executor",
                description = "Exec",
                systemPrompt = "prompt",
                toolNames = emptyList(),
                maxTurns = 1,
                timeoutMs = 10
            ),
            parentServices = services,
            parentSessionId = SessionId("session-1"),
            eventEmitter = { events.add(it) }
        )

        val result = runner.run(SubAgentRequest(query = "do it"))

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("Timeout")
    }

    @Test
    fun `runner forwards complete_task success answer`() = runTest {
        val llm = ScriptedSubAgentLLMClient(
            events = listOf(
                LLMStreamEvent.ToolCallDone(
                    LLMToolCall(
                        callId = "call-1",
                        name = "complete_task",
                        arguments = "{\"status\":\"success\",\"answer\":\"Email summary captured\"}"
                    )
                ),
                LLMStreamEvent.Completed
            )
        )
        val services = buildServices(llm, includeCompleteTask = true)
        val runner = IsolatedSubAgentRunner(
            definition = AgentDefinition(
                name = "executor",
                description = "Exec",
                systemPrompt = "prompt",
                toolNames = listOf("complete_task"),
                maxTurns = 1,
                timeoutMs = 5_000
            ),
            parentServices = services,
            parentSessionId = SessionId("session-1"),
            eventEmitter = { }
        )

        val result = runner.run(SubAgentRequest(query = "do it"))

        assertThat(result.success).isTrue()
        assertThat(result.message).contains("Email summary captured")
    }

    @Test
    fun `runner maps complete_task failure status to failed result`() = runTest {
        val llm = ScriptedSubAgentLLMClient(
            events = listOf(
                LLMStreamEvent.ToolCallDone(
                    LLMToolCall(
                        callId = "call-1",
                        name = "complete_task",
                        arguments = "{\"status\":\"failure\",\"answer\":\"Could not find Notion app: Not installed\"}"
                    )
                ),
                LLMStreamEvent.Completed
            )
        )
        val services = buildServices(llm, includeCompleteTask = true)
        val runner = IsolatedSubAgentRunner(
            definition = AgentDefinition(
                name = "executor",
                description = "Exec",
                systemPrompt = "prompt",
                toolNames = listOf("complete_task"),
                maxTurns = 1,
                timeoutMs = 5_000
            ),
            parentServices = services,
            parentSessionId = SessionId("session-1"),
            eventEmitter = { }
        )

        val result = runner.run(SubAgentRequest(query = "do it"))

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("Could not find Notion app: Not installed")
    }

    @Test
    fun `runner returns narrative summary when executor hits step limit`() = runTest {
        val llm = ScriptedSubAgentLLMClient(events = listOf(LLMStreamEvent.Completed))
        val services = buildServices(llm, includeCompleteTask = false)
        val runner =
            IsolatedSubAgentRunner(
                definition =
                    AgentDefinition(
                        name = "executor",
                        description = "Exec",
                        systemPrompt = "prompt",
                        toolNames = emptyList(),
                        maxTurns = 1,
                        timeoutMs = 5_000,
                        narrativeSummaryOnLimit = true
                    ),
                parentServices = services,
                parentSessionId = SessionId("session-1"),
                eventEmitter = { }
            )

        val result = runner.run(SubAgentRequest(query = "Tap search"))

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("Executor reached step limit")
        assertThat(result.message).contains("Delegated query: Tap search")
    }
}

private fun buildServices(
    llmClient: LLMClient,
    includeCompleteTask: Boolean = false
): SessionServices {
    val toolRegistry = ToolRegistry()
    if (includeCompleteTask) {
        toolRegistry.register(CompleteTaskTool())
    }
    val policyEngine = PolicyEngine()
    return SessionServices(
        toolRegistry = toolRegistry,
        toolRouter = ToolRouter(toolRegistry, policyEngine),
        historyManager = HistoryManager(),
        sessionState = AgentSessionState(),
        policyEngine = policyEngine,
        platform = FakeAndroidPlatform(),
        config = SessionConfig(
            maxTurns = 1,
            actionDelayMs = 0,
            llmBackend = LLMBackendType.OPENAI
        ),
        llmClient = llmClient,
        traceRecorder = NoopTraceRecorder
    )
}

private class SubAgentTestLLMClient(
    private val delayMs: Long
) : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): ResponsesResult {
        return ResponsesResult(
            textContent = "done",
            toolCalls = emptyList(),
            responseId = "resp"
        )
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): Flow<LLMStreamEvent> = flow {
        if (delayMs > 0) {
            delay(delayMs)
        }
        emit(LLMStreamEvent.TextDelta("done"))
        emit(LLMStreamEvent.Completed)
    }
}

private class ScriptedSubAgentLLMClient(
    private val events: List<LLMStreamEvent>
) : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): ResponsesResult {
        return ResponsesResult(
            textContent = null,
            toolCalls = emptyList(),
            responseId = "resp"
        )
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): Flow<LLMStreamEvent> = flow {
        events.forEach { emit(it) }
    }
}

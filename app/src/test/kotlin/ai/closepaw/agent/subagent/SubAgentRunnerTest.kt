package ai.closepaw.agent.subagent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.agent.AgentExecutionRole
import ai.closepaw.agent.definition.AgentRoleDef
import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.session.AgentSessionState
import ai.closepaw.session.SessionServices
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.CompleteTaskTool
import ai.closepaw.trace.NoopTraceRecorder
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
                val runner =
                        IsolatedSubAgentRunner(
                                roleDef =
                                        AgentRoleDef(
                                                name = "executor",
                                                executionRole = AgentExecutionRole.EXECUTOR,
                                                description = "Exec",
                                                systemPrompt = "prompt",
                                                allowedTools = emptySet(),
                                                maxTurns = 1,
                                                timeoutMs = 5_000
                                        ),
                                parentServices = services,
                                parentSessionId = SessionId("session-1"),
                                eventDispatcher = AgentEventDispatcher(SessionId("session-1")) {}
                        )

                val result = runner.run(SubAgentRequest(query = "do it"))

                assertThat(result.success).isTrue()
        }

        @Test
        fun `runner returns timeout when child exceeds timeout`() = runTest {
                val services = buildServices(SubAgentTestLLMClient(delayMs = 200))
                val events = mutableListOf<AgentEvent>()
                val runner =
                        IsolatedSubAgentRunner(
                                roleDef =
                                        AgentRoleDef(
                                                name = "executor",
                                                executionRole = AgentExecutionRole.EXECUTOR,
                                                description = "Exec",
                                                systemPrompt = "prompt",
                                                allowedTools = emptySet(),
                                                maxTurns = 1,
                                                timeoutMs = 10
                                        ),
                                parentServices = services,
                                parentSessionId = SessionId("session-1"),
                                eventDispatcher = AgentEventDispatcher(SessionId("session-1")) { events.add(it) }
                        )

                val result = runner.run(SubAgentRequest(query = "do it"))

                assertThat(result.success).isFalse()
                assertThat(result.message).contains("Timeout")
        }

        @Test
        fun `runner forwards complete_task success answer`() = runTest {
                val llm =
                        ScriptedSubAgentLLMClient(
                                events =
                                        listOf(
                                                LLMStreamEvent.ToolCallDone(
                                                        LLMToolCall(
                                                                callId = "call-1",
                                                                name = "complete_task",
                                                                arguments =
                                                                        "{\"status\":\"success\",\"answer\":\"Email summary captured\"}"
                                                        )
                                                ),
                                                LLMStreamEvent.Completed
                                        )
                        )
                val services = buildServices(llm, includeCompleteTask = true)
                val runner =
                        IsolatedSubAgentRunner(
                                roleDef =
                                        AgentRoleDef(
                                                name = "executor",
                                                executionRole = AgentExecutionRole.EXECUTOR,
                                                description = "Exec",
                                                systemPrompt = "prompt",
                                                allowedTools = setOf("complete_task"),
                                                maxTurns = 1,
                                                timeoutMs = 5_000
                                        ),
                                parentServices = services,
                                parentSessionId = SessionId("session-1"),
                                eventDispatcher = AgentEventDispatcher(SessionId("session-1")) {}
                        )

                val result = runner.run(SubAgentRequest(query = "do it"))

                assertThat(result.success).isTrue()
                assertThat(result.message).contains("Email summary captured")
        }

        @Test
        fun `runner maps complete_task failure status to failed result`() = runTest {
                val llm =
                        ScriptedSubAgentLLMClient(
                                events =
                                        listOf(
                                                LLMStreamEvent.ToolCallDone(
                                                        LLMToolCall(
                                                                callId = "call-1",
                                                                name = "complete_task",
                                                                arguments =
                                                                        "{\"status\":\"failure\",\"answer\":\"Could not find Notion app: Not installed\"}"
                                                        )
                                                ),
                                                LLMStreamEvent.Completed
                                        )
                        )
                val services = buildServices(llm, includeCompleteTask = true)
                val runner =
                        IsolatedSubAgentRunner(
                                roleDef =
                                        AgentRoleDef(
                                                name = "executor",
                                                executionRole = AgentExecutionRole.EXECUTOR,
                                                description = "Exec",
                                                systemPrompt = "prompt",
                                                allowedTools = setOf("complete_task"),
                                                maxTurns = 1,
                                                timeoutMs = 5_000
                                        ),
                                parentServices = services,
                                parentSessionId = SessionId("session-1"),
                                eventDispatcher = AgentEventDispatcher(SessionId("session-1")) {}
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
                                roleDef =
                                        AgentRoleDef(
                                                name = "executor",
                                                executionRole = AgentExecutionRole.EXECUTOR,
                                                description = "Exec",
                                                systemPrompt = "prompt",
                                                allowedTools = emptySet(),
                                                maxTurns = 1,
                                                timeoutMs = 5_000
                                        ),
                                parentServices = services,
                                parentSessionId = SessionId("session-1"),
                                eventDispatcher = AgentEventDispatcher(SessionId("session-1")) {}
                        )

                val result = runner.run(SubAgentRequest(query = "Tap search"))

                assertThat(result.success).isFalse()
                assertThat(result.message).contains("Agent reached turn limit")
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
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val testCatalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
                )
        return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = ToolRouter(toolRegistry, policyEngine),
                historyManager = HistoryManager(),
                sessionState = AgentSessionState(),
                policyEngine = policyEngine,
                appClassifier = AppClassifier(emptyMap()),
                platform = FakeAndroidPlatform(),
                config =
                        SessionConfig(
                                maxTurns = 1,
                                actionDelayMs = 0,
                                llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
                        ),
                llmClient = llmClient,
                modelCatalog = testCatalog,
                llmClientFactory = LLMClientFactory.forTest(testCatalog, llmClient),
                traceRecorder = NoopTraceRecorder,
                recordingService = io.mockk.mockk(relaxed = true)
        )
}

private class SubAgentTestLLMClient(private val delayMs: Long) : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
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
                model: String
        ): Flow<LLMStreamEvent> = flow {
                if (delayMs > 0) {
                        delay(delayMs)
                }
                emit(LLMStreamEvent.TextDelta("done"))
                emit(LLMStreamEvent.Completed)
        }
}

private class ScriptedSubAgentLLMClient(private val events: List<LLMStreamEvent>) : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
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
                model: String
        ): Flow<LLMStreamEvent> = flow { events.forEach { emit(it) } }
}

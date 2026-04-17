package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.session.SessionServices
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentErrorRecoveryTest {

        @Test
        fun `dns failure is non recoverable`() = runTest {
                val services =
                        buildServices(
                                AgentErrorTestLLMClient(
                                        UnknownHostException("Unable to resolve host")
                                )
                        )
                val agent =
                        Agent(
                                config =
                                        AgentExecutionConfig(
                                                goal = "goal",
                                                sessionId = SessionId.generate(),
                                                maxTurns = 1,
                                                uiSettleDelayMs = 0,
                                                systemPrompt = "test prompt"
                                        ),
                                services = services,
                                eventEmitter = {},
                                cancellationSignal = CompletableDeferred()
                        )

                val result = agent.run()

                assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
        }

        @Test
        fun `transient network error stops with error when no retry budget remains`() = runTest {
                val services =
                        buildServices(AgentErrorTestLLMClient(SocketTimeoutException("timeout")))
                val agent =
                        Agent(
                                config =
                                        AgentExecutionConfig(
                                                goal = "goal",
                                                sessionId = SessionId.generate(),
                                                maxTurns = 1,
                                                uiSettleDelayMs = 0,
                                                systemPrompt = "test prompt"
                                        ),
                                services = services,
                                eventEmitter = {},
                                cancellationSignal = CompletableDeferred()
                        )

                val result = agent.run()

                assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
        }

        @Test
        fun `context length exceeded is non recoverable`() = runTest {
                val services =
                        buildServices(
                                AgentErrorTestLLMClient(
                                        RuntimeException("maximum context length exceeded")
                                )
                        )
                val agent =
                        Agent(
                                config =
                                        AgentExecutionConfig(
                                                goal = "goal",
                                                sessionId = SessionId.generate(),
                                                maxTurns = 1,
                                                uiSettleDelayMs = 0,
                                                systemPrompt = "test prompt"
                                        ),
                                services = services,
                                eventEmitter = {},
                                cancellationSignal = CompletableDeferred()
                        )

                val result = agent.run()

                assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
        }
}

private fun buildServices(llmClient: LLMClient): SessionServices {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val platform = FakeAndroidPlatform()
        val config =
                SessionConfig(
                        maxTurns = 1,
                        actionDelayMs = 0,
                        llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
                )
        val testCatalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
                )
        return SessionServices(
                toolRegistry = toolRegistry,
                toolRouter = toolRouter,
                historyManager = HistoryManager(),
                sessionState = ai.closepaw.session.AgentSessionState(),
                policyEngine = policyEngine,
                appClassifier = AppClassifier(emptyMap()),
                platform = platform,
                config = config,
                llmClient = llmClient,
                modelCatalog = testCatalog,
                llmClientFactory = LLMClientFactory.forTest(testCatalog, llmClient),
                traceRecorder = NoopTraceRecorder,
                recordingService = io.mockk.mockk(relaxed = true)
        )
}

private class AgentErrorTestLLMClient(private val throwable: Throwable) : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): ResponsesResult {
                throw throwable
        }

        override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): Flow<LLMStreamEvent> = flow { throw throwable }
}

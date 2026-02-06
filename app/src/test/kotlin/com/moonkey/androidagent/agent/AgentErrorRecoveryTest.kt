package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class AgentErrorRecoveryTest {

    @Test
    fun `dns failure is non recoverable`() = runTest {
        val services = buildServices(AgentErrorTestLLMClient(UnknownHostException("Unable to resolve host")))
        val agent = Agent(
            config = AgentExecutionConfig(
                goal = "goal",
                sessionId = SessionId.generate(),
                maxTurns = 1,
                uiSettleDelayMs = 0,
                systemPrompt = "test prompt"
            ),
            services = services,
            eventEmitter = { },
            cancellationSignal = CompletableDeferred()
        )

        val result = agent.run()

        assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
    }

    @Test
    fun `transient network error stops with error when no retry budget remains`() = runTest {
        val services = buildServices(AgentErrorTestLLMClient(SocketTimeoutException("timeout")))
        val agent = Agent(
            config = AgentExecutionConfig(
                goal = "goal",
                sessionId = SessionId.generate(),
                maxTurns = 1,
                uiSettleDelayMs = 0,
                systemPrompt = "test prompt"
            ),
            services = services,
            eventEmitter = { },
            cancellationSignal = CompletableDeferred()
        )

        val result = agent.run()

        assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
    }

    @Test
    fun `context length exceeded is non recoverable`() = runTest {
        val services = buildServices(AgentErrorTestLLMClient(RuntimeException("maximum context length exceeded")))
        val agent = Agent(
            config = AgentExecutionConfig(
                goal = "goal",
                sessionId = SessionId.generate(),
                maxTurns = 1,
                uiSettleDelayMs = 0,
                systemPrompt = "test prompt"
            ),
            services = services,
            eventEmitter = { },
            cancellationSignal = CompletableDeferred()
        )

        val result = agent.run()

        assertThat(result).isInstanceOf(AgentStopReason.Error::class.java)
    }
}

private fun buildServices(llmClient: LLMClient): SessionServices {
    val toolRegistry = ToolRegistry()
    val policyEngine = PolicyEngine()
    val toolRouter = ToolRouter(toolRegistry, policyEngine)
    val platform = FakeAndroidPlatform()
    val config = SessionConfig(
        maxTurns = 1,
        actionDelayMs = 0,
        llmBackend = LLMBackendType.OPENAI
    )
    return SessionServices(
        toolRegistry = toolRegistry,
        toolRouter = toolRouter,
        historyManager = HistoryManager(),
        sessionState = com.moonkey.androidagent.session.AgentSessionState(),
        policyEngine = policyEngine,
        platform = platform,
        config = config,
        llmClient = llmClient,
        traceRecorder = NoopTraceRecorder
    )
}

private class AgentErrorTestLLMClient(
    private val throwable: Throwable
) : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): ResponsesResult {
        throw throwable
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): Flow<LLMStreamEvent> = flow {
        throw throwable
    }
}

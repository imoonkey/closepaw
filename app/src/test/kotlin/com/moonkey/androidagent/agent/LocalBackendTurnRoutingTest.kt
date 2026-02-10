package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalBackendTurnRoutingTest {

    @Test
    fun `local backend uses local llm client without cloud api keys`() = runTest {
        val localClient = LocalBackendTestLLMClient()
        val catalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
                )

        @Suppress("DEPRECATION")
        val sessionConfig =
                SessionConfig(
                        llmBackend = LLMBackendType.LOCAL,
                        maxTurns = 1,
                        actionDelayMs = 0
                )
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine()
        val services =
                SessionServices(
                        toolRegistry = toolRegistry,
                        toolRouter = ToolRouter(toolRegistry, policyEngine),
                        historyManager = HistoryManager(),
                        sessionState = AgentSessionState(),
                        policyEngine = policyEngine,
                        platform = FakeAndroidPlatform(),
                        config = sessionConfig,
                        llmClient = localClient,
                        modelCatalog = catalog,
                        llmClientFactory = LLMClientFactory(catalog = catalog, apiKeyResolver = { null }),
                        traceRecorder = NoopTraceRecorder
                )

        val agent =
                Agent(
                        config =
                                AgentExecutionConfig(
                                        goal = "Say done",
                                        sessionId = SessionId("local-routing-test"),
                                        maxTurns = 1,
                                        uiSettleDelayMs = 0,
                                        systemPrompt = "You are a test agent.",
                                        modelName = "gpt-5.2"
                                ),
                        services = services,
                        eventEmitter = {},
                        cancellationSignal = CompletableDeferred()
                )

        val stopReason = agent.run()
        assertThat(stopReason).isEqualTo(AgentStopReason.GoalAchieved)
        assertThat(localClient.streamingCalls).isEqualTo(1)
    }
}

private class LocalBackendTestLLMClient : LLMClient() {
    var streamingCalls: Int = 0

    override suspend fun chatWithTools(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): ResponsesResult {
        return ResponsesResult(textContent = "done", toolCalls = emptyList(), responseId = "local")
    }

    override fun chatWithToolsStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
    ): Flow<LLMStreamEvent> = flow {
        streamingCalls += 1
        emit(LLMStreamEvent.Created("local"))
        emit(LLMStreamEvent.TextDelta("done"))
        emit(LLMStreamEvent.Completed)
    }
}

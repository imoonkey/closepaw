package com.moonkey.androidagent.session

import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.AppClassifier
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.trace.TraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ResponsesResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SessionServicesCleanupTest {

    @Test
    fun `cleanup continues after llmClient failure`() = runBlocking {
        val throwingClient = object : LLMClient() {
            var cleanupCalled = false
            override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): ResponsesResult = error("unused")

            override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): Flow<LLMStreamEvent> = flow { error("unused") }

            override suspend fun cleanup() {
                cleanupCalled = true
                throw RuntimeException("boom")
            }
        }

        val traceRecorder = mockk<TraceRecorder>(relaxed = true)
        val factory = mockk<LLMClientFactory>(relaxed = true)
        coEvery { factory.cleanupAll() } returns Unit

        val services = buildServices(throwingClient, factory, traceRecorder)

        services.cleanup()

        assert(throwingClient.cleanupCalled) { "llmClient.cleanup must be invoked" }
        coVerify { factory.cleanupAll() }
        coVerify { traceRecorder.close() }
    }

    @Test
    fun `cleanup closes trace even if factory cleanup throws`() = runBlocking {
        val factory = mockk<LLMClientFactory>(relaxed = true)
        coEvery { factory.cleanupAll() } throws RuntimeException("factory fail")
        val traceRecorder = mockk<TraceRecorder>(relaxed = true)

        val services = buildServices(object : LLMClient() {
            override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): ResponsesResult = error("unused")

            override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): Flow<LLMStreamEvent> = flow { error("unused") }
        }, factory, traceRecorder)

        services.cleanup()

        coVerify { traceRecorder.close() }
    }

    private fun buildServices(
        llmClient: LLMClient,
        factory: LLMClientFactory,
        trace: TraceRecorder
    ): SessionServices {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val catalog =
            ModelCatalog.fromJson(
                """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
            )
        return SessionServices(
            toolRegistry = toolRegistry,
            toolRouter = toolRouter,
            historyManager = HistoryManager(),
            sessionState = AgentSessionState(),
            policyEngine = policyEngine,
            appClassifier = AppClassifier(emptyMap()),
            platform = FakeAndroidPlatform(),
            config = SessionConfig(
                maxTurns = 1,
                actionDelayMs = 0,
                llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
            ),
            llmClient = llmClient,
            modelCatalog = catalog,
            llmClientFactory = factory,
            traceRecorder = trace,
            recordingService = mockk(relaxed = true)
        )
    }
}

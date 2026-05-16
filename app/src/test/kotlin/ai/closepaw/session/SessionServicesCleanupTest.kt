package ai.closepaw.session

import ai.closepaw.history.HistoryManager
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.browser.cdp.CdpConnection
import ai.closepaw.browser.cdp.CdpConnectionClosedException
import ai.closepaw.browser.cdp.CdpConnectionFactory
import ai.closepaw.browser.cdp.shizuku.DevtoolsVersion
import ai.closepaw.browser.cdp.shizuku.PageTarget
import ai.closepaw.browser.script.BrowserDevtoolsBridge
import ai.closepaw.browser.script.BrowserScriptExecutor
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionLlmConfig
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import ai.closepaw.trace.TraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.test.buildTestContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
                model: String,
        maxOutputTokens: Long?,
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

        val result = services.cleanup()

        assert(throwingClient.cleanupCalled) { "llmClient.cleanup must be invoked" }
        coVerify { factory.cleanupAll() }
        coVerify { traceRecorder.close() }
        check(result is CleanupResult.PartialFailure) { "expected PartialFailure, got $result" }
        assert(result.failures.any { it.step == "llmClient.cleanup" }) { "missing llmClient failure: ${result.failures}" }
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
                model: String,
        maxOutputTokens: Long?,
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

    @Test
    fun `cleanup returns Success when all steps succeed`() = runBlocking {
        val factory = mockk<LLMClientFactory>(relaxed = true)
        coEvery { factory.cleanupAll() } returns Unit
        val traceRecorder = mockk<TraceRecorder>(relaxed = true)
        val client = object : LLMClient() {
            override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String,
        maxOutputTokens: Long?,
            ): ResponsesResult = error("unused")

            override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
            ): Flow<LLMStreamEvent> = flow { error("unused") }
        }

        val result = buildServices(client, factory, traceRecorder).cleanup()

        check(result is CleanupResult.Success) { "expected Success, got $result" }
    }

    @Test
    fun `cleanup closes browser session manager`() = runBlocking {
        val bridge = FakeBrowserBridge()
        val connectionFactory = FakeCdpConnectionFactory()
        val context = buildTestContext(java.io.File("/tmp/session-browser-cleanup")).also {
            every { it.applicationContext } returns it
        }
        val scope = CoroutineScope(SupervisorJob())
        val manager = BrowserSessionManager(
            context = context,
            sessionScope = scope,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = { bridge },
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, _, _ ->
                BrowserScriptExecutor { _, _ -> ScriptResult.Ok("\"ok\"") }
            },
        )
        val runResult = manager.run("return 1", 1_000)
        check(runResult is ScriptResult.Ok) { "expected fake browser_script success, got $runResult" }

        val factory = mockk<LLMClientFactory>(relaxed = true)
        coEvery { factory.cleanupAll() } returns Unit
        val services = buildServices(
            llmClient = object : LLMClient() {
                override suspend fun chatWithTools(
                    systemPrompt: String,
                    inputItems: List<ResponseInputItem>,
                    tools: List<FunctionTool>,
                    model: String,
        maxOutputTokens: Long?,
                ): ResponsesResult = error("unused")

                override fun chatWithToolsStreaming(
                    systemPrompt: String,
                    inputItems: List<ResponseInputItem>,
                    tools: List<FunctionTool>,
                    model: String
                ): Flow<LLMStreamEvent> = flow { error("unused") }
            },
            factory = factory,
            trace = mockk(relaxed = true),
            browserSessionManager = manager
        )

        services.cleanup()

        assert(bridge.closeCalls == 1) { "expected browser bridge close once, got ${bridge.closeCalls}" }
        assert(connectionFactory.connections.single().closeCalls == 1) {
            "expected CDP connection close once, got ${connectionFactory.connections.single().closeCalls}"
        }
    }

    private fun buildServices(
        llmClient: LLMClient,
        factory: LLMClientFactory,
        trace: TraceRecorder,
        browserSessionManager: BrowserSessionManager? = null
    ): SessionServices {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val catalog =
            ModelCatalog.fromJson(
                """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
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
                actionDelayMs = 0,
                llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
            ),
            llmClient = llmClient,
            modelCatalog = catalog,
            llmClientFactory = factory,
            traceRecorder = trace,
            recordingService = mockk(relaxed = true),
            browserSessionManager = browserSessionManager
        )
    }

    private class FakeBrowserBridge : BrowserDevtoolsBridge {
        var closeCalls = 0
            private set

        override suspend fun preflight() = Unit

        override suspend fun fetchVersion(): DevtoolsVersion =
            DevtoolsVersion(
                browser = "Chrome/130",
                protocolVersion = "1.3",
                webSocketDebuggerUrl = "ws://test",
                userAgent = null,
            )

        override suspend fun listPageTargets(): List<PageTarget> = emptyList()

        override fun close() {
            closeCalls++
        }
    }

    private class FakeCdpConnectionFactory : CdpConnectionFactory {
        val connections = mutableListOf<FakeCdpConnection>()

        override suspend fun connect(
            url: String,
            onMessage: (String) -> Unit,
            onFailure: (Throwable) -> Unit,
            onClosed: (CdpConnectionClosedException) -> Unit,
        ): CdpConnection {
            val connection = FakeCdpConnection(onMessage)
            connections.add(connection)
            return connection
        }
    }

    private class FakeCdpConnection(
        private val onMessage: (String) -> Unit,
    ) : CdpConnection {
        var closeCalls = 0
            private set

        override fun send(text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]!!.jsonPrimitive.int
            val method = request["method"]!!.jsonPrimitive.content
            val result = when (method) {
                "Target.createTarget" -> buildJsonObject { put("targetId", "blank-page") }
                "Target.attachToTarget" -> buildJsonObject { put("sessionId", "session-1") }
                else -> buildJsonObject { }
            }
            onMessage(
                buildJsonObject {
                    put("id", id)
                    put("result", result)
                }.toString()
            )
        }

        override fun close() {
            closeCalls++
        }
    }
}

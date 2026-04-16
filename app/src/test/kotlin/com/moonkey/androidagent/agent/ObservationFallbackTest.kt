package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.session.AgentSessionState
import com.moonkey.androidagent.session.SessionServices
import com.moonkey.androidagent.tool.AppClassifier
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.trace.AgentTrace
import com.moonkey.androidagent.trace.NoopTraceRecorder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/**
 * Regression coverage for er-harden-cleanup: when captureObservationWithSnapshot()
 * throws (e.g. platform.captureScreen fails), the tool is still marked executed
 * and execution continues without propagating the exception.
 */
class ObservationFallbackTest {

    @Test
    fun `screen capture failure falls back to text observation and marks tool executed`() = runTest {
        val platform = ThrowingCapturePlatform()
        val tool = NoObservationTool()
        val registry = ToolRegistry().apply { register(tool) }
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap())).apply {
            setApprovalMode(ApprovalMode.AUTO_APPROVE)
        }
        val catalog = ModelCatalog.fromJson(
            """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
        )
        val sessionConfig = SessionConfig(
            maxTurns = 1,
            actionDelayMs = 0,
            llm = SessionLlmConfig(backendType = LLMBackendType.OPENAI)
        )
        val services = SessionServices(
            toolRegistry = registry,
            toolRouter = ToolRouter(registry, policyEngine),
            historyManager = HistoryManager(),
            sessionState = AgentSessionState(),
            policyEngine = policyEngine,
            appClassifier = AppClassifier(emptyMap()),
            platform = platform,
            config = sessionConfig,
            llmClient = mockk(relaxed = true),
            modelCatalog = catalog,
            llmClientFactory = LLMClientFactory(catalog = catalog, apiKeyResolver = { null }),
            traceRecorder = NoopTraceRecorder,
            recordingService = mockk(relaxed = true)
        )
        val sessionId = SessionId.generate()
        val trace = AgentTrace(sessionId, services)
        val dispatcher = AgentEventDispatcher(sessionId) { /* noop */ }
        val execConfig = AgentExecutionConfig(
            goal = "goal",
            sessionId = sessionId,
            maxTurns = 1,
            uiSettleDelayMs = 0,
            systemPrompt = "p"
        )
        val runner = TurnExecutionPhaseRunner(execConfig, services, dispatcher, trace)

        val toolCall = ToolCallRequest(
            id = "call-1",
            name = tool.name,
            arguments = JSONObject()
        )
        val initialSnapshot = ScreenSnapshot(timestamp = 0L, elements = emptyList())

        val result = runner.executeActions(
            turnId = "turn-1",
            turnNumber = 0,
            initialSnapshot = initialSnapshot,
            toolCallsToExecute = listOf(toolCall)
        )

        assertThat(result.executedToolIds).containsExactly("call-1")
        assertThat(result.terminatedEarly).isFalse()
        assertThat(result.lastTerminalResult).isInstanceOf(ToolCallResult.Success::class.java)
        assertThat(platform.captureAttempts).isEqualTo(1)
    }
}

private class ThrowingCapturePlatform : AndroidPlatform {
    var captureAttempts: Int = 0
    override suspend fun captureScreen(): ScreenSnapshot {
        captureAttempts++
        throw RuntimeException("capture failed")
    }
    override suspend fun performAction(action: UIAction): ActionResult = ActionResult.Success()
    override fun hasRequiredPermissions(): Boolean = true
    override fun getCurrentPackageName(): String? = "com.example.fake"
    override fun getDisplayInfo(): DisplayInfo =
        DisplayInfo(widthPixels = 1080, heightPixels = 1920, density = 2f)
    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()
    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}

private class NoObservationTool : ToolSpec {
    override val name: String = "test_fallback_tool"
    override val description: String = "test tool returning Success with no observation"
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult = ValidationResult.Valid

    override fun createInvocation(params: JSONObject): ToolInvocation = object : ToolInvocation {
        override val toolName: String = name
        override val params: JSONObject = params
        override fun getDescription(): String = "test"
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.Success(output = "ok", observation = null)
    }
}

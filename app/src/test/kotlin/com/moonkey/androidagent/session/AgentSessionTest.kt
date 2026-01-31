package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionState
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {

    @Test
    fun `shutdown from running emits session completed user stopped`() = runTest {
        val session = buildSession(
            scope = this,
            captureDelayMs = 1_000L,
            llmDelayMs = 0L
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { session.events.collect { events.add(it) } }

        session.submit(Op.UserInput("goal"))
        assertThat(session.state.value).isEqualTo(SessionState.Running)

        session.submit(Op.Shutdown)
        advanceUntilIdle()

        assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
        val completed = events.filterIsInstance<AgentEvent.SessionCompleted>().single()
        assertThat(completed.reason).isEqualTo(CompletionReason.USER_STOPPED)

        job.cancel()
    }
}

private fun buildSession(
    scope: kotlinx.coroutines.CoroutineScope,
    captureDelayMs: Long,
    llmDelayMs: Long,
    maxTurns: Int = 2
): AgentSession {
    val toolRegistry = ToolRegistry()
    val policyEngine = PolicyEngine()
    val toolRouter = ToolRouter(toolRegistry, policyEngine)
    val platform = SessionTestAndroidPlatform(captureDelayMs)
    val config = SessionConfig(maxTurns = maxTurns, actionDelayMs = 0)
    val services = SessionServices(
        toolRegistry = toolRegistry,
        toolRouter = toolRouter,
        historyManager = HistoryManager(),
        policyEngine = policyEngine,
        platform = platform,
        config = config,
        llmClient = SessionTestLLMClient(llmDelayMs)
    )
    val service = mockk<AccessibilityService>(relaxed = true)
    return AgentSession.createWithServices(
        config = config,
        service = service,
        scope = scope,
        services = services
    )
}

private class SessionTestAndroidPlatform(
    private val captureDelayMs: Long
) : AndroidPlatform {
    override suspend fun captureScreen(): ScreenSnapshot {
        if (captureDelayMs > 0) {
            delay(captureDelayMs)
        }
        return ScreenSnapshot(timestamp = System.currentTimeMillis(), elements = emptyList())
    }

    override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
        return ActionResult.Success()
    }

    override fun hasRequiredPermissions(): Boolean = true

    override fun getCurrentPackageName(): String? = "com.example.fake"

    override fun getDisplayInfo(): DisplayInfo = DisplayInfo(
        widthPixels = 1080,
        heightPixels = 1920,
        density = 2f
    )

    override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

    override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
}

private class SessionTestLLMClient(
    private val delayMs: Long
) : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: com.openai.models.ChatModel
    ): ResponsesResult {
        return ResponsesResult(textContent = "done", toolCalls = emptyList(), responseId = "resp")
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

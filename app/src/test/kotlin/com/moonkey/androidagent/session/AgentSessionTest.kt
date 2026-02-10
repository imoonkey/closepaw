package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionState
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.trace.NoopTraceRecorder
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

    @Test
    fun `session lifecycle remains stable for all agent modes`() = runTest {
        listOf(AgentMode.BASIC, AgentMode.PRO).forEach { mode ->
            val session = buildSession(
                scope = this,
                captureDelayMs = 1_000L,
                llmDelayMs = 0L,
                agentMode = mode
            )
            val events = mutableListOf<AgentEvent>()
            val job = launch { session.events.collect { events.add(it) } }

            session.submit(Op.UserInput("goal-$mode"))
            assertThat(session.state.value).isEqualTo(SessionState.Running)

            session.submit(Op.Shutdown)
            advanceUntilIdle()

            assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
            val completed = events.filterIsInstance<AgentEvent.SessionCompleted>().single()
            assertThat(completed.reason).isEqualTo(CompletionReason.USER_STOPPED)

            job.cancel()
        }
    }
}

private fun buildSession(
    scope: kotlinx.coroutines.CoroutineScope,
    captureDelayMs: Long,
    llmDelayMs: Long,
    maxTurns: Int = 2,
    agentMode: AgentMode = AgentMode.PRO
): AgentSession {
    val toolRegistry = ToolRegistry()
    val policyEngine = PolicyEngine()
    val toolRouter = ToolRouter(toolRegistry, policyEngine)
    val platform = FakeAndroidPlatform(captureDelayMs = captureDelayMs)
    val config = SessionConfig(maxTurns = maxTurns, actionDelayMs = 0, agentMode = agentMode)
    val services = SessionServices(
        toolRegistry = toolRegistry,
        toolRouter = toolRouter,
        historyManager = HistoryManager(),
        sessionState = AgentSessionState(),
        policyEngine = policyEngine,
        platform = platform,
        config = config,
        llmClient = SessionTestLLMClient(llmDelayMs),
        traceRecorder = NoopTraceRecorder
    )
    val service = mockk<AccessibilityService>(relaxed = true)
    return AgentSession.createWithServices(
        config = config,
        service = service,
        scope = scope,
        services = services
    )
}

private class SessionTestLLMClient(
    private val delayMs: Long
) : LLMClient() {
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult {
        return ResponsesResult(textContent = "done", toolCalls = emptyList(), responseId = "resp")
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

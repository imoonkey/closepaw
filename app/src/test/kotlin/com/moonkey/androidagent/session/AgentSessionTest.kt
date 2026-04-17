package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.llm.ResponsesResult
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.tool.AppClassifier
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {

        @Test
        fun `shutdown from running emits session completed user stopped`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 1_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                job.cancel()
        }

        @Test
        fun `session lifecycle remains stable for all agent modes`() = runTest {
                listOf(AgentMode.BASIC, AgentMode.PRO).forEach { mode ->
                        val session =
                                buildSession(
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
                        val completed =
                                events.filterIsInstance<SessionCompleted>().single()
                        assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                        job.cancel()
                }
        }

        @Test
        fun `streaming failure still emits task completed error`() = runTest {
                val session =
                        buildSession(
                                scope = this,
                                captureDelayMs = 0L,
                                llmDelayMs = 0L,
                                llmClient = FailingStreamingSessionTestLLMClient()
                        )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                advanceUntilIdle()

                assertThat(session.state.value).isNotEqualTo(SessionState.Running)
                val completed = events.filterIsInstance<TaskCompleted>().single()
                assertThat(completed.outcome).isEqualTo(TaskOutcome.ERROR)
                assertThat(completed.result).contains("synthetic stream failure")

                job.cancel()
        }

        // ===== Lifecycle Serialization Tests =====

        @Test
        fun `completion and shutdown back to back yields Shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 50L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))

                // Schedule shutdown to arrive around when the agent completes
                launch {
                        delay(50L)
                        session.submit(Op.Shutdown)
                }
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                assertThat(events.filterIsInstance<SessionCompleted>()).hasSize(1)

                job.cancel()
        }

        @Test
        fun `two UserInput ops only starts one task`() = runTest {
                // Long capture delay keeps agent busy so it can't complete between submits
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("first"))
                session.submit(Op.UserInput("second"))

                // Shutdown + advanceUntilIdle to process all events before asserting
                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(events.filterIsInstance<TaskStarted>()).hasSize(1)

                job.cancel()
        }

        @Test
        fun `duplicate shutdown emits exactly one SessionCompleted`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))

                session.submit(Op.Shutdown)
                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                assertThat(events.filterIsInstance<SessionCompleted>()).hasSize(1)

                job.cancel()
        }

        // ===== Takeover / TakeoverPending Tests =====

        @Test
        fun `resume rejected while takeover still pending`() = runTest {
                // Long capture delay keeps agent busy so it can't confirm pause immediately
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                // Start takeover — it suspends waiting for agent to confirm pause
                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield() // Let takeover coroutine set TakeoverPending and suspend at await

                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                // Resume should be rejected — agent hasn't paused yet
                session.submit(Op.Resume)
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)
                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()

                // Clean up
                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `paused not observable before pause confirmation`() = runTest {
                // Use shorter delay so agent can complete and confirm the pause
                val session = buildSession(scope = this, captureDelayMs = 50L, llmDelayMs = 0L)
                val states = mutableListOf<SessionState>()
                val stateJob = launch { session.state.collect { states.add(it) } }
                val events = mutableListOf<AgentEvent>()
                val eventJob = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))

                // Start takeover
                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield() // TakeoverPending should be set before Paused

                // State must be TakeoverPending (not Paused yet)
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                // Advance so the agent finishes its turn and confirms the pause
                advanceUntilIdle()

                // Verify TakeoverPending appeared before Paused in the state sequence
                val pendingIdx = states.indexOf(SessionState.TakeoverPending)
                assertThat(pendingIdx).isGreaterThan(-1)
                val pausedIdx = states.indexOf(SessionState.Paused)
                // Paused may or may not appear (agent might complete before confirming pause),
                // but if it does, it must come after TakeoverPending
                if (pausedIdx != -1) {
                        assertThat(pausedIdx).isGreaterThan(pendingIdx)
                }

                // Clean up
                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                stateJob.cancel()
                eventJob.cancel()
        }

        @Test
        fun `takeover and resume events emitted in valid order`() = runTest {
                // Long capture delay keeps agent busy so it can't confirm pause
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()

                // State is TakeoverPending — agent hasn't confirmed pause yet
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)
                // SessionTakeover must not be emitted until agent confirms
                assertThat(events.filterIsInstance<SessionTakeover>()).isEmpty()
                // SessionResumed never appears without SessionTakeover
                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()

                // Shutdown and verify invariant holds across all emitted events
                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()

                events.forEachIndexed { idx, event ->
                        if (event is SessionResumed) {
                                val hasPrecedingTakeover =
                                        events.subList(0, idx).any { it is SessionTakeover }
                                assertThat(hasPrecedingTakeover).isTrue()
                        }
                }

                job.cancel()
        }
        // ===== Shutdown Reason Semantics Tests =====

        @Test
        fun `manual shutdown from idle emits user stopped not idle timeout`() = runTest {
                // Fast agent — completes immediately, transitions to Idle
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                // Advance enough for agent to complete but NOT the 300s idle timeout
                advanceTimeBy(1_000L)

                // Session should be in Idle after agent completes
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                // Manual shutdown from Idle
                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                job.cancel()
        }

        @Test
        fun `idle timeout emits idle timeout reason`() = runTest {
                // Fast agent — completes immediately, transitions to Idle with timeout armed
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                // Advance enough for agent to complete but NOT the 300s idle timeout
                advanceTimeBy(1_000L)

                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                // Advance past the 5-minute idle timeout (300_000ms total from agent completion)
                advanceTimeBy(300_000L)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.IDLE_TIMEOUT)

                job.cancel()
        }
}

private fun buildSession(
        scope: kotlinx.coroutines.CoroutineScope,
        captureDelayMs: Long,
        llmDelayMs: Long,
        maxTurns: Int = 2,
        agentMode: AgentMode = AgentMode.PRO,
        llmClient: LLMClient? = null
): AgentSession {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val platform = FakeAndroidPlatform(captureDelayMs = captureDelayMs)
        val config = SessionConfig(maxTurns = maxTurns, actionDelayMs = 0, agentMode = agentMode)
        val testCatalog =
                ModelCatalog.fromJson(
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
                )
        val testLlm = llmClient ?: SessionTestLLMClient(llmDelayMs)
        val services =
                SessionServices(
                        toolRegistry = toolRegistry,
                        toolRouter = toolRouter,
                        historyManager = HistoryManager(),
                        sessionState = AgentSessionState(),
                        policyEngine = policyEngine,
                        appClassifier = AppClassifier(emptyMap()),
                        platform = platform,
                        config = config,
                        llmClient = testLlm,
                        modelCatalog = testCatalog,
                        llmClientFactory = LLMClientFactory.forTest(testCatalog, testLlm),
                        traceRecorder = NoopTraceRecorder,
                        recordingService = mockk(relaxed = true)
                )
        val service = mockk<AccessibilityService>(relaxed = true)
        return AgentSession.createWithServices(
                config = config,
                service = service,
                scope = scope,
                services = services
        )
}

private class SessionTestLLMClient(private val delayMs: Long) : LLMClient() {
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

private class FailingStreamingSessionTestLLMClient : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): ResponsesResult {
                throw UnsupportedOperationException("Not used in this test")
        }

        override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): Flow<LLMStreamEvent> = callbackFlow {
                trySend(LLMStreamEvent.Failed("synthetic stream failure"))
                close(RuntimeException("synthetic stream failure"))
                awaitClose {}
        }
}

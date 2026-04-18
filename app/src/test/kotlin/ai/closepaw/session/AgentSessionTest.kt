package ai.closepaw.session

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.ConversationConfigSnapshot
import ai.closepaw.history.model.SessionRuntimeSnapshot
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.protocol.*
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
                // Drive a full Takeover -> Paused -> Resume cycle and assert event ordering.
                val gatedLlm = GatedStreamingLLMClient()
                val session = buildSession(
                        scope = this,
                        captureDelayMs = 0L,
                        llmDelayMs = 0L,
                        maxTurns = 5,
                        llmClient = gatedLlm
                )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                gatedLlm.streamStarted.await()

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                gatedLlm.release(0)
                runCurrent()
                takeoverJob.join()
                assertThat(session.state.value).isEqualTo(SessionState.Paused)

                session.submit(Op.Resume)
                yield()

                gatedLlm.release(1)
                session.submit(Op.Shutdown)
                advanceUntilIdle()

                val takeoverIdx = events.indexOfFirst { it is SessionTakeover }
                val resumedIdx = events.indexOfFirst { it is SessionResumed }
                assertThat(takeoverIdx).isAtLeast(0)
                assertThat(resumedIdx).isGreaterThan(takeoverIdx)

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

        // ===== Approval Policy Invariant =====

        @Test
        fun `stale Op Approve does not mutate allow-list`() = runTest {
                val spiedPolicyEngine = spyk(PolicyEngine(appClassifier = AppClassifier(emptyMap())))
                val toolRegistry = ToolRegistry()
                val toolRouter = ToolRouter(toolRegistry, spiedPolicyEngine)
                val platform = FakeAndroidPlatform(captureDelayMs = 0L)
                val config = SessionConfig(maxTurns = 2, actionDelayMs = 0)
                val testCatalog =
                        ModelCatalog.fromJson(
                                """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
                        )
                val testLlm = SessionTestLLMClient(0L)
                val services =
                        SessionServices(
                                toolRegistry = toolRegistry,
                                toolRouter = toolRouter,
                                historyManager = HistoryManager(),
                                sessionState = AgentSessionState(),
                                policyEngine = spiedPolicyEngine,
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
                val session =
                        AgentSession.createWithServices(
                                config = config,
                                service = service,
                                scope = this,
                                services = services
                        )

                // No pending approval exists for this actionId; router.resolveApproval returns false.
                session.submit(
                        Op.Approve(
                                actionId = "stale-action",
                                decision = ApprovalDecision.APPROVED,
                                scope = ApprovalScope.ALWAYS,
                                packageName = "com.example.untrusted"
                        )
                )
                advanceUntilIdle()

                verify(exactly = 0) { spiedPolicyEngine.allowPackagePersistent(any()) }
                verify(exactly = 0) { spiedPolicyEngine.allowPackageForSession(any()) }

                session.submit(Op.Shutdown)
                advanceUntilIdle()
        }

        // ===== Checkpoint schema versioning =====

        @Test
        fun `reload returns null for v1 schema snapshot`() {
                val snapshot = SessionRuntimeSnapshot(
                        schemaVersion = 1,
                        sessionId = "old-session",
                        config = ConversationConfigSnapshot(
                                mainModel = "gpt-5.2",
                                agentMode = "PRO",
                                maxTurns = 1,
                                perceptionMode = "DEFAULT",
                                platformMode = "DEFAULT",
                        ),
                        historyItems = emptyList(),
                        todos = emptyList(),
                        checkpointState = CheckpointState.IDLE_READY,
                        lastCheckpointAt = 0L,
                )
                val service = mockk<AccessibilityService>(relaxed = true)
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

                val result = AgentSession.reload(
                        snapshot = snapshot,
                        service = service,
                        scope = scope,
                        authStore = null,
                )

                assertThat(result).isNull()
        }

        // ===== FSM Transition Coverage =====
        // Spec source: app/src/main/kotlin/ai/closepaw/protocol/SessionState.kt
        // Guards live in AgentSession.handle*() — these tests exercise every valid
        // transition and every guard rejection in the FSM.

        // ---- Valid transitions ----

        @Test
        fun `created to shutdown direct without task`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                assertThat(session.state.value).isEqualTo(SessionState.Created)

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                assertThat(events.filterIsInstance<TaskStarted>()).isEmpty()
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                job.cancel()
        }

        @Test
        fun `idle to running on followup user input`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("first"))
                advanceTimeBy(1_000L)
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.UserInput("second"))
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.Running)
                assertThat(events.filterIsInstance<TaskStarted>().map { it.input })
                        .containsExactly("first", "second")
                        .inOrder()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `running to idle on task completion`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                advanceTimeBy(1_000L)

                assertThat(session.state.value).isEqualTo(SessionState.Idle)
                assertThat(events.filterIsInstance<TaskCompleted>()).hasSize(1)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `running to takeover pending on takeover op`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val job = launch { session.events.collect { } }

                session.submit(Op.UserInput("goal"))
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()

                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `paused to running via resume`() = runTest {
                // Deterministic Paused arrival: a gated LLM holds turn 1 inside the
                // streaming call. We submit Takeover (sets pauseState), release the
                // gate to let turn 1 finish, then the agent loops, observes pauseState,
                // confirms the pause, and handleTakeover transitions to Paused.
                val gatedLlm = GatedStreamingLLMClient()
                val session = buildSession(
                        scope = this,
                        captureDelayMs = 0L,
                        llmDelayMs = 0L,
                        maxTurns = 5,
                        llmClient = gatedLlm
                )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                gatedLlm.streamStarted.await()
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                gatedLlm.release(0)         // turn 1 LLM completes
                runCurrent()
                takeoverJob.join()

                assertThat(session.state.value).isEqualTo(SessionState.Paused)
                assertThat(events.filterIsInstance<SessionTakeover>()).hasSize(1)

                session.submit(Op.Resume)
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.Running)
                assertThat(events.filterIsInstance<SessionResumed>()).hasSize(1)

                gatedLlm.release(1)         // unblock turn 2 so agent can finish naturally
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `paused to shutdown allowed`() = runTest {
                val gatedLlm = GatedStreamingLLMClient()
                val session = buildSession(
                        scope = this,
                        captureDelayMs = 0L,
                        llmDelayMs = 0L,
                        maxTurns = 5,
                        llmClient = gatedLlm
                )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                gatedLlm.streamStarted.await()

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                gatedLlm.release(0)
                runCurrent()
                takeoverJob.join()
                assertThat(session.state.value).isEqualTo(SessionState.Paused)

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                job.cancel()
        }

        @Test
        fun `takeover pending to shutdown allowed`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                val completed = events.filterIsInstance<SessionCompleted>().single()
                assertThat(completed.reason).isEqualTo(SessionEndReason.USER_STOPPED)

                takeoverJob.cancel()
                job.cancel()
        }

        // ---- Guard rejections ----

        @Test
        fun `userinput rejected after shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                session.submit(Op.UserInput("late"))
                advanceUntilIdle()

                assertThat(events.filterIsInstance<TaskStarted>()).isEmpty()
                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)

                job.cancel()
        }

        @Test
        fun `userinput rejected while running`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("first"))
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                session.submit(Op.UserInput("second"))
                yield()
                assertThat(events.filterIsInstance<TaskStarted>()).hasSize(1)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `userinput rejected while takeover pending`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("first"))
                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                session.submit(Op.UserInput("second"))
                yield()

                assertThat(events.filterIsInstance<TaskStarted>()).hasSize(1)

                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `resume rejected in created`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Resume)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Created)
                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `resume rejected in running`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                session.submit(Op.Resume)
                yield()

                assertThat(session.state.value).isEqualTo(SessionState.Running)
                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `resume rejected in idle`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                advanceTimeBy(1_000L)
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.Resume)
                yield()

                assertThat(session.state.value).isEqualTo(SessionState.Idle)
                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `resume rejected after shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                session.submit(Op.Resume)
                advanceUntilIdle()

                assertThat(events.filterIsInstance<SessionResumed>()).isEmpty()
                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)

                job.cancel()
        }

        @Test
        fun `takeover rejected in created`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Takeover)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Created)
                assertThat(events.filterIsInstance<SessionTakeover>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `takeover rejected in idle`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                advanceTimeBy(1_000L)
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.Takeover)
                yield()

                assertThat(session.state.value).isEqualTo(SessionState.Idle)
                assertThat(events.filterIsInstance<SessionTakeover>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `takeover rejected after shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Shutdown)
                advanceUntilIdle()

                session.submit(Op.Takeover)
                advanceUntilIdle()

                assertThat(events.filterIsInstance<SessionTakeover>()).isEmpty()
                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)

                job.cancel()
        }

        @Test
        fun `interrupt rejected in created`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val job = launch { session.events.collect { } }

                session.submit(Op.Interrupt)
                advanceUntilIdle()
                assertThat(session.state.value).isEqualTo(SessionState.Created)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `interrupt rejected in idle`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val job = launch { session.events.collect { } }

                session.submit(Op.UserInput("goal"))
                advanceTimeBy(1_000L)
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.Interrupt)
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `interrupt rejected after shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val job = launch { session.events.collect { } }

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                session.submit(Op.Interrupt)
                advanceUntilIdle()

                assertThat(session.state.value).isEqualTo(SessionState.Shutdown)
                job.cancel()
        }

        @Test
        fun `interrupt from running yields idle with user stopped task completed`() = runTest {
                val gatedLlm = GatedStreamingLLMClient()
                val session = buildSession(
                        scope = this,
                        captureDelayMs = 0L,
                        llmDelayMs = 0L,
                        llmClient = gatedLlm
                )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                gatedLlm.streamStarted.await()
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                session.submit(Op.Interrupt)
                // Stay below the 300_000ms idle timeout while letting cancellation
                // propagate through the runner -> completions channel -> handleAgentComplete.
                advanceTimeBy(1_000L)
                runCurrent()

                assertThat(session.state.value).isEqualTo(SessionState.Idle)
                val completed = events.filterIsInstance<TaskCompleted>().single()
                assertThat(completed.outcome).isEqualTo(TaskOutcome.USER_STOPPED)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `interrupt from takeover pending yields idle with user stopped task completed`() = runTest {
                val gatedLlm = GatedStreamingLLMClient()
                val session = buildSession(
                        scope = this,
                        captureDelayMs = 0L,
                        llmDelayMs = 0L,
                        maxTurns = 5,
                        llmClient = gatedLlm
                )
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                gatedLlm.streamStarted.await()

                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                session.submit(Op.Interrupt)
                advanceTimeBy(1_000L)
                runCurrent()
                takeoverJob.cancel()

                assertThat(session.state.value).isEqualTo(SessionState.Idle)
                val completed = events.filterIsInstance<TaskCompleted>().single()
                assertThat(completed.outcome).isEqualTo(TaskOutcome.USER_STOPPED)

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `supplement accepted in running and emits event`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                assertThat(session.state.value).isEqualTo(SessionState.Running)

                session.submit(Op.Supplement("hint"))
                yield()

                val supp = events.filterIsInstance<SupplementReceived>().single()
                assertThat(supp.text).isEqualTo("hint")

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `supplement accepted in takeover pending and emits event`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 10_000L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                val takeoverJob = launch { session.submit(Op.Takeover) }
                yield()
                assertThat(session.state.value).isEqualTo(SessionState.TakeoverPending)

                session.submit(Op.Supplement("hint"))
                yield()

                val supp = events.filterIsInstance<SupplementReceived>().single()
                assertThat(supp.text).isEqualTo("hint")

                takeoverJob.cancel()
                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `supplement rejected in created`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Supplement("hint"))
                advanceUntilIdle()

                assertThat(events.filterIsInstance<SupplementReceived>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `supplement rejected in idle`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.UserInput("goal"))
                advanceTimeBy(1_000L)
                assertThat(session.state.value).isEqualTo(SessionState.Idle)

                session.submit(Op.Supplement("hint"))
                yield()

                assertThat(events.filterIsInstance<SupplementReceived>()).isEmpty()

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                job.cancel()
        }

        @Test
        fun `supplement rejected after shutdown`() = runTest {
                val session = buildSession(scope = this, captureDelayMs = 0L, llmDelayMs = 0L)
                val events = mutableListOf<AgentEvent>()
                val job = launch { session.events.collect { events.add(it) } }

                session.submit(Op.Shutdown)
                advanceUntilIdle()
                session.submit(Op.Supplement("hint"))
                advanceUntilIdle()

                assertThat(events.filterIsInstance<SupplementReceived>()).isEmpty()
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
                        """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
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

/**
 * LLM whose streaming flow blocks on a per-call [kotlinx.coroutines.CompletableDeferred]
 * gate, allowing tests to deterministically hold the agent inside a turn until
 * release. [streamStarted] resolves the first time a stream is collected so the
 * test can synchronize.
 */
private class GatedStreamingLLMClient : LLMClient() {
        val streamStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val gates = mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>()
        private var callIndex = 0

        @Synchronized
        private fun nextGate(): kotlinx.coroutines.CompletableDeferred<Unit> {
                val idx = callIndex++
                while (gates.size <= idx) gates.add(kotlinx.coroutines.CompletableDeferred())
                return gates[idx]
        }

        @Synchronized
        fun release(callIndex: Int = 0) {
                while (gates.size <= callIndex) gates.add(kotlinx.coroutines.CompletableDeferred())
                gates[callIndex].complete(Unit)
        }

        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): ResponsesResult = ResponsesResult(textContent = "done", toolCalls = emptyList(), responseId = "resp")

        override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): Flow<LLMStreamEvent> = flow {
                val gate = nextGate()
                if (!streamStarted.isCompleted) streamStarted.complete(Unit)
                gate.await()
                // Intentionally emit no TextDelta so the turn does NOT mark itself
                // complete (TurnToolPolicy treats text-only output as goal-achieved).
                // This keeps the agent in a Continue loop so we can deterministically
                // observe the pause confirmation path.
                emit(LLMStreamEvent.Completed)
        }
}

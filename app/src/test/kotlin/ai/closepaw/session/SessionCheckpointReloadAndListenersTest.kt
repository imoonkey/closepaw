@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.closepaw.session

import android.accessibilityservice.AccessibilityService
import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.MessageKind
import ai.closepaw.history.ResponseItem
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.ConversationConfigSnapshot
import ai.closepaw.history.model.HistoryItemConverter
import ai.closepaw.history.model.PersistedHistoryItem
import ai.closepaw.history.model.SessionRuntimeSnapshot
import ai.closepaw.history.model.TodoSnapshot
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.PlatformFactory
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionState
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.Todo
import ai.closepaw.protocol.TodoStatus
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import ai.closepaw.trace.TraceRecorderFactory
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * Real-reload + listener-wiring coverage for §6 of doc/main/ui/session/state_machine.md.
 *
 * Verifies the FSM contract enforced by [AgentSession.reload] (guard rejection,
 * Created return state, scratchpad legacy fallback, lastTaskOutcome restoration)
 * and the mutation-listener wiring/unwiring done in [AgentSession.init] /
 * [AgentSession] shutdown path. The coordinator-only data-shape round trip lives in
 * [SessionCheckpointCoordinatorTest].
 */
class SessionCheckpointReloadAndListenersTest {

    @After
    fun tearDown() {
        unmockkObject(PlatformFactory, TraceRecorderFactory, AppClassifier.Companion, SessionServices.Companion)
    }

    // region §6.3 reload guards — return null without touching infra factories

    @Test
    fun `reload rejects schema version mismatch`() {
        val service = mockk<AccessibilityService>(relaxed = true)
        val snapshot = newSnapshot(
            schemaVersion = 1,
            checkpointState = CheckpointState.IDLE_READY
        )

        val reloaded = AgentSession.reload(
            snapshot = snapshot,
            service = service,
            scope = mockk(relaxed = true),
            authStore = null
        )

        assertThat(reloaded).isNull()
    }

    @Test
    fun `reload rejects RUNNING_DIRTY snapshots per isReloadable guard`() {
        val service = mockk<AccessibilityService>(relaxed = true)
        val snapshot = newSnapshot(checkpointState = CheckpointState.RUNNING_DIRTY)

        val reloaded = AgentSession.reload(
            snapshot = snapshot,
            service = service,
            scope = mockk(relaxed = true),
            authStore = null
        )

        assertThat(reloaded).isNull()
    }

    // endregion

    // region §6.3 reload success path — Created state, restored containers,
    // legacy scratchpad fallback, lastTaskOutcome propagation.

    @Test
    fun `reload from IDLE_READY restores history todos scratchpad and returns Created`() = runTest {
        val recordingService = mockk<SessionRecordingService>(relaxed = true)
        val historyManager = HistoryManager()
        val sessionState = AgentSessionState()
        val services = buildServices(this, historyManager, sessionState, recordingService)
        installFactoryStubs(services)

        val snapshot = newSnapshot(
            checkpointState = CheckpointState.IDLE_READY,
            historyItems = listOf(
                PersistedHistoryItem.Message(
                    kind = MessageKind.USER_INTENT.name,
                    content = "do the thing"
                ),
                PersistedHistoryItem.FunctionCall(
                    id = "call-1",
                    name = "click",
                    argumentsRawJson = """{"target":"OK","x":42}"""
                )
            ),
            todos = listOf(
                TodoSnapshot("draft", TodoStatus.COMPLETED.name),
                TodoSnapshot("ship", TodoStatus.IN_PROGRESS.name)
            ),
            scratchpadJson = """{"note":"remember","count":7}""",
            lastTaskOutcome = TaskOutcome.GOAL_ACHIEVED.name
        )

        val reloaded = AgentSession.reload(
            snapshot = snapshot,
            service = mockk(relaxed = true),
            scope = this,
            authStore = null
        )

        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.state.value).isEqualTo(SessionState.Created)

        assertThat(historyManager.size()).isEqualTo(2)
        val restoredCall = historyManager.getAll()[1] as ResponseItem.FunctionCall
        assertThat(restoredCall.id).isEqualTo("call-1")
        assertThat(restoredCall.arguments.getString("target")).isEqualTo("OK")
        assertThat(restoredCall.arguments.getInt("x")).isEqualTo(42)

        assertThat(sessionState.todos.get())
            .containsExactly(
                Todo("draft", TodoStatus.COMPLETED),
                Todo("ship", TodoStatus.IN_PROGRESS)
            ).inOrder()

        assertThat(sessionState.scratchpad.read("note")).isEqualTo("remember")
        assertThat(sessionState.scratchpad.read("count")).isEqualTo(7)

        verify { recordingService.setLastTaskOutcome(TaskOutcome.GOAL_ACHIEVED) }

        reloaded.submit(Op.Shutdown)
        advanceUntilIdle()
    }

    @Test
    fun `reload migrates legacy Map scratchpad when scratchpadJson is empty`() = runTest {
        val recordingService = mockk<SessionRecordingService>(relaxed = true)
        val historyManager = HistoryManager()
        val sessionState = AgentSessionState()
        val services = buildServices(this, historyManager, sessionState, recordingService)
        installFactoryStubs(services)

        val snapshot = newSnapshot(
            checkpointState = CheckpointState.CLOSED,
            scratchpadJson = "{}",
            scratchpad = mapOf("legacy_key" to "legacy_value")
        )

        val reloaded = AgentSession.reload(
            snapshot = snapshot,
            service = mockk(relaxed = true),
            scope = this,
            authStore = null
        )

        assertThat(reloaded).isNotNull()
        assertThat(sessionState.scratchpad.read("legacy_key")).isEqualTo("legacy_value")

        reloaded!!.submit(Op.Shutdown)
        advanceUntilIdle()
    }

    @Test
    fun `reload tolerates unknown lastTaskOutcome name`() = runTest {
        val recordingService = mockk<SessionRecordingService>(relaxed = true)
        val historyManager = HistoryManager()
        val sessionState = AgentSessionState()
        val services = buildServices(this, historyManager, sessionState, recordingService)
        installFactoryStubs(services)

        val snapshot = newSnapshot(
            checkpointState = CheckpointState.IDLE_READY,
            lastTaskOutcome = "NOT_A_REAL_OUTCOME"
        )

        val reloaded = AgentSession.reload(
            snapshot = snapshot,
            service = mockk(relaxed = true),
            scope = this,
            authStore = null
        )

        assertThat(reloaded).isNotNull()
        verify(exactly = 0) { recordingService.setLastTaskOutcome(any()) }

        reloaded!!.submit(Op.Shutdown)
        advanceUntilIdle()
    }

    // endregion

    // region §6.2 mutation listener wiring + shutdown unwiring

    @Test
    fun `runtime mutations to history todos and scratchpad schedule checkpoints`() = runTest {
        val recordingService = mockk<SessionRecordingService>(relaxed = true)
        val historyManager = HistoryManager()
        val sessionState = AgentSessionState()
        val services = buildServices(this, historyManager, sessionState, recordingService)

        val session = AgentSession.createWithServices(
            config = services.config,
            service = mockk(relaxed = true),
            scope = this,
            services = services
        )

        historyManager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "hi"))
        sessionState.todos.update(listOf(Todo("x", TodoStatus.PENDING)))
        sessionState.scratchpad.write("k", "v")

        verify(exactly = 3) { recordingService.scheduleCheckpoint(any()) }

        session.submit(Op.Shutdown)
        advanceUntilIdle()
    }

    @Test
    fun `shutdown disables mutation listeners so post-shutdown writes do not schedule checkpoints`() = runTest {
        val recordingService = mockk<SessionRecordingService>(relaxed = true)
        val historyManager = HistoryManager()
        val sessionState = AgentSessionState()
        val services = buildServices(this, historyManager, sessionState, recordingService)
        val session = AgentSession.createWithServices(
            config = services.config,
            service = mockk(relaxed = true),
            scope = this,
            services = services
        )

        // Drive into Shutdown via the lifecycle, exercising the real listener-disable path.
        val collector = launch { session.events.collect { } }
        session.submit(Op.Shutdown)
        advanceUntilIdle()
        assertThat(session.state.value).isEqualTo(SessionState.Shutdown)

        // Reset to count only post-shutdown invocations of scheduleCheckpoint.
        io.mockk.clearMocks(recordingService, answers = false)

        historyManager.addItem(ResponseItem.Message(MessageKind.USER_INTENT, "after"))
        sessionState.todos.update(listOf(Todo("x", TodoStatus.PENDING)))
        sessionState.scratchpad.write("k", "v")

        verify(exactly = 0) { recordingService.scheduleCheckpoint(any()) }
        coVerify(exactly = 0) { recordingService.forceCheckpoint(any()) }

        collector.cancel()
    }

    // endregion

    // ---------- helpers ----------

    private fun newSnapshot(
        schemaVersion: Int = 2,
        checkpointState: CheckpointState,
        historyItems: List<PersistedHistoryItem> = emptyList(),
        todos: List<TodoSnapshot> = emptyList(),
        scratchpadJson: String = "{}",
        scratchpad: Map<String, String>? = null,
        lastTaskOutcome: String? = null,
        sessionId: String = "session-reload"
    ): SessionRuntimeSnapshot = SessionRuntimeSnapshot(
        schemaVersion = schemaVersion,
        sessionId = sessionId,
        config = ConversationConfigSnapshot(
            mainModel = "test-model",
            subagentModel = null,
            perceptionMode = "accessibility_only",
            platformMode = PlatformMode.ACCESSIBILITY.name
        ),
        historyItems = historyItems,
        todos = todos,
        scratchpadJson = scratchpadJson,
        scratchpad = scratchpad,
        checkpointState = checkpointState,
        lastCheckpointAt = 1_700_000_000L,
        lastTaskOutcome = lastTaskOutcome
    )

    private fun buildServices(
        scope: CoroutineScope,
        historyManager: HistoryManager,
        sessionState: AgentSessionState,
        recordingService: SessionRecordingService
    ): SessionServices {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        val platform = FakeAndroidPlatform(captureDelayMs = 0L)
        val config = SessionConfig(actionDelayMs = 0)
        val testCatalog = ModelCatalog.fromJson(
            """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}"""
        )
        val testLlm = StubLLMClient()
        return SessionServices(
            toolRegistry = toolRegistry,
            toolRouter = toolRouter,
            historyManager = historyManager,
            sessionState = sessionState,
            policyEngine = policyEngine,
            appClassifier = AppClassifier(emptyMap()),
            platform = platform,
            config = config,
            llmClient = testLlm,
            modelCatalog = testCatalog,
            llmClientFactory = LLMClientFactory.forTest(testCatalog, testLlm),
            traceRecorder = NoopTraceRecorder,
            recordingService = recordingService
        )
    }

    /**
     * Stub the four static factories `AgentSession.reload` calls. Without this the reload
     * path would crash during `PlatformFactory.create` / `SessionServices.create` because
     * those construct real Android components. Stubbing here lets us drive `reload()`
     * end-to-end on the JVM and assert restored state on the supplied SessionServices.
     */
    private fun installFactoryStubs(services: SessionServices) {
        mockkObject(TraceRecorderFactory)
        every { TraceRecorderFactory.create(any(), any(), any()) } returns NoopTraceRecorder

        mockkObject(AppClassifier.Companion)
        every { AppClassifier.fromAssets(any()) } returns AppClassifier(emptyMap())

        mockkObject(PlatformFactory)
        every {
            PlatformFactory.create(
                config = any(),
                service = any(),
                visualizer = any(),
                traceRecorder = any(),
                overlayTouchGate = any(),
                isPackageBlocked = any()
            )
        } returns mockk<AndroidPlatform>(relaxed = true)

        mockkObject(SessionServices.Companion)
        every {
            SessionServices.create(
                config = any(),
                platform = any(),
                authStore = any(),
                baseUrlOverrides = any(),
                context = any(),
                scope = any(),
                traceRecorder = any(),
                appClassifier = any()
            )
        } returns services
    }

    private class StubLLMClient : LLMClient() {
        override suspend fun chatWithTools(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String,
        maxOutputTokens: Long?,
        ): ResponsesResult = ResponsesResult(textContent = "ok", toolCalls = emptyList(), responseId = "r")

        override fun chatWithToolsStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            tools: List<FunctionTool>,
            model: String
        ): Flow<LLMStreamEvent> = flow {
            emit(LLMStreamEvent.TextDelta("ok"))
            emit(LLMStreamEvent.Completed)
        }
    }
}

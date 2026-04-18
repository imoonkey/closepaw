package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.MessageKind
import ai.closepaw.history.ResponseItem
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.HistoryItemConverter
import ai.closepaw.history.model.SessionRuntimeSnapshot
import ai.closepaw.history.model.isReloadable
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionState
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.Todo
import ai.closepaw.protocol.TodoStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

class SessionCheckpointCoordinatorTest {

    private fun buildCoordinator(
        recording: SessionRecordingService,
        sessionState: AgentSessionState = AgentSessionState(),
        historyManager: HistoryManager = HistoryManager(),
        sessionId: String = "session-1",
        config: SessionConfig = SessionConfig()
    ): SessionCheckpointCoordinator =
        SessionCheckpointCoordinator(
            sessionId = sessionId,
            config = config,
            historyManager = historyManager,
            sessionState = sessionState,
            recordingService = recording
        )

    @Test
    fun `scheduleCheckpoint delegates to recording service with state-specific snapshot`() {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        val idleProvider = slot<() -> SessionRuntimeSnapshot>()
        val runningProvider = slot<() -> SessionRuntimeSnapshot>()
        every { recording.scheduleCheckpoint(capture(idleProvider)) } returns Unit andThen Unit
        every { recording.getLastTaskOutcome() } returns null

        val coordinator = buildCoordinator(recording)

        coordinator.scheduleCheckpoint(SessionState.Idle)
        val idleSnapshot = idleProvider.captured.invoke()
        assertThat(idleSnapshot.checkpointState).isEqualTo(CheckpointState.IDLE_READY)

        every { recording.scheduleCheckpoint(capture(runningProvider)) } returns Unit
        coordinator.scheduleCheckpoint(SessionState.Running)
        val runningSnapshot = runningProvider.captured.invoke()
        assertThat(runningSnapshot.checkpointState).isEqualTo(CheckpointState.RUNNING_DIRTY)

        coVerify(exactly = 2) { recording.scheduleCheckpoint(any()) }
    }

    @Test
    fun `flushIdleReady builds snapshot from session state and forces checkpoint`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        val captured = slot<SessionRuntimeSnapshot>()
        coEvery { recording.forceCheckpoint(capture(captured)) } returns true
        every { recording.getLastTaskOutcome() } returns TaskOutcome.GOAL_ACHIEVED

        val sessionState = AgentSessionState()
        sessionState.todos.update(
            listOf(
                Todo("write tests", TodoStatus.IN_PROGRESS),
                Todo("ship it", TodoStatus.PENDING)
            )
        )
        sessionState.scratchpad.write("note", "remember")

        val coordinator = buildCoordinator(
            recording = recording,
            sessionState = sessionState,
            sessionId = "abc-123"
        )

        val beforeMs = System.currentTimeMillis()
        val success = coordinator.flushIdleReady()
        val afterMs = System.currentTimeMillis()

        assertThat(success).isTrue()
        val snapshot = captured.captured
        assertThat(snapshot.sessionId).isEqualTo("abc-123")
        assertThat(snapshot.checkpointState).isEqualTo(CheckpointState.IDLE_READY)
        assertThat(snapshot.todos.map { it.description })
            .containsExactly("write tests", "ship it").inOrder()
        assertThat(snapshot.todos.map { it.status })
            .containsExactly("IN_PROGRESS", "PENDING").inOrder()
        assertThat(snapshot.scratchpadJson).contains("note")
        assertThat(snapshot.scratchpadJson).contains("remember")
        assertThat(snapshot.historyItems).isEmpty()
        assertThat(snapshot.lastTaskOutcome).isEqualTo("GOAL_ACHIEVED")
        assertThat(snapshot.lastCheckpointAt).isAtLeast(beforeMs)
        assertThat(snapshot.lastCheckpointAt).isAtMost(afterMs)
        assertThat(snapshot.config.mainModel).isEqualTo(SessionConfig().mainModel)
    }

    @Test
    fun `flushClosed emits CLOSED state and propagates failure`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        val captured = slot<SessionRuntimeSnapshot>()
        coEvery { recording.forceCheckpoint(capture(captured)) } returns false
        every { recording.getLastTaskOutcome() } returns null

        val coordinator = buildCoordinator(recording)

        val success = coordinator.flushClosed()

        assertThat(success).isFalse()
        assertThat(captured.captured.checkpointState).isEqualTo(CheckpointState.CLOSED)
        assertThat(captured.captured.lastTaskOutcome).isNull()
    }

    // region characterization: section 6 of doc/main/ui/session/state_machine.md

    @Test
    fun `scheduleCheckpoint marks Created Paused and Shutdown as RUNNING_DIRTY`() {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        every { recording.getLastTaskOutcome() } returns null
        val provider = slot<() -> SessionRuntimeSnapshot>()
        every { recording.scheduleCheckpoint(capture(provider)) } returns Unit

        val coordinator = buildCoordinator(recording)

        listOf(SessionState.Created, SessionState.Paused, SessionState.Shutdown).forEach { state ->
            coordinator.scheduleCheckpoint(state)
            assertThat(provider.captured.invoke().checkpointState)
                .isEqualTo(CheckpointState.RUNNING_DIRTY)
        }
    }

    @Test
    fun `flushIdleReady reports failure when recording service rejects write`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        coEvery { recording.forceCheckpoint(any()) } returns false
        every { recording.getLastTaskOutcome() } returns null

        val coordinator = buildCoordinator(recording)

        assertThat(coordinator.flushIdleReady()).isFalse()
    }

    @Test
    fun `flushClosed reports success when write succeeds`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        coEvery { recording.forceCheckpoint(any()) } returns true
        every { recording.getLastTaskOutcome() } returns TaskOutcome.USER_STOPPED

        val coordinator = buildCoordinator(recording)

        assertThat(coordinator.flushClosed()).isTrue()
    }

    @Test
    fun `isReloadable accepts IDLE_READY and CLOSED but rejects RUNNING_DIRTY`() {
        assertThat(CheckpointState.IDLE_READY.isReloadable()).isTrue()
        assertThat(CheckpointState.CLOSED.isReloadable()).isTrue()
        assertThat(CheckpointState.RUNNING_DIRTY.isReloadable()).isFalse()
    }

    @Test
    fun `snapshot to restore round trip preserves todos scratchpad and history`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        val captured = slot<SessionRuntimeSnapshot>()
        coEvery { recording.forceCheckpoint(capture(captured)) } returns true
        every { recording.getLastTaskOutcome() } returns TaskOutcome.GOAL_ACHIEVED

        val originalTodos = listOf(
            Todo("draft proposal", TodoStatus.COMPLETED),
            Todo("review with team", TodoStatus.IN_PROGRESS),
            Todo("ship feature", TodoStatus.PENDING)
        )
        val originalHistory = listOf(
            ResponseItem.Message(MessageKind.USER_INTENT, "do the thing"),
            ResponseItem.FunctionCall(
                id = "call-1",
                name = "click",
                arguments = JSONObject().put("target", "OK").put("x", 42)
            ),
            ResponseItem.FunctionCallOutput(
                callId = "call-1",
                content = "ok",
                success = true,
                truncated = false
            ),
            ResponseItem.Message(MessageKind.ASSISTANT_TEXT, "done")
        )

        val sourceState = AgentSessionState()
        sourceState.todos.update(originalTodos)
        sourceState.scratchpad.write("note", "remember this")
        sourceState.scratchpad.write("count", 7)
        sourceState.scratchpad.write("flag", true)
        val sourceHistory = HistoryManager()
        sourceHistory.recordItems(originalHistory)

        val coordinator = buildCoordinator(
            recording = recording,
            sessionState = sourceState,
            historyManager = sourceHistory,
            sessionId = "round-trip"
        )
        coordinator.flushIdleReady()
        val snapshot = captured.captured

        // Restore into fresh containers (mirrors AgentSession.reload).
        val restoredHistory = HistoryManager().also {
            it.replaceAll(HistoryItemConverter.fromRecords(snapshot.historyItems))
        }
        val restoredState = AgentSessionState().also { state ->
            state.todos.update(snapshot.todos.map {
                Todo(it.description, TodoStatus.valueOf(it.status))
            })
            val parsed = JSONObject(snapshot.scratchpadJson)
            parsed.keys().forEach { key -> state.scratchpad.write(key, parsed.get(key)) }
        }

        // Todos preserved (description + status, in order).
        assertThat(restoredState.todos.get()).isEqualTo(originalTodos)

        // Scratchpad preserved (keys + values, including non-string types).
        assertThat(restoredState.scratchpad.list()).containsExactly("count", "flag", "note").inOrder()
        assertThat(restoredState.scratchpad.read("note")).isEqualTo("remember this")
        assertThat(restoredState.scratchpad.read("count")).isEqualTo(7)
        assertThat(restoredState.scratchpad.read("flag")).isEqualTo(true)

        // History preserved structurally; FunctionCall arguments survive JSON round trip.
        val restoredItems = restoredHistory.getAll()
        assertThat(restoredItems).hasSize(originalHistory.size)
        val restoredCall = restoredItems[1] as ResponseItem.FunctionCall
        val originalCall = originalHistory[1] as ResponseItem.FunctionCall
        assertThat(restoredCall.id).isEqualTo(originalCall.id)
        assertThat(restoredCall.name).isEqualTo(originalCall.name)
        assertThat(restoredCall.arguments.getString("target")).isEqualTo("OK")
        assertThat(restoredCall.arguments.getInt("x")).isEqualTo(42)
        assertThat(restoredItems[0]).isEqualTo(originalHistory[0])
        assertThat(restoredItems[2]).isEqualTo(originalHistory[2])
        assertThat(restoredItems[3]).isEqualTo(originalHistory[3])

        // Re-snapshotting the restored state matches the original snapshot's payload.
        val secondCaptured = slot<SessionRuntimeSnapshot>()
        coEvery { recording.forceCheckpoint(capture(secondCaptured)) } returns true
        val rebuilt = buildCoordinator(
            recording = recording,
            sessionState = restoredState,
            historyManager = restoredHistory,
            sessionId = snapshot.sessionId
        )
        rebuilt.flushIdleReady()
        val second = secondCaptured.captured
        assertThat(second.todos).isEqualTo(snapshot.todos)
        assertThat(second.historyItems).isEqualTo(snapshot.historyItems)
        assertThat(JSONObject(second.scratchpadJson).toString())
            .isEqualTo(JSONObject(snapshot.scratchpadJson).toString())
        assertThat(second.checkpointState).isEqualTo(CheckpointState.IDLE_READY)
    }

    @Test
    fun `snapshot empty session round trips to empty containers`() = runBlocking {
        val recording = mockk<SessionRecordingService>(relaxed = true)
        val captured = slot<SessionRuntimeSnapshot>()
        coEvery { recording.forceCheckpoint(capture(captured)) } returns true
        every { recording.getLastTaskOutcome() } returns null

        val coordinator = buildCoordinator(recording)
        coordinator.flushClosed()

        val snapshot = captured.captured
        assertThat(snapshot.historyItems).isEmpty()
        assertThat(snapshot.todos).isEmpty()
        assertThat(JSONObject(snapshot.scratchpadJson).length()).isEqualTo(0)

        val restoredHistory = HistoryManager().also {
            it.replaceAll(HistoryItemConverter.fromRecords(snapshot.historyItems))
        }
        assertThat(restoredHistory.isEmpty()).isTrue()
    }

    // endregion
}

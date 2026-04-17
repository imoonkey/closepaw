package ai.closepaw.session

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.SessionRuntimeSnapshot
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
}

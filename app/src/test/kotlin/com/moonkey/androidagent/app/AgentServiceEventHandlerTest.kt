package com.moonkey.androidagent.app

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.SessionRecordingService
import com.moonkey.androidagent.protocol.SessionError
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.TaskCompleted
import com.moonkey.androidagent.protocol.TaskOutcome
import com.moonkey.androidagent.protocol.TaskStarted
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class AgentServiceEventHandlerTest {

    private val sessionId = SessionId("s1")
    private val statusUpdates = mutableListOf<String>()
    private var sessionClearedCount = 0
    private val overlay = mockk<ServiceOverlayController>(relaxed = true)
    private val recording = mockk<SessionRecordingService>(relaxed = true)

    private val handler = AgentServiceEventHandler(
        logTag = "test",
        updateStatus = { statusUpdates += it },
        sessionCleared = { sessionClearedCount++ },
        overlayController = { overlay }
    )

    @Test
    fun `TaskStarted records user message and notifies overlay`() {
        val event = TaskStarted(
            sessionId = sessionId,
            timestamp = 1_000L,
            taskId = "t1",
            input = "Do the thing"
        )

        handler.handleEvent(event, recording)

        verify { recording.onTaskStarted() }
        verify { recording.recordUserMessage(any(), 1_000L, "Do the thing") }
        verify { recording.startAgentMessage("t1", 1_000L) }
        verify { overlay.onTaskStarted("t1", "Do the thing") }
    }

    @Test
    fun `TaskCompleted finalizes recording and updates overlay`() {
        val event = TaskCompleted(
            sessionId = sessionId,
            timestamp = 2_000L,
            taskId = "t1",
            result = "ok",
            outcome = TaskOutcome.GOAL_ACHIEVED
        )

        handler.handleEvent(event, recording)

        verify { recording.completeAgentMessage() }
        verify { recording.recordTaskOutcome(TaskOutcome.GOAL_ACHIEVED) }
        verify { overlay.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "ok") }
    }

    @Test
    fun `SessionError surfaces error in status text and overlay`() {
        val event = SessionError(
            sessionId = sessionId,
            timestamp = 3_000L,
            message = "boom"
        )

        handler.handleEvent(event, recording)

        assertThat(statusUpdates).containsExactly("❌ Error: boom")
        verify { overlay.onSessionError("boom") }
    }
}

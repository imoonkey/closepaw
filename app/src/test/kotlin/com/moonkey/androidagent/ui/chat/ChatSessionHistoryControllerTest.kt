package com.moonkey.androidagent.ui.chat

import androidx.compose.runtime.mutableStateListOf
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.ResumedSessionData
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.history.model.SessionRecord
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.chat.model.ChatUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatSessionHistoryControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun buildController(
        manager: SessionHistoryManager?,
        messages: androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage> = mutableStateListOf(),
        uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState()),
        agentMessageIds: MutableList<String?> = mutableListOf(),
    ): ChatSessionHistoryController {
        return ChatSessionHistoryController(
            scope = scope,
            sessionHistoryManager = manager,
            messages = messages,
            streamingBuffer = StringBuilder("pending"),
            stateLock = Any(),
            setCurrentAgentMessageId = { agentMessageIds.add(it) },
            uiState = uiState,
        )
    }

    private fun sessionInfo(id: String = "sess-1") = SessionInfo(
        id = id,
        fileName = "session-2026-04-16T10-00-00-$id.json",
        startTime = 0L,
        lastUpdated = 0L,
        messageCount = 1,
        displayTitle = "title",
        firstUserMessage = "hi",
    )

    @Test
    fun `resumeSession loads history, updates messages, and invokes callback`() = runTest(dispatcher) {
        val manager = mockk<SessionHistoryManager>(relaxed = true)
        val info = sessionInfo()
        val record = SessionRecord(
            sessionId = info.id,
            startTime = 0L,
            lastUpdated = 0L,
            messages = listOf(MessageRecord.User(id = "m1", timestamp = 0L, text = "hello world")),
        )
        val data = ResumedSessionData(session = record, fileName = info.fileName)
        coEvery { manager.loadSession(info.id) } returns Result.success(data)

        val messages = mutableStateListOf<ChatMessage>()
        val uiState = MutableStateFlow(ChatUiState(showEmptyState = true))
        val controller = buildController(manager, messages = messages, uiState = uiState)

        var callbackCalled = false
        controller.resumeSession(info) { callbackCalled = true }

        assertThat(messages).isNotEmpty()
        assertThat(uiState.value.showEmptyState).isFalse()
        assertThat(callbackCalled).isTrue()
        coVerify { manager.resumeSession(data) }
    }

    @Test
    fun `startNewSession clears state and marks empty`() {
        val manager = mockk<SessionHistoryManager>(relaxed = true)
        val messages = mutableStateListOf<ChatMessage>(
            ChatMessage.User(id = "u1", text = "old", timestamp = 0L)
        )
        val uiState = MutableStateFlow(ChatUiState(showEmptyState = false))
        val agentIds = mutableListOf<String?>()
        val controller = buildController(
            manager,
            messages = messages,
            uiState = uiState,
            agentMessageIds = agentIds,
        )

        controller.startNewSession()

        assertThat(messages).isEmpty()
        assertThat(uiState.value.showEmptyState).isTrue()
        assertThat(agentIds).containsExactly(null)
        verify(exactly = 0) { manager.startNewSession(any(), any()) }
    }

    @Test
    fun `deleteSession removes session and reloads list`() = runTest(dispatcher) {
        val manager = mockk<SessionHistoryManager>(relaxed = true)
        val info = sessionInfo("sess-del")
        coEvery { manager.deleteSession(info.id) } returns Result.success(Unit)
        coEvery { manager.listSessions() } returns emptyList()

        val controller = buildController(manager)
        controller.deleteSession(info)

        coVerify { manager.deleteSession(info.id) }
        coVerify { manager.listSessions() }
        assertThat(controller.sessions.value).isEmpty()
    }

    @Test
    fun `hasSessionHistory reflects manager presence`() {
        val withManager = buildController(mockk<SessionHistoryManager>(relaxed = true))
        val withoutManager = buildController(null)

        assertThat(withManager.hasSessionHistory()).isTrue()
        assertThat(withoutManager.hasSessionHistory()).isFalse()
    }
}

package ai.closepaw.ui.chat

import androidx.compose.runtime.mutableStateListOf
import ai.closepaw.protocol.CompletionHandoff
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TaskStarted
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ChatUiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** Covers `vd-handoff-event-model` acceptance: reducer threads
 *  [TaskCompleted.handoff] into the completed agent row, and absence (the
 *  non-VD default) is preserved as null. */
class ChatCompletionHandoffTest {

    private val sessionId = SessionId("s1")

    private class Fixture {
        val uiState = MutableStateFlow(ChatUiState())
        val messages = mutableStateListOf<ChatMessage>()
        val buffer = StringBuilder()
        var currentAgentId: String? = null
        val reducer = ChatEventReducer(
            uiState = uiState,
            messages = messages,
            streamingBuffer = buffer,
            stateLock = Any(),
            setCurrentAgentMessageId = { currentAgentId = it }
        )
    }

    @Test
    fun `VD completion carries handoff onto the agent row`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "open youtube on vd"))

        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
        )

        f.reducer.handle(
            TaskCompleted(
                sessionId = sessionId,
                timestamp = 200L,
                taskId = "task-1",
                result = "Opened YouTube on VD.",
                outcome = TaskOutcome.GOAL_ACHIEVED,
                handoff = handoff,
            )
        )

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.handoff).isEqualTo(handoff)
    }

    @Test
    fun `non-VD completion leaves handoff null`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "is wifi on"))

        f.reducer.handle(
            TaskCompleted(
                sessionId = sessionId,
                timestamp = 200L,
                taskId = "task-1",
                result = "Yes, Wi-Fi is on.",
                outcome = TaskOutcome.GOAL_ACHIEVED,
            )
        )

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.handoff).isNull()
    }

    @Test
    fun `appendCompletionToMessages threads handoff into newly created row`() {
        val messages = mutableListOf<ChatMessage>(
            ChatMessage.User(id = "u1", timestamp = 1L, text = "open camera")
        )
        val handoff = CompletionHandoff(
            appPackage = "com.android.camera",
            appLabel = "Camera",
        )

        appendCompletionToMessages(
            messages = messages,
            rawResult = "Done.",
            timestamp = 2L,
            taskId = "task-1",
            handoff = handoff,
        )

        val agent = messages.last() as ChatMessage.Agent
        assertThat(agent.handoff).isEqualTo(handoff)
    }

    @Test
    fun `appendCompletionToMessages without handoff keeps row handoff null`() {
        val messages = mutableListOf<ChatMessage>(
            ChatMessage.User(id = "u1", timestamp = 1L, text = "task")
        )

        appendCompletionToMessages(
            messages = messages,
            rawResult = "Done.",
            timestamp = 2L,
            taskId = "task-1",
        )

        val agent = messages.last() as ChatMessage.Agent
        assertThat(agent.handoff).isNull()
    }
}

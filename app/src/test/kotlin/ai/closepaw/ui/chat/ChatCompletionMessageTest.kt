package ai.closepaw.ui.chat

import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.common.formatToolName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatCompletionMessageTest {

    @Test
    fun `last text without tools is promoted to FinalText in place`() {
        val messages =
                mutableListOf(
                        ChatMessage.User(id = "u1", timestamp = 1L, text = "open youtube"),
                        ChatMessage.Agent(
                                id = "a1",
                                timestamp = 2L,
                                contentBlocks = listOf(ContentBlock.Text("All done.")),
                                state = AgentMessageState.Streaming
                        )
                )

        appendCompletionToMessages(
                messages = messages,
                rawResult = "All done.",
                timestamp = 3L,
                taskId = "task-1"
        )

        val last = messages.last() as ChatMessage.Agent
        assertThat(messages).hasSize(2)
        assertThat(last.state).isEqualTo(AgentMessageState.Complete)
        assertThat(last.contentBlocks).containsExactly(ContentBlock.FinalText("All done."))
    }

    @Test
    fun `complete_task action present appends FinalText with raw result`() {
        val complete = ContentBlock.Action(
                ActionCardData(
                        id = "ct1",
                        toolName = formatToolName("complete_task"),
                        description = "Complete (success): yes",
                        state = ActionState.Success,
                        resultSummary = "ok"
                )
        )
        val messages =
                mutableListOf(
                        ChatMessage.User(id = "u1", timestamp = 1L, text = "is wifi on?"),
                        ChatMessage.Agent(
                                id = "a1",
                                timestamp = 2L,
                                contentBlocks = listOf(complete),
                                state = AgentMessageState.Streaming
                        )
                )

        appendCompletionToMessages(
                messages = messages,
                rawResult = "Yes, Wi-Fi is on.",
                timestamp = 3L,
                taskId = "task-1"
        )

        val last = messages.last() as ChatMessage.Agent
        assertThat(last.contentBlocks).hasSize(2)
        assertThat(last.contentBlocks.last()).isEqualTo(ContentBlock.FinalText("Yes, Wi-Fi is on."))
    }

    @Test
    fun `completion with no answer and no text leaves no final region`() {
        val messages =
                mutableListOf<ChatMessage>(
                        ChatMessage.User(id = "u1", timestamp = 1L, text = "go"),
                        ChatMessage.Agent(
                                id = "a1",
                                timestamp = 2L,
                                contentBlocks = emptyList(),
                                state = AgentMessageState.Streaming
                        )
                )

        appendCompletionToMessages(
                messages = messages,
                rawResult = null,
                timestamp = 3L,
                taskId = "task-1"
        )

        val last = messages.last() as ChatMessage.Agent
        // USER_STOPPED / side-effect-only completions never fabricate a "Task completed"
        // FinalText — uxfb-3 contract: missing answer ⇒ no final region.
        assertThat(last.contentBlocks).isEmpty()
        assertThat(last.state).isEqualTo(AgentMessageState.Complete)
    }

    @Test
    fun `creates completion agent message when none exists`() {
        val messages = mutableListOf<ChatMessage>(ChatMessage.User(id = "u1", timestamp = 1L, text = "go"))

        appendCompletionToMessages(
                messages = messages,
                rawResult = "Done",
                timestamp = 2L,
                taskId = "task-2"
        )

        assertThat(messages).hasSize(2)
        val completion = messages.last() as ChatMessage.Agent
        assertThat(completion.id).isEqualTo("task-2")
        assertThat(completion.timestamp).isEqualTo(2L)
        assertThat(completion.state).isEqualTo(AgentMessageState.Complete)
        assertThat(completion.contentBlocks).containsExactly(ContentBlock.FinalText("Done"))
    }

    @Test
    fun `error path keeps Text block prefixed with warning`() {
        val messages = mutableListOf<ChatMessage>(
                ChatMessage.Agent(
                        id = "a1",
                        timestamp = 1L,
                        contentBlocks = emptyList(),
                        state = AgentMessageState.Streaming
                )
        )

        appendCompletionToMessages(
                messages = messages,
                rawResult = "boom",
                timestamp = 2L,
                taskId = "task-1",
                isError = true
        )

        val last = messages.last() as ChatMessage.Agent
        assertThat(last.contentBlocks).containsExactly(ContentBlock.Text("⚠️ boom"))
    }
}

package ai.closepaw.ui.chat

import com.google.common.truth.Truth.assertThat
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import org.junit.Test

class ChatCompletionMessageTest {

    @Test
    fun `appends completion text to last agent message`() {
        val messages =
                mutableListOf(
                        ChatMessage.User(id = "u1", timestamp = 1L, text = "open youtube"),
                        ChatMessage.Agent(
                                id = "a1",
                                timestamp = 2L,
                                contentBlocks = listOf(ContentBlock.Text("Working...")),
                                state = AgentMessageState.Streaming
                        )
                )

        appendCompletionToMessages(
                messages = messages,
                completionText = "Task completed",
                timestamp = 3L,
                taskId = "task-1"
        )

        val last = messages.last() as ChatMessage.Agent
        assertThat(messages).hasSize(2)
        assertThat(last.state).isEqualTo(AgentMessageState.Complete)
        assertThat(last.contentBlocks.last()).isEqualTo(ContentBlock.Text("Task completed"))
    }

    @Test
    fun `creates completion agent message when none exists`() {
        val messages = mutableListOf<ChatMessage>(ChatMessage.User(id = "u1", timestamp = 1L, text = "go"))

        appendCompletionToMessages(
                messages = messages,
                completionText = "Done",
                timestamp = 2L,
                taskId = "task-2"
        )

        assertThat(messages).hasSize(2)
        val completion = messages.last() as ChatMessage.Agent
        assertThat(completion.id).isEqualTo("task-2")
        assertThat(completion.timestamp).isEqualTo(2L)
        assertThat(completion.state).isEqualTo(AgentMessageState.Complete)
        assertThat(completion.contentBlocks).containsExactly(ContentBlock.Text("Done"))
    }
}

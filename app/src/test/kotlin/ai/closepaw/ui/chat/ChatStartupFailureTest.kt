package ai.closepaw.ui.chat

import com.google.common.truth.Truth.assertThat
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import org.junit.Test

class ChatStartupFailureTest {

    @Test
    fun `startup failure preserves user input and surfaces error`() {
        val messages = mutableListOf<ChatMessage>()

        appendStartupFailureMessages(
                messages = messages,
                inputText = "Open Settings",
                errorMessage = "Platform initialization failed",
                timestamp = 42L
        )

        assertThat(messages).hasSize(2)
        val user = messages[0] as ChatMessage.User
        assertThat(user.text).isEqualTo("Open Settings")
        assertThat(user.timestamp).isEqualTo(42L)

        val agent = messages[1] as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Complete)
        val errorBlock = agent.contentBlocks.single() as ContentBlock.Text
        assertThat(errorBlock.text).contains("Platform initialization failed")
        assertThat(errorBlock.text).contains("Failed to start")
    }
}

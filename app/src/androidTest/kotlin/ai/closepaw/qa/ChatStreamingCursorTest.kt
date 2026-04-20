package ai.closepaw.qa

import ai.closepaw.ui.chat.components.CURSOR_TEST_TAG
import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatStreamingCursorTest {

    @get:Rule val compose = createComposeRule()

    @Test fun streaming_message_shows_inline_cursor_on_last_text_block() {
        val msg = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("streaming content")),
            state = AgentMessageState.Streaming
        )
        compose.setContent { MessageBubble(msg) }

        compose.onNodeWithTag(CURSOR_TEST_TAG).assertExists()
    }

    @Test fun complete_message_has_no_cursor() {
        val msg = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("done")),
            state = AgentMessageState.Complete
        )
        compose.setContent { MessageBubble(msg) }

        compose.onAllNodesWithTag(CURSOR_TEST_TAG).assertCountEquals(0)
    }
}

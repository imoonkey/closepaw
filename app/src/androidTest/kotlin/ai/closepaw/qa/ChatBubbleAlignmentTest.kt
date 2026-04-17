package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBubbleAlignmentTest {

    @get:Rule val compose = createComposeRule()

    @Test fun user_bubble_sits_in_right_half() {
        compose.setContent {
            Box(Modifier.fillMaxWidth()) {
                MessageBubble(ChatMessage.User(id = "u1", timestamp = 0L, text = "hi from user"))
            }
        }

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val text = compose.onNodeWithText("hi from user").fetchSemanticsNode().boundsInRoot
        assert(text.left >= rootW / 2) {
            "user bubble text expected in right half; rootW=$rootW textLeft=${text.left}"
        }
    }

    @Test fun agent_bubble_sits_in_left_half() {
        val agent = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("hi from agent")),
            state = AgentMessageState.Complete
        )
        compose.setContent {
            Box(Modifier.fillMaxWidth()) { MessageBubble(agent) }
        }

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val text = compose.onNodeWithText("hi from agent").fetchSemanticsNode().boundsInRoot
        assert(text.right <= rootW / 2 + rootW * 0.1f) {
            "agent bubble text expected in left half; rootW=$rootW textRight=${text.right}"
        }
    }
}

package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBubbleAlignmentTest {

    @get:Rule val compose = createComposeRule()

    @Test fun user_bubble_sits_in_right_half() {
        compose.setContent {
            ClosePawTheme {
                Box(Modifier.fillMaxWidth()) {
                    MessageBubble(ChatMessage.User(id = "u1", timestamp = 0L, text = "hi from user"))
                }
            }
        }

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val bubble = compose.onNodeWithTag("qa-user-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "user bubble expected in right half; rootW=$rootW bubbleLeft=${bubble.left}",
            bubble.left >= rootW / 2
        )
    }

    @Test fun agent_bubble_sits_in_left_half() {
        val agent = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("hi from agent")),
            state = AgentMessageState.Complete
        )
        compose.setContent {
            ClosePawTheme {
                Box(Modifier.fillMaxWidth()) { MessageBubble(agent) }
            }
        }

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val bubble = compose.onNodeWithTag("qa-agent-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "agent bubble expected in left half; rootW=$rootW bubbleRight=${bubble.right}",
            bubble.right <= rootW / 2
        )
    }
}

package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBubbleAlignmentTest {

    @get:Rule val compose = createComposeRule()

    @Test fun user_bubble_sits_in_right_half() {
        // Cap parent width so "hi from user" + 85% widthIn definitely produces
        // a bubble whose left edge clears rootW/2 regardless of device width.
        compose.setContent {
            ClosePawTheme {
                Box(Modifier.width(360.dp)) {
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

    @Test fun agent_bubble_is_left_aligned_full_width_row() {
        // AgentRow is fillMaxWidth() by design (trace + final-answer stack),
        // not a half-width bubble. Verify left-aligned start instead.
        val agent = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("hi from agent")),
            state = AgentMessageState.Complete
        )
        compose.setContent {
            ClosePawTheme {
                Box(Modifier.width(360.dp)) { MessageBubble(agent) }
            }
        }

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val bubble = compose.onNodeWithTag("qa-agent-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "agent row expected to start at left edge; bubbleLeft=${bubble.left}",
            bubble.left == 0f
        )
        assertTrue(
            "agent row expected to span full width; rootW=$rootW bubbleRight=${bubble.right}",
            bubble.right == rootW
        )
    }
}

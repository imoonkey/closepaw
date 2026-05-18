package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatThinkingStateTest {

    @get:Rule val compose = createComposeRule()

    @Test fun thinking_indicator_shown_when_agent_thinking_and_no_content() {
        val msg = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = emptyList(),
            state = AgentMessageState.Thinking
        )
        compose.setContent { ClosePawTheme { MessageBubble(msg) } }
        // Indicator uses rememberInfiniteTransition; halt the clock after the
        // initial frame so semantic reads don't wait on a never-idle composition.
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("qa-thinking-indicator").assertExists()
    }

    @Test fun thinking_indicator_hidden_once_content_arrives() {
        val msg = ChatMessage.Agent(
            id = "a1",
            timestamp = 0L,
            contentBlocks = listOf(ContentBlock.Text("partial response")),
            state = AgentMessageState.Streaming
        )
        compose.setContent { ClosePawTheme { MessageBubble(msg) } }
        // StreamingText cursor uses rememberInfiniteTransition; same rationale.
        compose.mainClock.autoAdvance = false

        compose.onAllNodesWithTag("qa-thinking-indicator").assertCountEquals(0)
        // StreamingText's annotated text contains the inline-content placeholder
        // char after the text, so onNodeWithText must match by substring.
        compose.onNodeWithText("partial response", substring = true).assertExists()
    }
}

package ai.closepaw.qa

import ai.closepaw.ui.chat.components.ChatHeader
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHeaderTest {

    @get:Rule val compose = createComposeRule()

    @Test fun new_chat_button_hidden_when_showNewChatButton_false() {
        compose.setContent {
            ChatHeader(
                onMenuClick = {},
                onNewChatClick = {},
                showNewChatButton = false
            )
        }

        compose.onAllNodesWithContentDescription("New conversation").assertCountEquals(0)
    }
}

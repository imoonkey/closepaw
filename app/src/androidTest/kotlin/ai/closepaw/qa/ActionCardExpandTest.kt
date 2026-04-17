package ai.closepaw.qa

import ai.closepaw.ui.chat.components.ActionCard
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionCardExpandTest {

    @get:Rule val compose = createComposeRule()

    private val data = ActionCardData(
        id = "x",
        toolName = "my-tool",
        description = "desc",
        state = ActionState.Success,
        expandedContent = "EXPANDED_PAYLOAD"
    )

    @Test fun expanded_content_hidden_by_default_and_shown_after_tap() {
        compose.setContent { ActionCard(data) }

        compose.onAllNodesWithText("EXPANDED_PAYLOAD").assertCountEquals(0)

        compose.onNodeWithText("my-tool").performClick()
        compose.onNodeWithText("EXPANDED_PAYLOAD").assertExists()

        compose.onNodeWithText("my-tool").performClick()
        compose.onAllNodesWithText("EXPANDED_PAYLOAD").assertCountEquals(0)
    }
}

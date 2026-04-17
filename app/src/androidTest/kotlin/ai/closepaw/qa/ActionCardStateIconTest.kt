package ai.closepaw.qa

import ai.closepaw.ui.chat.components.ActionCard
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionCardStateIconTest {

    @get:Rule val compose = createComposeRule()

    private fun card(state: ActionState) = ActionCardData(
        id = "x",
        toolName = "tool",
        description = "desc",
        state = state
    )

    @Test fun success_state_shows_success_icon() {
        compose.setContent { ActionCard(card(ActionState.Success)) }
        compose.onNodeWithContentDescription("Success").assertExists()
        compose.onAllNodesWithContentDescription("Failed").assertCountEquals(0)
    }

    @Test fun failed_state_shows_failed_icon() {
        compose.setContent { ActionCard(card(ActionState.Failed)) }
        compose.onNodeWithContentDescription("Failed").assertExists()
        compose.onAllNodesWithContentDescription("Success").assertCountEquals(0)
    }

    @Test fun skipped_state_shows_skipped_icon() {
        compose.setContent { ActionCard(card(ActionState.Skipped)) }
        compose.onNodeWithContentDescription("Skipped").assertExists()
    }

    @Test fun proposed_state_has_no_status_icon() {
        compose.setContent { ActionCard(card(ActionState.Proposed)) }
        compose.onAllNodesWithContentDescription("Success").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Failed").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Skipped").assertCountEquals(0)
    }

    @Test fun executing_state_has_no_terminal_icon() {
        compose.setContent { ActionCard(card(ActionState.Executing)) }
        compose.onAllNodesWithContentDescription("Success").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Failed").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Skipped").assertCountEquals(0)
    }
}

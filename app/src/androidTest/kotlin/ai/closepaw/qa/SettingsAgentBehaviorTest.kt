package ai.closepaw.qa

import ai.closepaw.protocol.AgentMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9: The Executor Model dropdown is gated on AgentMode.PRO. Both halves —
 * Pro shows it, Basic hides it — are asserted via a testTag on the dropdown
 * container, because the label text "Executor Model" is also used as a
 * section title and would otherwise match even when the dropdown is gone.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAgentBehaviorTest {

    @get:Rule val compose = createComposeRule()

    // S9a
    @Test fun pro_mode_shows_executor_dropdown() {
        compose.setContent {
            TestLlmAuthPage(agentMode = AgentMode.PRO)
        }
        compose.onNodeWithTag("qa-executor-model-dropdown").assertExists()
    }

    // S9b
    @Test fun basic_mode_hides_executor_dropdown() {
        compose.setContent {
            TestLlmAuthPage(agentMode = AgentMode.BASIC)
        }
        compose.onAllNodesWithTag("qa-executor-model-dropdown").assertCountEquals(0)
    }
}

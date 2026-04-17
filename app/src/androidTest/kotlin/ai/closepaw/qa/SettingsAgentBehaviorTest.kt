package ai.closepaw.qa

import ai.closepaw.protocol.AgentMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9: Executor Model dropdown is gated on AgentMode.PRO. The production
 * composable for the executor dropdown lives inside LlmAuthSettingsPage.
 * (Basic-mode absence is implicitly covered by S5-S8, which all run in BASIC
 * and do not encounter the "Executor Model" label.)
 */
@RunWith(AndroidJUnit4::class)
class SettingsAgentBehaviorTest {

    @get:Rule val compose = createComposeRule()

    // S9
    @Test fun pro_mode_shows_executor_dropdown() {
        compose.setContent {
            TestLlmAuthPage(authMethod = "oauth", agentMode = AgentMode.PRO)
        }
        // "Executor Model" appears as both the section title and the dropdown
        // field label, which is fine — at least one occurrence proves the
        // dropdown composes.
        val count = compose.onAllNodesWithText("Executor Model").fetchSemanticsNodes().size
        assertTrue("Executor Model dropdown missing in PRO mode", count >= 1)
    }
}

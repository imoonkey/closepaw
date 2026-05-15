package ai.closepaw.qa

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Subagent model dropdown is always rendered on the LLM Auth page (delegation is always
 * available; there is no longer a mode gate around it).
 */
@RunWith(AndroidJUnit4::class)
class SettingsAgentBehaviorTest {

    @get:Rule val compose = createComposeRule()

    @Test fun subagent_dropdown_is_shown() {
        compose.setContent { TestLlmAuthPage() }
        compose.onNodeWithTag("qa-subagent-model-dropdown").assertExists()
    }
}

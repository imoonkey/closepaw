package ai.closepaw.qa

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1-S4: Settings sheet navigation — home entry, dismiss, back from sub-page,
 * and page state survival across saved-instance-state restoration.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavTest {

    @get:Rule val compose = createComposeRule()

    // S1: Sheet opens to home page — all three nav rows visible, no sub-page markers.
    @Test fun sheet_opens_to_home_page() {
        compose.setContent { TestSettingsSheet() }

        compose.onNodeWithText("LLM & Authentication").assertExists()
        compose.onNodeWithText("Agent Behavior").assertExists()
        compose.onNodeWithText("Permissions & Advanced").assertExists()

        // Sub-page unique markers must be absent on home.
        compose.onAllNodesWithText("Sign In").assertCountEquals(0)
        compose.onAllNodesWithText("Termux Shell").assertCountEquals(0)
        compose.onAllNodesWithText("Session Traces").assertCountEquals(0)
    }

    // S2: Clicking the close icon fires onDismiss.
    @Test fun sheet_dismiss_fires_callback() {
        var dismissed = 0
        compose.setContent { TestSettingsSheet(onDismiss = { dismissed++ }) }

        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
    }

    // S3: Navigate to LLM Auth sub-page, press Back → home restored.
    @Test fun back_from_subpage_returns_to_home() {
        compose.setContent { TestSettingsSheet() }

        // Enter sub-page via nav row.
        compose.onNodeWithText("LLM & Authentication").performClick()
        compose.onNodeWithText("Sign In").assertExists()  // tabs visible = sub-page

        // Press Back icon — "Back" contentDescription exists only on sub-page headers.
        compose.onNodeWithContentDescription("Back").performClick()

        // Home markers back, sub-page tabs gone.
        compose.onAllNodesWithText("Sign In").assertCountEquals(0)
        compose.onNodeWithText("Agent Behavior").assertExists()
        compose.onNodeWithText("Permissions & Advanced").assertExists()
    }

    // S4: Page state is rememberSaveable — survives config change (simulated via StateRestorationTester).
    @Test fun page_state_survives_state_restoration() {
        val restore = StateRestorationTester(compose)
        restore.setContent { TestSettingsSheet() }

        compose.onNodeWithText("Agent Behavior").performClick()
        // "Termux Shell" is a Tools-section row title that only renders on the Agent Behavior sub-page.
        assertTrue(
            "Agent Behavior sub-page did not render 'Termux Shell' row",
            compose.onAllNodesWithText("Termux Shell").fetchSemanticsNodes().isNotEmpty()
        )

        restore.emulateSavedInstanceStateRestore()

        // Still on Agent Behavior sub-page.
        assertTrue(
            "Sub-page state lost after restoration",
            compose.onAllNodesWithText("Termux Shell").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "Back icon should persist across restoration",
            compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        )
    }
}

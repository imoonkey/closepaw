package ai.closepaw.qa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dialog body copy is unique in this page and stable across redraws — use it as
 * the dialog-visible marker.
 */
private const val CLEAR_TRACES_DIALOG_BODY =
    "All recorded execution traces will be permanently deleted. This cannot be undone."

@RunWith(AndroidJUnit4::class)
class SettingsPermissionsTest {

    @get:Rule val compose = createComposeRule()

    // S10: trace toggle ON shows the sensitive-data warning banner; OFF hides it.
    @Test fun session_traces_toggle_shows_warning_banner() {
        compose.setContent {
            var enabled by remember { mutableStateOf(false) }
            TestPermissionsPage(
                traceEnabled = enabled,
                onTraceEnabledChange = { enabled = it },
            )
        }

        val bannerFragment = "Traces may contain sensitive data"

        // Initial: OFF → no banner.
        compose.onAllNodesWithText(bannerFragment, substring = true).assertCountEquals(0)

        // Toggle ON — tagged Switch is the only stable selector (two switches on page).
        compose.onNodeWithTag("qa-session-traces-switch").performClick()
        compose.onNodeWithText(bannerFragment, substring = true).assertExists()

        // Toggle OFF → banner gone.
        compose.onNodeWithTag("qa-session-traces-switch").performClick()
        compose.onAllNodesWithText(bannerFragment, substring = true).assertCountEquals(0)
    }

    // S11: clicking Clear Traces opens the confirmation dialog.
    @Test fun clear_traces_shows_confirm_dialog() {
        compose.setContent { TestPermissionsPage(traceEnabled = false) }

        // Dialog body not yet visible.
        compose.onAllNodesWithText(CLEAR_TRACES_DIALOG_BODY).assertCountEquals(0)

        compose.onNodeWithText("Clear Traces").performClick()

        compose.onNodeWithText(CLEAR_TRACES_DIALOG_BODY).assertExists()
    }

    // S12: confirming the dialog disables the Clear Traces button and relabels it.
    @Test fun clear_traces_confirm_disables_button() {
        compose.setContent { TestPermissionsPage(traceEnabled = false) }

        compose.onNodeWithText("Clear Traces").performClick()
        // Dialog's confirm button is the one with exact text "Clear".
        compose.onNodeWithText("Clear").performClick()

        // Dialog dismissed, main button relabeled and disabled.
        compose.waitUntil(timeoutMillis = 3000) {
            compose.onAllNodesWithText("Traces Cleared").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Traces Cleared").assertIsNotEnabled()
        compose.onAllNodesWithText(CLEAR_TRACES_DIALOG_BODY).assertCountEquals(0)
    }
}

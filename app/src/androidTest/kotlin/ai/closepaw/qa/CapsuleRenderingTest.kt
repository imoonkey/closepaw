package ai.closepaw.qa

import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleRenderingTest {

    @get:Rule val compose = createComposeRule()

    // K1 — Hidden hides Row1/Row2 controls. Row3 prompt input is still rendered
    // by design (new-task prompt); we only verify no active-task chrome.
    @Test fun hidden_renders_no_row1_or_row2_controls() {
        compose.setContent { TestCapsule(mode = CapsuleMode.Hidden) }

        compose.onAllNodesWithText("Takeover").assertCountEquals(0)
        compose.onAllNodesWithText("Stop").assertCountEquals(0)
        compose.onAllNodesWithText("Resume").assertCountEquals(0)
        compose.onAllNodesWithText("Close").assertCountEquals(0)
    }

    // K2
    @Test fun running_shows_thought_and_takeover_button() {
        compose.setContent {
            TestCapsule(mode = CapsuleMode.Running(thought = "Opening Settings"))
        }

        compose.onNodeWithText("Opening Settings").assertExists()
        compose.onNodeWithText("Takeover").assertExists()
    }

    // K3
    @Test fun running_shows_stop_button() {
        compose.setContent {
            TestCapsule(mode = CapsuleMode.Running(thought = "Working"))
        }

        compose.onNodeWithText("Stop").assertExists()
    }

    // K4
    @Test fun takeover_shows_resume_instead_of_takeover() {
        compose.setContent {
            TestCapsule(mode = CapsuleMode.Takeover(lastThought = "Paused here"))
        }

        compose.onNodeWithText("Resume").assertExists()
        compose.onAllNodesWithText("Takeover").assertCountEquals(0)
    }
}

package ai.closepaw.qa

import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleRenderingTest {

    @get:Rule val compose = createComposeRule()

    // K1 — Hidden draws no capsule state-chrome (Row1 thought, Row2 buttons,
    // Row2-right nav icons). Row3 new-task prompt IS still drawn by design
    // (it's the "What can I help you with?" entry point), and we verify that
    // too so this test captures the actual contract.
    @Test fun hidden_renders_no_capsule_state_chrome() {
        compose.setContent { TestCapsule(mode = CapsuleMode.Hidden) }

        // Row3 prompt stays — intentional new-task entry point.
        compose.onNodeWithText("What can I help you with?").assertExists()

        // No Row2 primary/secondary/stop button labels from any state.
        listOf(
            "Takeover", "Handing over", "Resume",
            "Stop", "Stopping...", "Close", "Reject",
            "Done", "Session", "Always",
        ).forEach { label ->
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }

        // No Row2-right nav icons.
        listOf("Minimize", "Open app", "Open viewer").forEach { cd ->
            compose.onAllNodesWithContentDescription(cd).assertCountEquals(0)
        }

        // No Row1 thought strings from any active state.
        listOf(
            "Thinking...", "Handing over...", "Paused",
            "💬 Awaiting response", "✋ Action needed",
            "Allow ClosePaw to operate Chrome?",
        ).forEach { txt ->
            compose.onAllNodesWithText(txt).assertCountEquals(0)
        }
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

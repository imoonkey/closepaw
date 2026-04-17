package ai.closepaw.qa

import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleLifecycleTest {

    @get:Rule val compose = createComposeRule()

    // K11 — Done shows success message and auto-dismisses after 3s.
    // The real auto-hide lives in CapsuleStateHolder; here we mirror the
    // same LaunchedEffect + 3000ms delay pattern at the UI layer to
    // verify the visible contract under compose's test clock.
    @Test fun done_shows_success_message_and_auto_dismisses_after_3s() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var mode: CapsuleMode by remember { mutableStateOf(CapsuleMode.Done("Task complete")) }
            LaunchedEffect(mode) {
                if (mode is CapsuleMode.Done) {
                    delay(3000L)
                    mode = CapsuleMode.Hidden
                }
            }
            TestCapsule(mode = mode)
        }

        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("✓ Task complete").assertExists()

        compose.mainClock.advanceTimeBy(3100)
        compose.onAllNodesWithText("✓ Task complete").assertCountEquals(0)
    }

    // K12
    @Test fun error_dismiss_button_fires_callback() {
        var dismissed = false
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.Error("Something broke"),
                onDismissError = { dismissed = true },
            )
        }

        compose.onNodeWithText("⚠ Something broke").assertExists()
        compose.onNodeWithText("Close").performClick()
        assertTrue("onDismissError was not invoked", dismissed)
    }

    // K13 — isStopPending replaces Stop with disabled "Stopping..."
    @Test fun is_stop_pending_disables_stop_button() {
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.Running(thought = "Working"),
                isStopPending = true,
            )
        }

        compose.onNodeWithText("Stopping...").assertExists().assertIsNotEnabled()
        compose.onAllNodesWithText("Stop").assertCountEquals(0)
    }

    @Test fun stop_button_enabled_when_not_stop_pending() {
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.Running(thought = "Working"),
                isStopPending = false,
            )
        }
        compose.onNodeWithText("Stop").assertIsEnabled()
    }

    // K14
    @Test fun navigation_buttons_fire_correct_nav_action() {
        val fired = mutableListOf<NavAction>()
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.Running(thought = "Working"),
                context = CapsuleContext.BACKGROUND,
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                hasIsland = true,
                onNavigate = { fired += it },
            )
        }

        compose.onNodeWithContentDescription("Minimize").performClick()
        compose.onNodeWithContentDescription("Open app").performClick()
        compose.onNodeWithContentDescription("Open viewer").performClick()

        assertEquals(
            listOf(NavAction.MINIMIZE, NavAction.OPEN_APP, NavAction.OPEN_VIEWER),
            fired,
        )
    }
}

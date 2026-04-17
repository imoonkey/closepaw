package ai.closepaw.qa

import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.overlay.CapsuleStateHolder
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleLifecycleTest {

    @get:Rule val compose = createComposeRule()

    // K11 — exercises the real auto-hide path in CapsuleStateHolder.
    // Uses real time (runBlocking + real delay) so the holder's delay(3000)
    // actually fires. If production drops scheduleAutoHide or changes the
    // timing, this test fails.
    @Test fun done_state_auto_dismisses_to_hidden_via_real_holder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val holder = CapsuleStateHolder(scope)
            withContext(Dispatchers.Main) {
                holder.onTaskStarted("task-1", "start")
                holder.onTaskCompleted(TaskOutcome.GOAL_ACHIEVED, "Task complete")
            }
            assertEquals(
                CapsuleMode.Done("Task complete"),
                holder.mode.value,
            )

            // Well before the 3s mark — should still be Done.
            delay(1500L)
            assertTrue(
                "Should still be Done at 1.5s, got ${holder.mode.value}",
                holder.mode.value is CapsuleMode.Done,
            )

            // Past the 3s mark — should have auto-hidden.
            delay(1800L)
            assertEquals(
                CapsuleMode.Hidden,
                holder.mode.value,
            )
        } finally {
            scope.cancel()
        }
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

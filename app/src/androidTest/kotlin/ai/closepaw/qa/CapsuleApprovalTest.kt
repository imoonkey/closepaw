package ai.closepaw.qa

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleApprovalTest {

    @get:Rule val compose = createComposeRule()

    // K9
    @Test fun waiting_for_action_shows_instruction_and_done_button() {
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.WaitingForAction(
                    instruction = "Unlock the phone",
                    callId = "w-1",
                )
            )
        }

        compose.onNodeWithText("Unlock the phone").assertExists()
        compose.onNodeWithText("Done").assertExists()
    }

    // K10
    @Test fun waiting_for_approval_shows_app_level_scope_buttons() {
        var captured: Quad? = null
        compose.setContent {
            TestCapsule(
                mode = CapsuleMode.WaitingForApproval(
                    callId = "a-1",
                    description = "Click OK",
                    appLabel = "Chrome",
                    packageName = "com.android.chrome",
                    reason = "needs approval",
                ),
                onApprovalResponse = { id, decision, scope, pkg ->
                    captured = Quad(id, decision, scope, pkg)
                },
            )
        }

        compose.onNodeWithText("Allow ClosePaw to operate Chrome?").assertExists()
        compose.onNodeWithText("Always").assertExists()
        compose.onNodeWithText("Session").assertExists()
        compose.onNodeWithText("Reject").assertExists()

        compose.onNodeWithText("Always").performClick()
        val result = captured
        assertNotNull(result)
        assertEquals("a-1", result!!.callId)
        assertEquals(ApprovalDecision.APPROVED, result.decision)
        assertEquals(ApprovalScope.ALWAYS, result.scope)
        assertEquals("com.android.chrome", result.pkg)
    }

    private data class Quad(
        val callId: String,
        val decision: ApprovalDecision,
        val scope: ApprovalScope,
        val pkg: String,
    )
}

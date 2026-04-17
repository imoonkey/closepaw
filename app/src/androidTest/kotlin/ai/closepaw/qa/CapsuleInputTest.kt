package ai.closepaw.qa

import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleInputTest {

    @get:Rule val compose = createComposeRule()

    private val waiting = CapsuleMode.WaitingForInput(
        question = "What size?",
        callId = "c-1",
    )

    // K5
    @Test fun waiting_for_input_shows_textfield_hint_and_disabled_send() {
        compose.setContent { TestCapsule(mode = waiting) }

        compose.onNodeWithTag("qa-capsule-input").assertExists()
        compose.onNodeWithText("Type your response...").assertExists()
        compose.onNodeWithText("What size?").assertExists()
        compose.onNodeWithContentDescription("Send →").assertIsNotEnabled()
    }

    // K6
    @Test fun send_enabled_only_when_non_blank() {
        compose.setContent { TestCapsule(mode = waiting) }

        compose.onNodeWithContentDescription("Send →").assertIsNotEnabled()

        compose.onNodeWithTag("qa-capsule-input").performTextInput("   ")
        compose.onNodeWithContentDescription("Send →").assertIsNotEnabled()

        compose.onNodeWithTag("qa-capsule-input").performTextReplacement("hello")
        compose.onNodeWithContentDescription("Send →").assertIsEnabled()
    }

    // K7
    @Test fun typing_updates_input_field() {
        compose.setContent { TestCapsule(mode = waiting) }

        compose.onNodeWithTag("qa-capsule-input").performTextInput("abc")
        compose.onNodeWithTag("qa-capsule-input").assertTextContains("abc")
    }

    // K8
    @Test fun send_fires_callback_with_text_and_clears_field() {
        var received: Pair<String, String>? = null
        compose.setContent {
            TestCapsule(
                mode = waiting,
                onUserResponse = { callId, text -> received = callId to text },
            )
        }

        compose.onNodeWithTag("qa-capsule-input").performTextInput("hello")
        compose.onNodeWithContentDescription("Send →").performClick()

        assertEquals("c-1" to "hello", received)
        compose.onNodeWithTag("qa-capsule-input").assertTextContains("")
        compose.onNodeWithContentDescription("Send →").assertIsNotEnabled()
    }
}

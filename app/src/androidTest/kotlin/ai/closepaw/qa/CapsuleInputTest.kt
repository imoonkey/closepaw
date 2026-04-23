package ai.closepaw.qa

import ai.closepaw.ui.overlay.model.CapsuleMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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

private fun SemanticsNodeInteraction.editableTextValue(): String =
    fetchSemanticsNode().config[SemanticsProperties.EditableText].text

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
        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsNotEnabled()
    }

    // K6
    @Test fun send_enabled_only_when_non_blank() {
        compose.setContent { TestCapsule(mode = waiting) }

        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsNotEnabled()

        compose.onNodeWithTag("qa-capsule-input").performTextInput("   ")
        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsNotEnabled()

        compose.onNodeWithTag("qa-capsule-input").performTextReplacement("hello")
        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsEnabled()
    }

    // K7
    @Test fun typing_updates_input_field() {
        compose.setContent { TestCapsule(mode = waiting) }

        compose.onNodeWithTag("qa-capsule-input").performTextInput("abc")
        assertEquals("abc", compose.onNodeWithTag("qa-capsule-input").editableTextValue())
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
        assertEquals("hello", compose.onNodeWithTag("qa-capsule-input").editableTextValue())

        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).performClick()

        assertEquals("c-1" to "hello", received)
        assertEquals("", compose.onNodeWithTag("qa-capsule-input").editableTextValue())
        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsNotEnabled()
    }
}

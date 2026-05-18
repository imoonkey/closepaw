package ai.closepaw.qa

import ai.closepaw.ui.chat.components.EmptyState
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatEmptyStateTest {

    @get:Rule val compose = createComposeRule()

    // Production renders each suggestion as verb + gloss in two adjacent Text
    // nodes (MarginaliaSuggestion). Matching the verb is sufficient + unique.
    private val verbs = listOf("Check", "Turn on", "Search")
    private val fullSuggestions = listOf(
        "Check my unread emails",
        "Turn on Do Not Disturb",
        "Search for nearby restaurants",
    )

    @Test fun shows_three_suggestion_chips() {
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = {}) } }

        verbs.forEach { verb ->
            compose.onNodeWithText(verb).assertExists()
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    }

    @Test fun tapping_suggestion_fires_callback_with_text() {
        var clicked: String? = null
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = { clicked = it }) } }

        // Click action lives on the Row, not the Text; tap by clickable index.
        compose.onAllNodes(hasClickAction())[1].performClick()

        assertEquals(fullSuggestions[1], clicked)
    }
}

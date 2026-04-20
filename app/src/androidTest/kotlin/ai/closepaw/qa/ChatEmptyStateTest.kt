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

    private val suggestions = listOf(
        "Check my unread emails",
        "Turn on Do Not Disturb",
        "Search for nearby restaurants"
    )

    @Test fun shows_three_suggestion_chips() {
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = {}) } }

        suggestions.forEach { text ->
            compose.onNodeWithText("\"$text\"").assertExists()
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    }

    @Test fun tapping_suggestion_fires_callback_with_text() {
        var clicked: String? = null
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = { clicked = it }) } }

        compose.onNodeWithText("\"${suggestions[1]}\"").performClick()

        assertEquals(suggestions[1], clicked)
    }
}

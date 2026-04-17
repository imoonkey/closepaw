package ai.closepaw.qa

import ai.closepaw.ui.chat.components.EmptyState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        compose.setContent { EmptyState(onSuggestionClick = {}) }

        suggestions.forEach { text ->
            compose.onNodeWithText("\"$text\"").assertExists()
        }
    }

    @Test fun tapping_suggestion_fires_callback_with_text() {
        var clicked: String? = null
        compose.setContent { EmptyState(onSuggestionClick = { clicked = it }) }

        compose.onNodeWithText("\"${suggestions[1]}\"").performClick()

        assert(clicked == suggestions[1]) { "expected ${suggestions[1]} got $clicked" }
    }
}

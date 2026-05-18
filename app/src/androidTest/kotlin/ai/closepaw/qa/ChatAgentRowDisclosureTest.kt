package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
import ai.closepaw.ui.theme.ClosePawTheme
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D2/D3 row disclosure: pill owns the toggle; row root is not clickable; the
 * final-answer region stays visible across collapse.
 */
@RunWith(AndroidJUnit4::class)
class ChatAgentRowDisclosureTest {

    @get:Rule val compose = createComposeRule()

    private val traceBlocks = listOf(
        ContentBlock.Thought("opening Settings"),
        ContentBlock.Action(
            ActionCardData(
                id = "a1",
                toolName = "open_app",
                description = "com.android.settings",
                state = ActionState.Success,
                resultSummary = "ok"
            )
        ),
    )

    private fun agent(
        rowState: RowState,
        state: AgentMessageState = AgentMessageState.Complete,
        withFinal: Boolean = true,
    ): ChatMessage.Agent {
        val blocks = if (withFinal) traceBlocks + ContentBlock.FinalText("All set.") else traceBlocks
        return ChatMessage.Agent(
            id = "a1",
            timestamp = 1_000L,
            contentBlocks = blocks,
            state = state,
            rowState = rowState,
            completedTimestamp = if (rowState == RowState.Complete) 4_800L else null,
        )
    }

    private fun assertExpanded() {
        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
    }

    private fun assertCollapsed() {
        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
    }

    @Test fun live_row_is_expanded_and_locked_open() {
        compose.setContent {
            ClosePawTheme {
                MessageBubble(
                    agent(RowState.Live, AgentMessageState.Streaming, withFinal = false)
                )
            }
        }

        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "live"))
        compose.onNodeWithText("opening Settings").assertIsDisplayed()
        // No pill on locked-open rows.
        compose.onNodeWithTag("qa-collapse-pill").assertDoesNotExist()
    }

    @Test fun row_default_collapses_when_transitioning_live_to_complete() {
        var rowState by mutableStateOf(RowState.Live)
        compose.setContent {
            val agentState = if (rowState == RowState.Live) AgentMessageState.Streaming
                else AgentMessageState.Complete
            ClosePawTheme {
                MessageBubble(agent(rowState, agentState))
            }
        }

        // Precondition: Live rows report "live" (locked-open), not "expanded".
        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "live"))

        rowState = RowState.Complete
        compose.waitForIdle()

        assertCollapsed()
    }

    @Test fun pill_toggles_trace_visibility() {
        compose.setContent {
            ClosePawTheme { MessageBubble(agent(RowState.Complete)) }
        }

        // Default = collapsed; user expands via pill.
        assertCollapsed()
        compose.onNodeWithTag("qa-collapse-pill").performClick()
        compose.waitForIdle()
        assertExpanded()

        compose.onNodeWithTag("qa-collapse-pill").performClick()
        compose.waitForIdle()
        assertCollapsed()
    }

    @Test fun final_answer_stays_visible_when_collapsed() {
        compose.setContent {
            ClosePawTheme { MessageBubble(agent(RowState.Complete)) }
        }

        assertCollapsed()
        compose.onNodeWithTag("qa-final-answer").assertIsDisplayed()
        compose.onNodeWithText("All set.").assertIsDisplayed()
    }

    @Test fun complete_with_no_final_renders_pill_but_no_final_region() {
        compose.setContent {
            ClosePawTheme { MessageBubble(agent(RowState.Complete, withFinal = false)) }
        }

        compose.onNodeWithTag("qa-collapse-pill").assertIsDisplayed()
        compose.onNodeWithTag("qa-final-answer").assertDoesNotExist()
    }
}

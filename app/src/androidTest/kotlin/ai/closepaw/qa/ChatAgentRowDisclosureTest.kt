package ai.closepaw.qa

import ai.closepaw.ui.chat.components.MessageBubble
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Track A spec §5: row disclosure UI.
 *
 * Locks the regression covered by codex review round-1 critical #2 — a row
 * that starts Live and later transitions to Complete must default-collapse.
 * The reducer-only ChatThoughtAndRowStateTest does not exercise the Compose
 * layer; this does.
 */
@RunWith(AndroidJUnit4::class)
class ChatAgentRowDisclosureTest {

    @get:Rule val compose = createComposeRule()

    private val sampleBlocks = listOf(
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
        ContentBlock.Text("All set.")
    )

    private fun agent(rowState: RowState, state: AgentMessageState = AgentMessageState.Complete) =
        ChatMessage.Agent(
            id = "a1",
            timestamp = 1_000L,
            contentBlocks = sampleBlocks,
            state = state,
            rowState = rowState,
            completedTimestamp = if (rowState == RowState.Complete) 4_800L else null
        )

    private fun assertExpanded() {
        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
    }

    private fun assertCollapsed() {
        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
    }

    @Test fun live_row_is_expanded_and_locked_open() {
        compose.setContent { MessageBubble(agent(RowState.Live, AgentMessageState.Streaming)) }

        compose.onNodeWithTag("qa-agent-bubble")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "live"))
        // Trace items rendered.
        compose.onNodeWithText("opening Settings").assertExists()
    }

    @Test fun row_default_collapses_when_transitioning_live_to_complete() {
        // Mimic the producer pattern: same row id, rowState swaps mid-flight.
        var rowState by mutableStateOf(RowState.Live)
        compose.setContent {
            val agentState = if (rowState == RowState.Live) AgentMessageState.Streaming
                else AgentMessageState.Complete
            MessageBubble(agent(rowState, agentState))
        }

        // Live → expanded
        assertExpanded()

        // Transition to Complete → default must collapse (regression guard).
        rowState = RowState.Complete
        compose.waitForIdle()

        assertCollapsed()
    }

    @Test fun user_toggle_persists_against_default() {
        compose.setContent { MessageBubble(agent(RowState.Complete)) }

        // Default = collapsed, then user expands.
        assertCollapsed()
        compose.onNodeWithTag("qa-agent-bubble").performClick()
        assertExpanded()

        // User toggle wins until they tap again.
        compose.onNodeWithTag("qa-agent-bubble").performClick()
        assertCollapsed()
    }

    @Test fun collapsed_summary_includes_action_count_and_elapsed() {
        compose.setContent { MessageBubble(agent(RowState.Complete)) }

        // Headline ladder picks the first thought as headline; spec §5.2 summary.
        compose.onNodeWithText("opening Settings · 1 action · 3.8s").assertExists()
    }
}

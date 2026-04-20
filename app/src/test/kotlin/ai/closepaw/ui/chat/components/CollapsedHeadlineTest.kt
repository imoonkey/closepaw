package ai.closepaw.ui.chat.components

import com.google.common.truth.Truth.assertThat
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import org.junit.Test

class CollapsedHeadlineTest {

    private fun agent(
        blocks: List<ContentBlock> = emptyList(),
        userPrompt: String? = null,
    ) = ChatMessage.Agent(
        id = "a1",
        timestamp = 0L,
        contentBlocks = blocks,
        state = AgentMessageState.Complete,
        rowState = RowState.Complete,
        userPrompt = userPrompt,
    )

    @Test
    fun `prompt wins ladder when present`() {
        val msg = agent(
            blocks = listOf(ContentBlock.Thought("thinking")),
            userPrompt = "go open settings"
        )
        assertThat(collapsedHeadline(msg)).isEqualTo("go open settings")
    }

    @Test
    fun `thought used when no prompt`() {
        val msg = agent(blocks = listOf(ContentBlock.Thought("planning")))
        assertThat(collapsedHeadline(msg)).isEqualTo("planning")
    }

    @Test
    fun `action used when no prompt or thought`() {
        val action = ActionCardData(
            id = "a", toolName = "click", description = "tap OK",
            state = ActionState.Success, resultSummary = null
        )
        val msg = agent(blocks = listOf(ContentBlock.Action(action)))
        assertThat(collapsedHeadline(msg)).isEqualTo("tap OK")
    }

    @Test
    fun `text used when no prompt thought or action`() {
        val msg = agent(blocks = listOf(ContentBlock.Text("Done.")))
        assertThat(collapsedHeadline(msg)).isEqualTo("Done.")
    }

    @Test
    fun `empty blocks falls back to no activity`() {
        val msg = agent(blocks = emptyList())
        assertThat(collapsedHeadline(msg)).isEqualTo("(no activity)")
    }
}

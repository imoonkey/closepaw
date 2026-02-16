package com.moonkey.androidagent.ui.chat

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.ui.chat.model.ActionCardData
import com.moonkey.androidagent.ui.chat.model.ActionState
import com.moonkey.androidagent.ui.chat.model.ContentBlock
import org.junit.Test

class ChatActionExecutionMappingTest {

    @Test
    fun `updates most recent matching action when duplicate ids exist`() {
        val blocks =
                listOf(
                        ContentBlock.Action(
                                ActionCardData(
                                        id = "dup-id",
                                        toolName = "Open app",
                                        description = "Open app",
                                        state = ActionState.Proposed
                                )
                        ),
                        ContentBlock.Action(
                                ActionCardData(
                                        id = "dup-id",
                                        toolName = "Mobile action",
                                        description = "Click element 3",
                                        state = ActionState.Proposed
                                )
                        )
                )

        val (updated, found) =
                updateActionBlockForExecution(
                        blocks = blocks,
                        actionId = "dup-id",
                        newState = ActionState.Success,
                        resultSummary = "Clicked"
                )

        assertThat(found).isTrue()
        val first = (updated[0] as ContentBlock.Action).data
        val second = (updated[1] as ContentBlock.Action).data
        assertThat(first.state).isEqualTo(ActionState.Proposed)
        assertThat(first.resultSummary).isNull()
        assertThat(second.state).isEqualTo(ActionState.Success)
        assertThat(second.resultSummary).isEqualTo("Clicked")
    }

    @Test
    fun `returns not found when no action id matches`() {
        val blocks =
                listOf(
                        ContentBlock.Action(
                                ActionCardData(
                                        id = "a1",
                                        toolName = "Open app",
                                        description = "Open app",
                                        state = ActionState.Proposed
                                )
                        )
                )

        val (updated, found) =
                updateActionBlockForExecution(
                        blocks = blocks,
                        actionId = "missing",
                        newState = ActionState.Success,
                        resultSummary = "ok"
                )

        assertThat(found).isFalse()
        assertThat(updated).isEqualTo(blocks)
    }
}

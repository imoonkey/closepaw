package ai.closepaw.ui.chat.components

import com.google.common.truth.Truth.assertThat
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.ContentBlock
import org.junit.Test

/**
 * UXFB-4: ThoughtGroup grouping logic. The pure [groupTrace] function is the
 * structural contract — composable styling is verified visually via
 * /ux-visual-debug. Cardinality (TurnPlanningPhaseRunner.kt:217-238): each turn
 * emits exactly one Thought + ≥1 Actions.
 */
class ThoughtGroupTest {

    private fun action(id: String, name: String = "tool"): ContentBlock.Action =
        ContentBlock.Action(
            ActionCardData(
                id = id,
                toolName = name,
                description = id,
                state = ActionState.Success,
            ),
        )

    private fun thought(text: String): ContentBlock.Thought = ContentBlock.Thought(text)

    @Test
    fun `single thought followed by many actions forms one group`() {
        val blocks = listOf(
            thought("plan it"),
            action("a1"),
            action("a2"),
            action("a3"),
        )

        val groups = groupTrace(blocks)

        assertThat(groups).hasSize(1)
        assertThat(groups[0].thought?.text).isEqualTo("plan it")
        assertThat(groups[0].items).hasSize(3)
    }

    @Test
    fun `actions before first thought become a header-less preface group`() {
        val blocks = listOf(
            action("a0"),
            thought("now I think"),
            action("a1"),
        )

        val groups = groupTrace(blocks)

        assertThat(groups).hasSize(2)
        assertThat(groups[0].thought).isNull()
        assertThat(groups[0].items).hasSize(1)
        assertThat(groups[1].thought?.text).isEqualTo("now I think")
        assertThat(groups[1].items).hasSize(1)
    }

    @Test
    fun `thought with no following actions still produces its own group`() {
        val blocks = listOf(
            thought("solo thought"),
            thought("next thought"),
            action("a1"),
        )

        val groups = groupTrace(blocks)

        assertThat(groups).hasSize(2)
        assertThat(groups[0].thought?.text).isEqualTo("solo thought")
        assertThat(groups[0].items).isEmpty()
        assertThat(groups[1].thought?.text).isEqualTo("next thought")
        assertThat(groups[1].items).hasSize(1)
    }

    @Test
    fun `multi-turn ordering is preserved across groups`() {
        val blocks = listOf(
            thought("turn 1"),
            action("t1a"),
            action("t1b"),
            thought("turn 2"),
            action("t2a"),
            thought("turn 3"),
            action("t3a"),
            action("t3b"),
            action("t3c"),
        )

        val groups = groupTrace(blocks)

        assertThat(groups.map { it.thought?.text }).containsExactly(
            "turn 1", "turn 2", "turn 3",
        ).inOrder()
        assertThat(groups[0].items).hasSize(2)
        assertThat(groups[1].items).hasSize(1)
        assertThat(groups[2].items).hasSize(3)
    }

    @Test
    fun `final text blocks are excluded from grouping`() {
        val blocks = listOf(
            thought("plan"),
            action("a1"),
            ContentBlock.FinalText("answer"),
        )

        val groups = groupTrace(blocks)

        assertThat(groups).hasSize(1)
        assertThat(groups[0].items).hasSize(1)
    }
}

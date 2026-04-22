package ai.closepaw.history.model

import com.google.common.truth.Truth.assertThat
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import org.junit.Test

class MessageConverterTest {

    @Test
    fun `user message round trip preserves content and role`() {
        val original = ChatMessage.User(id = "u1", timestamp = 123L, text = "hello world")

        val record = MessageConverter.toRecord(original)
        assertThat(record).isInstanceOf(MessageRecord.User::class.java)
        val userRecord = record as MessageRecord.User
        assertThat(userRecord.id).isEqualTo("u1")
        assertThat(userRecord.timestamp).isEqualTo(123L)
        assertThat(userRecord.text).isEqualTo("hello world")

        val restored = MessageConverter.fromRecord(record) as ChatMessage.User
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `agent message preserves content blocks and complete state`() {
        val original = ChatMessage.Agent(
            id = "a1",
            timestamp = 456L,
            contentBlocks = listOf(
                ContentBlock.Text("I will click."),
                ContentBlock.Action(
                    ActionCardData(
                        id = "act1",
                        toolName = "mobile_action",
                        description = "click button",
                        state = ActionState.Success,
                        resultSummary = "done"
                    )
                ),
                ContentBlock.Text("Done.")
            ),
            state = AgentMessageState.Complete
        )

        val record = MessageConverter.toRecord(original) as MessageRecord.Agent
        assertThat(record.isComplete).isTrue()
        assertThat(record.contentBlocks).hasSize(3)
        val actionRecord = record.contentBlocks[1] as ContentBlockRecord.Action
        assertThat(actionRecord.toolName).isEqualTo("mobile_action")
        assertThat(actionRecord.state).isEqualTo("success")
        assertThat(actionRecord.resultSummary).isEqualTo("done")

        val restored = MessageConverter.fromRecord(record) as ChatMessage.Agent
        assertThat(restored.state).isEqualTo(AgentMessageState.Complete)
        assertThat(restored.contentBlocks).hasSize(3)
        assertThat((restored.contentBlocks[0] as ContentBlock.Text).text).isEqualTo("I will click.")
        val restoredAction = (restored.contentBlocks[1] as ContentBlock.Action).data
        assertThat(restoredAction.state).isEqualTo(ActionState.Success)
    }

    @Test
    fun `agent message streaming state round trips`() {
        val original = ChatMessage.Agent(
            id = "a2",
            timestamp = 1L,
            contentBlocks = emptyList(),
            state = AgentMessageState.Streaming
        )

        val record = MessageConverter.toRecord(original) as MessageRecord.Agent
        assertThat(record.isComplete).isFalse()

        val restored = MessageConverter.fromRecord(record) as ChatMessage.Agent
        assertThat(restored.state).isEqualTo(AgentMessageState.Streaming)
    }

    @Test
    fun `action state strings parse to correct ActionState`() {
        val states = listOf(
            "proposed" to ActionState.Proposed,
            "executing" to ActionState.Executing,
            "success" to ActionState.Success,
            "failed" to ActionState.Failed,
            "skipped" to ActionState.Skipped,
            "bogus" to ActionState.Proposed
        )

        states.forEach { (stateStr, expected) ->
            val record = MessageRecord.Agent(
                id = "x",
                timestamp = 0L,
                contentBlocks = listOf(
                    ContentBlockRecord.Action(
                        id = "a",
                        toolName = "mobile_action",
                        description = "d",
                        state = stateStr,
                        resultSummary = null
                    )
                ),
                isComplete = false
            )
            val msg = MessageConverter.fromRecord(record) as ChatMessage.Agent
            val action = (msg.contentBlocks.single() as ContentBlock.Action).data
            assertThat(action.state).isEqualTo(expected)
        }
    }

    @Test
    fun `tool name maps to display name and icon`() {
        val record = MessageRecord.Agent(
            id = "t1",
            timestamp = 0L,
            contentBlocks = listOf(
                ContentBlockRecord.Action(
                    id = "a1",
                    toolName = "open_app",
                    description = "launch",
                    state = "proposed",
                    resultSummary = null
                )
            ),
            isComplete = true
        )

        val msg = MessageConverter.fromRecord(record) as ChatMessage.Agent
        val action = (msg.contentBlocks.single() as ContentBlock.Action).data
        assertThat(action.toolName).isEqualTo("Open app")
    }

    @Test
    fun `error rowState round-trips through persistence`() {
        val original = ChatMessage.Agent(
            id = "a-err",
            timestamp = 10L,
            contentBlocks = listOf(ContentBlock.Text("⚠ boom")),
            state = AgentMessageState.Complete,
            rowState = RowState.Error,
            completedTimestamp = 11L
        )

        val record = MessageConverter.toRecord(original) as MessageRecord.Agent
        assertThat(record.rowState).isEqualTo("error")

        val restored = MessageConverter.fromRecord(record) as ChatMessage.Agent
        assertThat(restored.rowState).isEqualTo(RowState.Error)
    }

    @Test
    fun `legacy record without rowState derives Complete from isComplete`() {
        val legacy = MessageRecord.Agent(
            id = "legacy-1",
            timestamp = 1L,
            contentBlocks = emptyList(),
            isComplete = true
            // rowState omitted = null (back-compat)
        )

        val restored = MessageConverter.fromRecord(legacy) as ChatMessage.Agent
        assertThat(restored.rowState).isEqualTo(RowState.Complete)
    }

    @Test
    fun `legacy streaming record without rowState derives Live`() {
        val legacy = MessageRecord.Agent(
            id = "legacy-2",
            timestamp = 1L,
            contentBlocks = emptyList(),
            isComplete = false
        )

        val restored = MessageConverter.fromRecord(legacy) as ChatMessage.Agent
        assertThat(restored.rowState).isEqualTo(RowState.Live)
    }

    @Test
    fun `fromRecords hydrates userPrompt from preceding User record`() {
        val records = listOf(
            MessageRecord.User(id = "u1", timestamp = 1L, text = "open settings"),
            MessageRecord.Agent(
                id = "a1",
                timestamp = 2L,
                contentBlocks = listOf(ContentBlockRecord.Text("ok")),
                isComplete = true,
                rowState = "complete"
            )
        )

        val restored = MessageConverter.fromRecords(records)
        val agent = restored[1] as ChatMessage.Agent
        assertThat(agent.userPrompt).isEqualTo("open settings")
    }

    @Test
    fun `fromRecords leaves userPrompt null when no preceding User record`() {
        val records = listOf(
            MessageRecord.Agent(
                id = "a-orphan",
                timestamp = 1L,
                contentBlocks = emptyList(),
                isComplete = true
            )
        )

        val restored = MessageConverter.fromRecords(records)
        val agent = restored[0] as ChatMessage.Agent
        assertThat(agent.userPrompt).isNull()
    }

    @Test
    fun `agent message preserves long thought verbatim through round trip`() {
        val longThought = "I should open Settings, scroll down to Accessibility, " +
            "tap Installed Services, find ClosePaw, toggle the switch, and confirm " +
            "the system dialog so the agent gains accessibility permission, then " +
            "verify the service is enabled before returning to the home screen."
        check(longThought.length > 200) { "test fixture must exceed 200 chars, was ${longThought.length}" }

        val original = ChatMessage.Agent(
            id = "a-long",
            timestamp = 1L,
            contentBlocks = listOf(ContentBlock.Thought(longThought)),
            state = AgentMessageState.Streaming
        )

        val record = MessageConverter.toRecord(original) as MessageRecord.Agent
        val thoughtRecord = record.contentBlocks.single() as
            ai.closepaw.history.model.ContentBlockRecord.Thought
        assertThat(thoughtRecord.text).isEqualTo(longThought)

        val restored = MessageConverter.fromRecord(record) as ChatMessage.Agent
        val restoredThought = restored.contentBlocks.single() as ContentBlock.Thought
        assertThat(restoredThought.text).isEqualTo(longThought)
    }
}

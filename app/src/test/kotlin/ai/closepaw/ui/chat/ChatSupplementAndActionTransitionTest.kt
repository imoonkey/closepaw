package ai.closepaw.ui.chat

import androidx.compose.runtime.mutableStateListOf
import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ActionOutcome
import ai.closepaw.protocol.ActionProposed
import ai.closepaw.protocol.MessageDelta
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.SupplementReceived
import ai.closepaw.protocol.TaskStarted
import ai.closepaw.protocol.TurnStarted
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ChatUiState
import ai.closepaw.ui.chat.model.ContentBlock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Gap-filling transitions for [ChatEventReducer] not covered by
 * [ChatEventReducerTest]: supplement-as-user-turn, executed-without-proposal,
 * non-success outcomes, error-without-open-agent, and turn-started buffer reset.
 * Locks the documented contract in doc/main/state_machines/ui_chat.md.
 */
class ChatSupplementAndActionTransitionTest {

    private val sessionId = SessionId("s1")

    private class Fixture {
        val uiState = MutableStateFlow(ChatUiState())
        val messages = mutableStateListOf<ChatMessage>()
        val buffer = StringBuilder()
        var currentAgentId: String? = null
        val reducer = ChatEventReducer(
            uiState = uiState,
            messages = messages,
            streamingBuffer = buffer,
            stateLock = Any(),
            setCurrentAgentMessageId = { currentAgentId = it }
        )
    }

    // ── SupplementReceived: user turn split mid-task ──

    @Test
    fun `supplement closes prior agent message and opens fresh one`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(MessageDelta(sessionId, 101L, turnId = "t1", delta = "working"))

        f.reducer.handle(SupplementReceived(sessionId, 200L, text = "wait, also do X"))

        // [User, Agent(closed), User(supplement), Agent(fresh)]
        assertThat(f.messages).hasSize(4)
        val firstAgent = f.messages[1] as ChatMessage.Agent
        assertThat(firstAgent.state).isEqualTo(AgentMessageState.Complete)
        val supplementUser = f.messages[2] as ChatMessage.User
        assertThat(supplementUser.text).isEqualTo("wait, also do X")
        val freshAgent = f.messages[3] as ChatMessage.Agent
        assertThat(freshAgent.state).isEqualTo(AgentMessageState.Thinking)
        assertThat(freshAgent.contentBlocks).isEmpty()
        assertThat(freshAgent.id).isEqualTo("supplement-200")
        assertThat(f.currentAgentId).isEqualTo("supplement-200")
    }

    @Test
    fun `supplement before any task still inserts user and opens agent`() {
        val f = Fixture()
        f.reducer.handle(SupplementReceived(sessionId, 50L, text = "early note"))

        assertThat(f.messages).hasSize(2)
        assertThat(f.messages[0]).isInstanceOf(ChatMessage.User::class.java)
        val agent = f.messages[1] as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Thinking)
    }

    // ── ActionExecuted with no preceding proposal ──

    @Test
    fun `executed without proposal synthesises action card in executed state`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        // Seed the streaming buffer so the empty-buffer assertion below actually
        // verifies the no-match clear path in ChatEventReducer (`streamingBuffer.clear()`).
        f.reducer.handle(MessageDelta(sessionId, 105L, turnId = "t1", delta = "stale text"))

        f.reducer.handle(
            ActionExecuted(
                sessionId = sessionId,
                timestamp = 110L,
                actionId = "a-orphan",
                toolName = "click",
                outcome = ActionOutcome.SUCCESS,
                result = "clicked"
            )
        )

        val agent = f.messages.last() as ChatMessage.Agent
        val action = agent.contentBlocks.filterIsInstance<ContentBlock.Action>().single()
        assertThat(action.data.id).isEqualTo("a-orphan")
        assertThat(action.data.state).isEqualTo(ActionState.Success)
        assertThat(action.data.resultSummary).isEqualTo("clicked")
        // The no-match branch in ChatEventReducer.handleActionExecuted clears the
        // streaming buffer that the seeded MessageDelta populated above.
        assertThat(f.buffer.toString()).isEmpty()
    }

    @Test
    fun `executed FAILED maps to ActionState Failed`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(
            ActionProposed(sessionId, 101L, actionId = "a1", toolName = "click", description = "tap")
        )
        f.reducer.handle(
            ActionExecuted(
                sessionId = sessionId,
                timestamp = 102L,
                actionId = "a1",
                toolName = "click",
                outcome = ActionOutcome.FAILED,
                result = "no element"
            )
        )

        val action = (f.messages.last() as ChatMessage.Agent)
            .contentBlocks.single() as ContentBlock.Action
        assertThat(action.data.state).isEqualTo(ActionState.Failed)
        assertThat(action.data.resultSummary).isEqualTo("no element")
    }

    @Test
    fun `executed SKIPPED maps to ActionState Skipped`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(
            ActionProposed(sessionId, 101L, actionId = "a1", toolName = "click", description = "tap")
        )
        f.reducer.handle(
            ActionExecuted(
                sessionId = sessionId,
                timestamp = 102L,
                actionId = "a1",
                toolName = "click",
                outcome = ActionOutcome.SKIPPED,
                result = null
            )
        )

        val action = (f.messages.last() as ChatMessage.Agent)
            .contentBlocks.single() as ContentBlock.Action
        assertThat(action.data.state).isEqualTo(ActionState.Skipped)
        assertThat(action.data.resultSummary).isNull()
    }

    // ── Error without an open agent ──

    @Test
    fun `error before any task creates synthetic agent message`() {
        val f = Fixture()
        f.reducer.handle(SessionError(sessionId, 50L, message = "boom"))

        assertThat(f.uiState.value.showEmptyState).isFalse()
        assertThat(f.messages).hasSize(1)
        val agent = f.messages[0] as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Complete)
        val text = agent.contentBlocks.single() as ContentBlock.Text
        assertThat(text.text).isEqualTo("⚠️ boom")
    }

    // ── TurnStarted resets streaming buffer ──

    @Test
    fun `turn started clears streaming buffer so next delta starts fresh`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(MessageDelta(sessionId, 101L, turnId = "t1", delta = "first"))

        f.reducer.handle(TurnStarted(sessionId, 200L, turnId = "t2", turnNumber = 2))
        f.reducer.handle(MessageDelta(sessionId, 201L, turnId = "t2", delta = "second"))

        // Buffer was cleared between turns, so the trailing text block reflects only "second".
        val agent = f.messages.last() as ChatMessage.Agent
        val texts = agent.contentBlocks.filterIsInstance<ContentBlock.Text>()
        assertThat(texts.last().text).isEqualTo("second")
    }

    // ── Action card splits text into a fresh trailing text block ──

    @Test
    fun `text after action card lands in a new text block, not appended to prior text`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(MessageDelta(sessionId, 101L, turnId = "t1", delta = "I'll click."))
        f.reducer.handle(
            ActionProposed(sessionId, 102L, actionId = "a1", toolName = "click", description = "tap")
        )
        f.reducer.handle(MessageDelta(sessionId, 103L, turnId = "t1", delta = "Done."))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.contentBlocks).hasSize(3)
        assertThat((agent.contentBlocks[0] as ContentBlock.Text).text).isEqualTo("I'll click.")
        assertThat(agent.contentBlocks[1]).isInstanceOf(ContentBlock.Action::class.java)
        assertThat((agent.contentBlocks[2] as ContentBlock.Text).text).isEqualTo("Done.")
    }
}

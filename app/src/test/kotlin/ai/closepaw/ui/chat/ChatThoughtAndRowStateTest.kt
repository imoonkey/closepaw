package ai.closepaw.ui.chat

import androidx.compose.runtime.mutableStateListOf
import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ActionOutcome
import ai.closepaw.protocol.ActionProposed
import ai.closepaw.protocol.MessageDelta
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TaskStarted
import ai.closepaw.protocol.ThoughtUpdate
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ChatUiState
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Track A spec §3 + §5: ThoughtUpdate routing into ContentBlock.Thought, plus
 * the four-state RowState machine (Live → Complete / Error). Locks the
 * chronological-trace ordering invariant from §5: trace items appear in
 * arrival order with no reordering, no deduplication.
 */
class ChatThoughtAndRowStateTest {

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

    @Test
    fun `thought update appends a Thought block`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(ThoughtUpdate(sessionId, 110L, full = "I should open Settings", compact = "I should open Settings"))

        val agent = f.messages.last() as ChatMessage.Agent
        val thought = agent.contentBlocks.single() as ContentBlock.Thought
        assertThat(thought.text).isEqualTo("I should open Settings")
    }

    @Test
    fun `multiple thought updates produce multiple Thought blocks in order`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(ThoughtUpdate(sessionId, 110L, full = "step one", compact = "step one"))
        f.reducer.handle(ThoughtUpdate(sessionId, 111L, full = "step two", compact = "step two"))
        f.reducer.handle(ThoughtUpdate(sessionId, 112L, full = "step three", compact = "step three"))

        val agent = f.messages.last() as ChatMessage.Agent
        val thoughts = agent.contentBlocks.filterIsInstance<ContentBlock.Thought>()
        assertThat(thoughts.map { it.text })
            .containsExactly("step one", "step two", "step three")
            .inOrder()
    }

    @Test
    fun `thought interleaved with action preserves chronological order`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(ThoughtUpdate(sessionId, 110L, full = "open Settings", compact = "open Settings"))
        f.reducer.handle(
            ActionProposed(sessionId, 111L, actionId = "a1", toolName = "click", description = "tap")
        )
        f.reducer.handle(ThoughtUpdate(sessionId, 112L, full = "now find Accessibility", compact = "now find Accessibility"))

        val agent = f.messages.last() as ChatMessage.Agent
        val kinds = agent.contentBlocks.map { it::class.simpleName }
        assertThat(kinds).containsExactly("Thought", "Action", "Thought").inOrder()
    }

    @Test
    fun `text after thought lands in a new Text block, not appended`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(MessageDelta(sessionId, 105L, turnId = "t1", delta = "first"))
        f.reducer.handle(ThoughtUpdate(sessionId, 110L, full = "rethinking", compact = "rethinking"))
        f.reducer.handle(MessageDelta(sessionId, 115L, turnId = "t1", delta = "second"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.contentBlocks).hasSize(3)
        assertThat((agent.contentBlocks[0] as ContentBlock.Text).text).isEqualTo("first")
        assertThat(agent.contentBlocks[1]).isInstanceOf(ContentBlock.Thought::class.java)
        assertThat((agent.contentBlocks[2] as ContentBlock.Text).text).isEqualTo("second")
    }

    @Test
    fun `empty thought is ignored`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(ThoughtUpdate(sessionId, 110L, full = "", compact = ""))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.contentBlocks.filterIsInstance<ContentBlock.Thought>()).isEmpty()
    }

    @Test
    fun `task started opens row in Live state`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.rowState).isEqualTo(RowState.Live)
    }

    @Test
    fun `task completion transitions row to Complete`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(
            TaskCompleted(
                sessionId = sessionId,
                timestamp = 200L,
                taskId = "task-1",
                result = "done",
                outcome = TaskOutcome.GOAL_ACHIEVED
            )
        )

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.rowState).isEqualTo(RowState.Complete)
    }

    @Test
    fun `session error transitions row to Error`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(SessionError(sessionId, 150L, message = "boom"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.rowState).isEqualTo(RowState.Error)
    }

    @Test
    fun `error followed by next task keeps prior row in Error not Complete`() {
        // Locked-open invariant: an Error row stays Error even when the next
        // user turn closes it.
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))
        f.reducer.handle(SessionError(sessionId, 150L, message = "boom"))

        f.reducer.handle(TaskStarted(sessionId, 200L, taskId = "task-2", input = "again"))

        val firstAgent = f.messages.first { it is ChatMessage.Agent } as ChatMessage.Agent
        assertThat(firstAgent.rowState).isEqualTo(RowState.Error)
    }

    @Test
    fun `thought after action executed appends a new Thought block, not into action`() {
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
                outcome = ActionOutcome.SUCCESS,
                result = "ok"
            )
        )
        f.reducer.handle(ThoughtUpdate(sessionId, 103L, full = "now what", compact = "now what"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.contentBlocks).hasSize(2)
        assertThat(agent.contentBlocks[0]).isInstanceOf(ContentBlock.Action::class.java)
        assertThat((agent.contentBlocks[1] as ContentBlock.Thought).text).isEqualTo("now what")
    }
}

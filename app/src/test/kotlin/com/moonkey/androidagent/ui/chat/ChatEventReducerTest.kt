package com.moonkey.androidagent.ui.chat

import androidx.compose.runtime.mutableStateListOf
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.ActionExecuted
import com.moonkey.androidagent.protocol.ActionOutcome
import com.moonkey.androidagent.protocol.ActionProposed
import com.moonkey.androidagent.protocol.MessageDelta
import com.moonkey.androidagent.protocol.SessionError
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.protocol.TaskCompleted
import com.moonkey.androidagent.protocol.TaskOutcome
import com.moonkey.androidagent.protocol.TaskStarted
import com.moonkey.androidagent.ui.chat.model.ActionState
import com.moonkey.androidagent.ui.chat.model.AgentMessageState
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.chat.model.ChatUiState
import com.moonkey.androidagent.ui.chat.model.ContentBlock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class ChatEventReducerTest {

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
    fun `task-start event inserts user turn and opens agent message`() {
        val f = Fixture()

        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "hi"))

        assertThat(f.uiState.value.showEmptyState).isFalse()
        assertThat(f.messages).hasSize(2)
        val user = f.messages[0] as ChatMessage.User
        assertThat(user.text).isEqualTo("hi")
        assertThat(user.timestamp).isEqualTo(100L)
        val agent = f.messages[1] as ChatMessage.Agent
        assertThat(agent.id).isEqualTo("task-1")
        assertThat(agent.state).isEqualTo(AgentMessageState.Thinking)
        assertThat(agent.contentBlocks).isEmpty()
        assertThat(f.currentAgentId).isEqualTo("task-1")
    }

    @Test
    fun `streaming deltas accumulate text into agent message`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(MessageDelta(sessionId, 101L, turnId = "t1", delta = "Hello"))
        f.reducer.handle(MessageDelta(sessionId, 102L, turnId = "t1", delta = ", world"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Streaming)
        assertThat(agent.contentBlocks).hasSize(1)
        val text = agent.contentBlocks[0] as ContentBlock.Text
        assertThat(text.text).isEqualTo("Hello, world")
    }

    @Test
    fun `action proposal then execution transitions card to success state`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "do it"))

        f.reducer.handle(
            ActionProposed(
                sessionId = sessionId,
                timestamp = 101L,
                actionId = "a1",
                toolName = "click",
                description = "Tap OK"
            )
        )

        val afterProposal = f.messages.last() as ChatMessage.Agent
        val proposedBlock = afterProposal.contentBlocks.single() as ContentBlock.Action
        assertThat(proposedBlock.data.id).isEqualTo("a1")
        assertThat(proposedBlock.data.state).isEqualTo(ActionState.Proposed)
        assertThat(proposedBlock.data.resultSummary).isNull()

        f.reducer.handle(
            ActionExecuted(
                sessionId = sessionId,
                timestamp = 102L,
                actionId = "a1",
                toolName = "click",
                outcome = ActionOutcome.SUCCESS,
                result = "Tapped"
            )
        )

        val afterExec = f.messages.last() as ChatMessage.Agent
        assertThat(afterExec.contentBlocks).hasSize(1)
        val executedBlock = afterExec.contentBlocks.single() as ContentBlock.Action
        assertThat(executedBlock.data.id).isEqualTo("a1")
        assertThat(executedBlock.data.state).isEqualTo(ActionState.Success)
        assertThat(executedBlock.data.resultSummary).isEqualTo("Tapped")
    }

    @Test
    fun `task completion marks agent message complete and clears current id`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "start"))
        f.reducer.handle(MessageDelta(sessionId, 101L, turnId = "t1", delta = "progress"))

        f.reducer.handle(
            TaskCompleted(
                sessionId = sessionId,
                timestamp = 200L,
                taskId = "task-1",
                result = "All done",
                outcome = TaskOutcome.GOAL_ACHIEVED
            )
        )

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Complete)
        val texts = agent.contentBlocks.filterIsInstance<ContentBlock.Text>()
        assertThat(texts.last().text).isEqualTo("All done")
        assertThat(f.currentAgentId).isNull()
    }

    @Test
    fun `session error appends warning text and marks message complete`() {
        val f = Fixture()
        f.reducer.handle(TaskStarted(sessionId, 100L, taskId = "task-1", input = "go"))

        f.reducer.handle(SessionError(sessionId, 150L, message = "boom"))

        val agent = f.messages.last() as ChatMessage.Agent
        assertThat(agent.state).isEqualTo(AgentMessageState.Complete)
        val lastText = agent.contentBlocks.filterIsInstance<ContentBlock.Text>().last()
        assertThat(lastText.text).isEqualTo("⚠️ boom")
    }
}

package ai.closepaw.ui.chat

import androidx.compose.runtime.snapshots.SnapshotStateList
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ActionOutcome
import ai.closepaw.protocol.ActionProposed
import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.MessageDelta
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SupplementReceived
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskStarted
import ai.closepaw.protocol.ThoughtUpdate
import ai.closepaw.protocol.TurnPhaseChanged
import ai.closepaw.protocol.TurnStarted
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ChatUiState
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import ai.closepaw.ui.common.formatToolName
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ChatEventReducer(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val messages: SnapshotStateList<ChatMessage>,
    private val streamingBuffer: StringBuilder,
    private val stateLock: Any,
    private val setCurrentAgentMessageId: (String?) -> Unit
) {
    companion object {
        private const val TAG = "ChatViewModel"
    }

    fun handle(event: AgentEvent) {
        synchronized(stateLock) {
            when (event) {
                is TaskStarted -> handleTaskStarted(event)
                is TurnStarted -> handleTurnStarted(event)
                is TurnPhaseChanged -> Unit
                is MessageDelta -> handleMessageDelta(event)
                is ThoughtUpdate -> handleThoughtUpdate(event)
                is ActionProposed -> handleActionProposed(event)
                is ActionExecuted -> handleActionExecuted(event)
                is TaskCompleted -> handleTaskCompleted(event)
                is SessionError -> handleError(event)
                is SupplementReceived -> handleSupplement(event)
                else -> Unit
            }
        }
    }

    private fun handleTaskStarted(event: TaskStarted) {
        uiState.update { it.copy(showEmptyState = false) }
        insertUserTurn(event.input, event.timestamp, agentId = event.taskId)
    }

    private fun handleTurnStarted(event: TurnStarted) {
        android.util.Log.d(TAG, "TurnStarted: turn=${event.turnNumber}, clearing buffer")
        streamingBuffer.clear()
    }

    private fun handleMessageDelta(event: MessageDelta) {
        android.util.Log.d(TAG, "MessageDelta received: turnId=${event.turnId}, delta=${event.delta.take(30)}...")
        streamingBuffer.append(event.delta)

        updateLastAgentMessage { msg ->
            val updatedBlocks = updateOrAppendTextBlock(msg.contentBlocks, streamingBuffer.toString())
            msg.copy(contentBlocks = updatedBlocks, state = AgentMessageState.Streaming)
        }
    }

    private fun updateOrAppendTextBlock(blocks: List<ContentBlock>, text: String): List<ContentBlock> {
        if (blocks.isEmpty()) return listOf(ContentBlock.Text(text))
        val lastBlock = blocks.last()
        return if (lastBlock is ContentBlock.Text) {
            blocks.dropLast(1) + ContentBlock.Text(text)
        } else {
            blocks + ContentBlock.Text(text)
        }
    }

    private fun handleThoughtUpdate(event: ThoughtUpdate) {
        val text = event.full
        if (text.isEmpty()) return
        // Streaming text after a thought begins a new Text block, mirroring the
        // ActionProposed behavior — the trace is chronological.
        streamingBuffer.clear()
        updateLastAgentMessage { msg ->
            msg.copy(contentBlocks = msg.contentBlocks + ContentBlock.Thought(text))
        }
    }

    private fun handleActionProposed(event: ActionProposed) {
        val newAction =
            ActionCardData(
                id = event.actionId,
                toolName = formatToolName(event.toolName),
                description = event.description,
                state = ActionState.Proposed,
                resultSummary = null
            )

        streamingBuffer.clear()
        updateLastAgentMessage { msg ->
            msg.copy(contentBlocks = msg.contentBlocks + ContentBlock.Action(newAction))
        }
    }

    private fun handleActionExecuted(event: ActionExecuted) {
        val newState = when (event.outcome) {
            ActionOutcome.SUCCESS -> ActionState.Success
            ActionOutcome.FAILED -> ActionState.Failed
            ActionOutcome.SKIPPED -> ActionState.Skipped
        }
        updateLastAgentMessage { msg ->
            val (updatedExisting, found) =
                updateActionBlockForExecution(
                    blocks = msg.contentBlocks,
                    actionId = event.actionId,
                    newState = newState,
                    resultSummary = event.result
                )

            val updatedBlocks =
                if (found) {
                    updatedExisting
                } else {
                    streamingBuffer.clear()
                    val newAction =
                        ActionCardData(
                            id = event.actionId,
                            toolName = formatToolName(event.toolName),
                            description = event.result ?: event.toolName,
                            state = newState,
                            resultSummary = event.result
                        )
                    msg.contentBlocks + ContentBlock.Action(newAction)
                }
            msg.copy(contentBlocks = updatedBlocks)
        }
    }

    private fun handleTaskCompleted(event: TaskCompleted) {
        val isError = event.outcome == ai.closepaw.protocol.TaskOutcome.ERROR
        appendCompletionToMessages(
            messages = messages,
            rawResult = event.result,
            timestamp = event.timestamp,
            taskId = event.taskId,
            isError = isError,
            handoff = event.handoff,
        )
        streamingBuffer.clear()
        setCurrentAgentMessageId(null)
    }

    private fun handleError(event: SessionError) {
        val errorText = "⚠️ ${event.message}"
        val index = messages.indexOfLast { it is ChatMessage.Agent }
        if (index >= 0) {
            val current = messages[index] as ChatMessage.Agent
            messages[index] = current.copy(
                contentBlocks = current.contentBlocks + ContentBlock.Text(errorText),
                state = AgentMessageState.Complete,
                rowState = RowState.Error,
                completedTimestamp = event.timestamp
            )
        } else {
            messages.add(
                ChatMessage.Agent(
                    id = "error-${event.timestamp}",
                    timestamp = event.timestamp,
                    contentBlocks = listOf(ContentBlock.Text(errorText)),
                    state = AgentMessageState.Complete,
                    rowState = RowState.Error,
                    completedTimestamp = event.timestamp
                )
            )
            uiState.update { it.copy(showEmptyState = false) }
        }
    }

    private fun handleSupplement(event: SupplementReceived) {
        insertUserTurn(event.text, event.timestamp)
    }

    /**
     * Universal "user message splits the conversation" operation.
     *
     * Used by both [handleTaskStarted] (new task after idle) and
     * [handleSupplement] (mid-task user amendment). The chat UI doesn't
     * distinguish between the two — both close the current agent segment,
     * insert a user bubble, and open a fresh agent segment.
     */
    private fun insertUserTurn(text: String, timestamp: Long, agentId: String? = null) {
        // 1. Close current agent message (idempotent if already Complete or absent)
        updateLastAgentMessage { msg ->
            val sealing = msg.state != AgentMessageState.Complete
            msg.copy(
                state = AgentMessageState.Complete,
                rowState = if (msg.rowState == RowState.Error) RowState.Error else RowState.Complete,
                // Only stamp on the Live/Waiting → terminal transition; preserve
                // existing values (including null on legacy rows that never had
                // their completion timestamp persisted).
                completedTimestamp = if (sealing) msg.completedTimestamp ?: timestamp else msg.completedTimestamp
            )
        }

        // 2. Insert user message
        messages.add(
            ChatMessage.User(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                text = text
            )
        )

        // 3. New agent message for subsequent actions
        val id = agentId ?: "supplement-$timestamp"
        streamingBuffer.clear()
        setCurrentAgentMessageId(id)
        messages.add(
            ChatMessage.Agent(
                id = id,
                timestamp = timestamp,
                contentBlocks = emptyList(),
                state = AgentMessageState.Thinking,
                rowState = RowState.Live,
                userPrompt = text,
            )
        )
    }

    private inline fun updateLastAgentMessage(transform: (ChatMessage.Agent) -> ChatMessage.Agent) {
        val index = messages.indexOfLast { it is ChatMessage.Agent }
        if (index >= 0) {
            val current = messages[index] as ChatMessage.Agent
            // Drop late streaming events that arrive after the row sealed
            // (e.g. ThoughtUpdate emitted after TaskCompleted). Sealed rows
            // are immutable per Track A spec §5.
            if (current.state == AgentMessageState.Complete) return
            messages[index] = transform(current)
        }
    }
}

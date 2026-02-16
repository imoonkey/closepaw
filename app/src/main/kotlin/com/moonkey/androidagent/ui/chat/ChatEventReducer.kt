package com.moonkey.androidagent.ui.chat

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.moonkey.androidagent.protocol.ActionExecuted
import com.moonkey.androidagent.protocol.ActionProposed
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.MessageDelta
import com.moonkey.androidagent.protocol.SessionError
import com.moonkey.androidagent.protocol.SupplementReceived
import com.moonkey.androidagent.protocol.TaskCompleted
import com.moonkey.androidagent.protocol.TaskStarted
import com.moonkey.androidagent.protocol.TurnPhaseChanged
import com.moonkey.androidagent.protocol.TurnStarted
import com.moonkey.androidagent.ui.chat.model.ActionCardData
import com.moonkey.androidagent.ui.chat.model.ActionState
import com.moonkey.androidagent.ui.chat.model.AgentMessageState
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.chat.model.ChatUiState
import com.moonkey.androidagent.ui.chat.model.ContentBlock
import com.moonkey.androidagent.ui.common.formatToolName
import com.moonkey.androidagent.ui.common.getToolIcon
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ChatEventReducer(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val messages: SnapshotStateList<ChatMessage>,
    private val streamingBuffer: StringBuilder,
    private val stateLock: Any,
    private val setCurrentAgentMessageId: (String?) -> Unit,
    private val onTaskCompleted: (() -> Unit)?
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
                is ActionProposed -> handleActionProposed(event)
                is ActionExecuted -> handleActionExecuted(event)
                is TaskCompleted -> handleTaskCompleted(event)
                is SessionError -> handleError()
                is SupplementReceived -> handleSupplement(event)
                else -> Unit
            }
        }
    }

    private fun handleTaskStarted(event: TaskStarted) {
        uiState.update { it.copy(showEmptyState = false) }

        messages.add(
            ChatMessage.User(
                id = UUID.randomUUID().toString(),
                timestamp = event.timestamp,
                text = event.input
            )
        )

        streamingBuffer.clear()
        setCurrentAgentMessageId(event.taskId)
        messages.add(
            ChatMessage.Agent(
                id = event.taskId,
                timestamp = event.timestamp,
                contentBlocks = emptyList(),
                state = AgentMessageState.Thinking
            )
        )
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

    private fun handleActionProposed(event: ActionProposed) {
        val newAction =
            ActionCardData(
                id = event.actionId,
                toolName = formatToolName(event.toolName),
                toolIcon = getToolIcon(event.toolName),
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
        val newState = if (event.success) ActionState.Success else ActionState.Failed
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
                            toolIcon = getToolIcon(event.toolName),
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
        val completionText = completionSummary(event.result)
        appendCompletionToMessages(messages, completionText, event.timestamp, event.taskId)
        streamingBuffer.clear()
        setCurrentAgentMessageId(null)
        onTaskCompleted?.invoke()
    }

    private fun handleError() {
        updateLastAgentMessage { msg -> msg.copy(state = AgentMessageState.Complete) }
    }

    private fun handleSupplement(event: SupplementReceived) {
        messages.add(
            ChatMessage.User(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                text = event.text
            )
        )
    }

    private inline fun updateLastAgentMessage(transform: (ChatMessage.Agent) -> ChatMessage.Agent) {
        val index = messages.indexOfLast { it is ChatMessage.Agent }
        if (index >= 0) {
            val current = messages[index] as ChatMessage.Agent
            messages[index] = transform(current)
        }
    }
}

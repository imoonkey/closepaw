package com.moonkey.androidagent.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonkey.androidagent.app.AgentService
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.model.MessageConverter
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.session.AgentSession
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun completionSummary(result: String?): String =
        result?.takeIf { it.isNotBlank() } ?: "Task completed"

internal fun shouldHandleReboundEvent(
        eventTimestamp: Long,
        replayCutoffTimestamp: Long?
): Boolean = replayCutoffTimestamp == null || eventTimestamp > replayCutoffTimestamp

internal fun appendCompletionToMessages(
        messages: MutableList<ChatMessage>,
        completionText: String,
        timestamp: Long,
        taskId: String
) {
    val index = messages.indexOfLast { it is ChatMessage.Agent }
    if (index >= 0) {
        val current = messages[index] as ChatMessage.Agent
        messages[index] =
                current.copy(
                        contentBlocks = current.contentBlocks + ContentBlock.Text(completionText),
                        state = AgentMessageState.Complete
                )
        return
    }

    messages.add(
            ChatMessage.Agent(
                    id = taskId,
                    timestamp = timestamp,
                    contentBlocks = listOf(ContentBlock.Text(completionText)),
                    state = AgentMessageState.Complete
            )
    )
}

/**
 * ChatViewModel - Manages chat state and event collection.
 *
 * Responsibilities:
 * - Collect events from AgentSession.events
 * - Maintain message list with mutableStateListOf<ChatMessage>()
 * - Handle streaming text accumulation
 * - Manage input state (Idle/Working)
 * - Manage session history (list, resume, delete)
 */
class ChatViewModel(
        private val sessionProvider: () -> AgentSession?,
        private val sessionHistoryManager: SessionHistoryManager? = null,
        private val onSessionNeeded: ((String) -> Unit)? = null,
        private val onTaskCompleted: (() -> Unit)? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Messages (Compose observable list)
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage>
        get() = _messages

    // Streaming accumulator
    private val streamingBuffer = StringBuilder()
    private var currentAgentMessageId: String? = null

    // Active event collection job
    private var eventCollectionJob: kotlinx.coroutines.Job? = null

    private val eventReducer = EventReducer()
    private val sessionHistoryController =
            ChatSessionHistoryController(
                    scope = viewModelScope,
                    sessionHistoryManager = sessionHistoryManager,
                    messages = _messages,
                    streamingBuffer = streamingBuffer,
                    setCurrentAgentMessageId = { currentAgentMessageId = it },
                    uiState = _uiState,
            )
    val sessions: StateFlow<List<SessionInfo>> = sessionHistoryController.sessions

    /** Start collecting events from a session. */
    fun startEventCollection(
            session: AgentSession,
            replayCutoffTimestamp: Long? = null
    ) {
        // Cancel any existing collection
        eventCollectionJob?.cancel()

        eventCollectionJob =
                viewModelScope.launch {
                    session.events.collect { event ->
                        if (shouldHandleReboundEvent(event.timestamp, replayCutoffTimestamp)) {
                            handleEvent(event)
                        }
                    }
                }
    }

    /** Handle incoming AgentEvent. */
    private fun handleEvent(event: AgentEvent) {
        eventReducer.handle(event)
    }

    private inner class EventReducer {
        fun handle(event: AgentEvent) {
            when (event) {
                is AgentEvent.TaskStarted -> handleTaskStarted(event)
                is AgentEvent.TurnStarted -> handleTurnStarted(event)
                is AgentEvent.TurnPhaseChanged -> Unit
                is AgentEvent.MessageDelta -> handleMessageDelta(event)
                is AgentEvent.ActionProposed -> handleActionProposed(event)
                is AgentEvent.ActionExecuted -> handleActionExecuted(event)
                is AgentEvent.TaskCompleted -> handleTaskCompleted(event)
                is AgentEvent.SessionError -> handleError(event)
                is AgentEvent.SupplementReceived -> handleSupplement(event)
                else -> {
                    /* Ignore other events (ScreenCaptured, etc) */
                }
            }
        }

        private fun handleTaskStarted(event: AgentEvent.TaskStarted) {
            // Update UI state — capsule mode is managed by CapsuleStateHolder
            _uiState.update { it.copy(showEmptyState = false) }

            val userMsgId = UUID.randomUUID().toString()

            // Record user message to history
            // Add user message to UI
            _messages.add(
                    ChatMessage.User(
                            id = userMsgId,
                            timestamp = event.timestamp,
                            text = event.input
                    )
            )

            // Prepare agent message placeholder with empty content blocks
            streamingBuffer.clear()
            currentAgentMessageId = event.taskId
            _messages.add(
                    ChatMessage.Agent(
                            id = event.taskId,
                            timestamp = event.timestamp,
                            contentBlocks = emptyList(),
                            state = AgentMessageState.Thinking
                    )
            )
        }

        private fun handleTurnStarted(event: AgentEvent.TurnStarted) {
            // Clear streaming buffer at turn start to properly segment text between turns
            // This prevents text from previous turns accumulating with new turn text
            android.util.Log.d(TAG, "TurnStarted: turn=${event.turnNumber}, clearing buffer")
            streamingBuffer.clear()
        }

        private fun handleMessageDelta(event: AgentEvent.MessageDelta) {
            android.util.Log.d(
                    TAG,
                    "MessageDelta received: turnId=${event.turnId}, delta=${event.delta.take(30)}..."
            )
            streamingBuffer.append(event.delta)

            updateLastAgentMessage { msg ->
                val updatedBlocks =
                        updateOrAppendTextBlock(msg.contentBlocks, streamingBuffer.toString())
                msg.copy(contentBlocks = updatedBlocks, state = AgentMessageState.Streaming)
            }
        }

        /**
         * Update the last Text block or append a new one if needed. This maintains proper
         * interleaving: if the last block is an Action, we start a new Text block.
         */
        private fun updateOrAppendTextBlock(
                blocks: List<ContentBlock>,
                text: String
        ): List<ContentBlock> {
            if (blocks.isEmpty()) {
                return listOf(ContentBlock.Text(text))
            }

            val lastBlock = blocks.last()
            return if (lastBlock is ContentBlock.Text) {
                // Update the existing last text block
                blocks.dropLast(1) + ContentBlock.Text(text)
            } else {
                // Last block is an action - append new text block
                blocks + ContentBlock.Text(text)
            }
        }

        private fun handleActionProposed(event: AgentEvent.ActionProposed) {
            val newAction =
                    ActionCardData(
                            id = event.actionId,
                            toolName = formatToolName(event.toolName),
                            toolIcon = getToolIcon(event.toolName),
                            description = event.description,
                            state = ActionState.Proposed,
                            resultSummary = null
                    )

            // Record action to history

            // Clear streaming buffer - text before this action is "finalized"
            // New text will go into a new Text block after the action
            streamingBuffer.clear()

            updateLastAgentMessage { msg ->
                // Append action block
                msg.copy(contentBlocks = msg.contentBlocks + ContentBlock.Action(newAction))
            }
        }

        private fun handleActionExecuted(event: AgentEvent.ActionExecuted) {
            val newState = if (event.success) ActionState.Success else ActionState.Failed
            val stateString = if (event.success) "success" else "failed"

            updateLastAgentMessage { msg ->
                // Find existing action block and update it, or add new one
                val existingBlockIndex =
                        msg.contentBlocks.indexOfFirst { block ->
                            block is ContentBlock.Action && block.data.id == event.actionId
                        }

                val updatedBlocks =
                        if (existingBlockIndex >= 0) {
                            // Update existing action block
                            msg.contentBlocks.mapIndexed { index, block ->
                                if (index == existingBlockIndex && block is ContentBlock.Action) {
                                    ContentBlock.Action(
                                            block.data.copy(
                                                    state = newState,
                                                    resultSummary = event.result
                                            )
                                    )
                                } else block
                            }
                        } else {
                            // Action wasn't proposed first - add it directly
                            // Clear streaming buffer first since this is a new action
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

        private fun handleTaskCompleted(event: AgentEvent.TaskCompleted) {
            // Capsule mode transitions are handled by CapsuleStateHolder
            val completionText = completionSummary(event.result)

            // Completion text must always be present, even when no agent bubble exists yet.
            appendCompletionToMessages(_messages, completionText, event.timestamp, event.taskId)

            // Reset streaming state
            streamingBuffer.clear()
            currentAgentMessageId = null

            // Notify that task is completed (for session cleanup)
            onTaskCompleted?.invoke()
        }

        private fun handleError(event: AgentEvent.SessionError) {
            // Also mark any pending agent message as complete
            updateLastAgentMessage { msg -> msg.copy(state = AgentMessageState.Complete) }
        }

        private fun handleSupplement(event: AgentEvent.SupplementReceived) {
            // Show supplement as a user message in chat history
            _messages.add(
                    ChatMessage.User(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            text = event.text
                    )
            )
        }

        /** Helper: update the last agent message in the list. */
        private inline fun updateLastAgentMessage(
                transform: (ChatMessage.Agent) -> ChatMessage.Agent
        ) {
            val index = _messages.indexOfLast { it is ChatMessage.Agent }
            if (index >= 0) {
                val current = _messages[index] as ChatMessage.Agent
                _messages[index] = transform(current)
            }
        }
    }

    /** Restore chat UI from a recorder snapshot owned by an active session. */
    fun restoreMessagesFromRecords(records: List<MessageRecord>) {
        val restoredMessages = MessageConverter.fromRecords(records)
        _messages.clear()
        streamingBuffer.clear()
        currentAgentMessageId = null
        _messages.addAll(restoredMessages)
        _uiState.update { it.copy(showEmptyState = _messages.isEmpty()) }
    }

    /**
     * Send a user message.
     *
     * If no session exists, calls onSessionNeeded callback to create one.
     */
    fun sendMessage(text: String) {
        val session = sessionProvider()
        if (session != null) {
            viewModelScope.launch { session.submit(Op.UserInput(text)) }
        } else {
            // No session - request one to be created
            onSessionNeeded?.invoke(text)
        }
    }

    /** Stop the current task. */
    fun stopTask() {
        val session = sessionProvider()
        if (session != null) {
            viewModelScope.launch { session.submit(Op.Interrupt) }
        }
    }

    // ===== Smart Capsule Actions =====

    /** Send a supplement message during an active task. */
    fun sendSupplement(text: String) {
        val session = sessionProvider() ?: return
        viewModelScope.launch { session.submit(Op.Supplement(text)) }
    }

    /** Request takeover (user takes control of device). */
    fun requestTakeover() {
        val session = sessionProvider() ?: return
        viewModelScope.launch { session.submit(Op.Takeover) }
    }

    /** Resume from takeover (return control to agent). */
    fun requestResume() {
        val session = sessionProvider() ?: return
        viewModelScope.launch { session.submit(Op.Resume) }
    }

    /** Send user response to an ask_user request. */
    fun sendUserResponse(callId: String, response: String) {
        val session = sessionProvider() ?: return
        viewModelScope.launch { session.submit(Op.UserResponse(callId, response)) }
    }

    fun dismissError() {
        AgentService.instance?.capsuleStateHolder?.onDismissError()
    }

    /** Clear conversation history. */
    fun clearConversation() {
        sessionHistoryController.clearConversation()
    }

    // ===== Session History Methods =====

    /** Load the list of saved sessions. */
    fun loadSessions() {
        sessionHistoryController.loadSessions()
    }

    /**
     * Resume a previously saved session.
     *
     * This clears current messages and restores the session's messages.
     *
     * @param sessionInfo The session to resume
     * @param onResumed Callback when session is ready (for UI to reconnect event collection)
     */
    fun resumeSession(sessionInfo: SessionInfo, onResumed: (() -> Unit)? = null) {
        sessionHistoryController.resumeSession(sessionInfo, onResumed)
    }

    /**
     * Start a new session, clearing current conversation.
     *
     * @param model The model being used
     * @param appVersion The app version
     */
    fun startNewSession(model: String? = null, appVersion: String? = null) {
        sessionHistoryController.startNewSession(model, appVersion)
    }

    /**
     * Delete a saved session.
     *
     * @param sessionInfo The session to delete
     */
    fun deleteSession(sessionInfo: SessionInfo) {
        sessionHistoryController.deleteSession(sessionInfo)
    }

    /** Check if session history is available. */
    fun hasSessionHistory(): Boolean = sessionHistoryController.hasSessionHistory()

    override fun onCleared() {
        super.onCleared()
        eventCollectionJob?.cancel()
    }
}

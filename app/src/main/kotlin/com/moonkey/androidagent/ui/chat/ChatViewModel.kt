package com.moonkey.androidagent.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.model.MessageConverter
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.chat.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ChatViewModel - Manages chat state and event collection.
 * 
 * Responsibilities:
 * - Collect events from AgentSession.events
 * - Maintain message list with mutableStateListOf<ChatMessage>()
 * - Handle streaming text accumulation
 * - Manage input state (Idle/Working)
 * - Manage task banner state
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
        
        /** Delay before hiding completed banner */
        private const val BANNER_FADE_DELAY_MS = 2000L
    }
    
    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Messages (Compose observable list)
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages
    
    // Task banner state
    private val _taskBannerState = MutableStateFlow<TaskBannerState>(TaskBannerState.Idle)
    val taskBannerState: StateFlow<TaskBannerState> = _taskBannerState.asStateFlow()
    
    // Session list state (for session history UI)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()
    
    // Streaming accumulator
    private val streamingBuffer = StringBuilder()
    private var currentAgentMessageId: String? = null
    
    // Active event collection job
    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    
    /**
     * Start collecting events from a session.
     */
    fun startEventCollection(session: AgentSession) {
        // Cancel any existing collection
        eventCollectionJob?.cancel()
        
        eventCollectionJob = viewModelScope.launch {
            session.events.collect { event ->
                handleEvent(event)
            }
        }
    }
    
    /**
     * Handle incoming AgentEvent.
     */
    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TaskStarted -> handleTaskStarted(event)
            is AgentEvent.TurnStarted -> handleTurnStarted(event)
            is AgentEvent.TurnPhaseChanged -> handlePhaseChanged(event)
            is AgentEvent.MessageDelta -> handleMessageDelta(event)
            is AgentEvent.ActionProposed -> handleActionProposed(event)
            is AgentEvent.ActionExecuted -> handleActionExecuted(event)
            is AgentEvent.ActionSkipped -> handleActionSkipped(event)
            is AgentEvent.TaskCompleted -> handleTaskCompleted(event)
            is AgentEvent.SessionError -> handleError(event)
            else -> { /* Ignore other events */ }
        }
    }
    
    // ===== Recording Service Access =====
    
    private val recordingService get() = sessionHistoryManager?.getRecordingService()
    
    private fun handleTaskStarted(event: AgentEvent.TaskStarted) {
        // Update UI state
        _uiState.update { it.copy(inputState = InputState.Working, showEmptyState = false) }
        
        // Update banner
        _taskBannerState.value = TaskBannerState.Working(
            taskTitle = event.input.take(50)
        )
        
        // Initialize session recording if not already active
        if (recordingService?.hasActiveSession() != true) {
            recordingService?.initializeNewSession()
        }
        
        val userMsgId = UUID.randomUUID().toString()
        
        // Record user message to history
        recordingService?.recordUserMessage(userMsgId, event.timestamp, event.input)
        
        // Add user message to UI
        _messages.add(ChatMessage.User(
            id = userMsgId,
            timestamp = event.timestamp,
            text = event.input
        ))
        
        // Record agent message start
        recordingService?.startAgentMessage(event.taskId, event.timestamp)
        
        // Prepare agent message placeholder with empty content blocks
        streamingBuffer.clear()
        currentAgentMessageId = event.taskId
        _messages.add(ChatMessage.Agent(
            id = event.taskId,
            timestamp = event.timestamp,
            contentBlocks = emptyList(),
            state = AgentMessageState.Thinking
        ))
    }
    
    private fun handleTurnStarted(event: AgentEvent.TurnStarted) {
        // Clear streaming buffer at turn start to properly segment text between turns
        // This prevents text from previous turns accumulating with new turn text
        android.util.Log.d(TAG, "TurnStarted: turn=${event.turnNumber}, clearing buffer")
        streamingBuffer.clear()
    }
    
    private fun handlePhaseChanged(event: AgentEvent.TurnPhaseChanged) {
        val current = _taskBannerState.value
        if (current is TaskBannerState.Working) {
            _taskBannerState.value = current.copy(phase = event.phase.name)
        }
    }
    
    private fun handleMessageDelta(event: AgentEvent.MessageDelta) {
        android.util.Log.d(TAG, "MessageDelta received: turnId=${event.turnId}, delta=${event.delta.take(30)}...")
        streamingBuffer.append(event.delta)
        
        // Record text delta to history
        recordingService?.appendTextDelta(event.delta)
        
        updateLastAgentMessage { msg ->
            val updatedBlocks = updateOrAppendTextBlock(msg.contentBlocks, streamingBuffer.toString())
            msg.copy(
                contentBlocks = updatedBlocks,
                state = AgentMessageState.Streaming
            )
        }
    }
    
    /**
     * Update the last Text block or append a new one if needed.
     * This maintains proper interleaving: if the last block is an Action, we start a new Text block.
     */
    private fun updateOrAppendTextBlock(blocks: List<ContentBlock>, text: String): List<ContentBlock> {
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
        val newAction = ActionCardData(
            id = event.actionId,
            toolName = formatToolName(event.toolName),
            toolIcon = getToolIcon(event.toolName),
            description = event.description,
            state = ActionState.Proposed,
            resultSummary = null
        )
        
        // Record action to history
        recordingService?.recordAction(
            actionId = event.actionId,
            toolName = event.toolName,
            description = event.description,
            state = "proposed"
        )
        
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
        
        // Update action state in history
        recordingService?.updateActionState(event.actionId, stateString, event.result)
        
        updateLastAgentMessage { msg ->
            // Find existing action block and update it, or add new one
            val existingBlockIndex = msg.contentBlocks.indexOfFirst { block ->
                block is ContentBlock.Action && block.data.id == event.actionId
            }
            
            val updatedBlocks = if (existingBlockIndex >= 0) {
                // Update existing action block
                msg.contentBlocks.mapIndexed { index, block ->
                    if (index == existingBlockIndex && block is ContentBlock.Action) {
                        ContentBlock.Action(block.data.copy(
                            state = newState,
                            resultSummary = event.result
                        ))
                    } else block
                }
            } else {
                // Action wasn't proposed first - add it directly
                // Record action to history first
                recordingService?.recordAction(
                    actionId = event.actionId,
                    toolName = event.toolName,
                    description = event.result ?: event.toolName,
                    state = stateString
                )
                
                // Clear streaming buffer first since this is a new action
                streamingBuffer.clear()
                
                val newAction = ActionCardData(
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
    
    private fun handleActionSkipped(event: AgentEvent.ActionSkipped) {
        // Update action state in history
        recordingService?.updateActionState(event.actionId, "skipped", event.reason)
        
        updateLastAgentMessage { msg ->
            // Find existing action block and update it
            val existingBlockIndex = msg.contentBlocks.indexOfFirst { block ->
                block is ContentBlock.Action && block.data.id == event.actionId
            }
            
            if (existingBlockIndex >= 0) {
                val updatedBlocks = msg.contentBlocks.mapIndexed { index, block ->
                    if (index == existingBlockIndex && block is ContentBlock.Action) {
                        ContentBlock.Action(block.data.copy(
                            state = ActionState.Skipped,
                            resultSummary = event.reason
                        ))
                    } else block
                }
                msg.copy(contentBlocks = updatedBlocks)
            } else msg
        }
    }
    
    private fun handleTaskCompleted(event: AgentEvent.TaskCompleted) {
        // Update UI state
        _uiState.update { it.copy(inputState = InputState.Idle) }
        
        // Update banner
        _taskBannerState.value = TaskBannerState.Completed(
            summary = event.result ?: "Task complete"
        )
        
        // Complete agent message in history
        recordingService?.completeAgentMessage()
        
        // Mark agent message as complete
        updateLastAgentMessage { msg ->
            msg.copy(state = AgentMessageState.Complete)
        }
        
        // Reset streaming state
        streamingBuffer.clear()
        currentAgentMessageId = null
        
        // Notify that task is completed (for session cleanup)
        onTaskCompleted?.invoke()
        
        // Auto-hide banner after delay
        viewModelScope.launch {
            delay(BANNER_FADE_DELAY_MS)
            if (_taskBannerState.value is TaskBannerState.Completed) {
                _taskBannerState.value = TaskBannerState.Idle
            }
        }
    }
    
    private fun handleError(event: AgentEvent.SessionError) {
        _uiState.update { it.copy(inputState = InputState.Idle) }
        _taskBannerState.value = TaskBannerState.Error(event.error.message)
        
        // Also mark any pending agent message as complete
        updateLastAgentMessage { msg ->
            msg.copy(state = AgentMessageState.Complete)
        }
    }
    
    /**
     * Helper: update the last agent message in the list.
     */
    private inline fun updateLastAgentMessage(transform: (ChatMessage.Agent) -> ChatMessage.Agent) {
        val index = _messages.indexOfLast { it is ChatMessage.Agent }
        if (index >= 0) {
            val current = _messages[index] as ChatMessage.Agent
            _messages[index] = transform(current)
        }
    }
    
    /**
     * Format tool name for display (e.g., "click" -> "Click").
     */
    private fun formatToolName(toolName: String): String {
        return toolName.replaceFirstChar { it.uppercase() }
            .replace("_", " ")
    }
    
    /**
     * Map tool names to Material icons.
     */
    private fun getToolIcon(toolName: String): ImageVector = when (toolName.lowercase()) {
        "mobile_action" -> Icons.Rounded.TouchApp
        "app_control" -> Icons.Rounded.Apps
        "complete_task" -> Icons.Rounded.CheckCircle
        "click" -> Icons.Rounded.TouchApp
        "type" -> Icons.Rounded.Keyboard
        "scroll" -> Icons.Rounded.UnfoldMore
        "swipe" -> Icons.Rounded.SwipeVertical
        "back" -> Icons.AutoMirrored.Rounded.ArrowBack
        "home" -> Icons.Rounded.Home
        "wait" -> Icons.Rounded.HourglassEmpty
        "complete_task" -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Build
    }
    
    /**
     * Send a user message.
     * 
     * If no session exists, calls onSessionNeeded callback to create one.
     */
    fun sendMessage(text: String) {
        val session = sessionProvider()
        if (session != null) {
            viewModelScope.launch {
                session.submit(Op.UserInput(text))
            }
        } else {
            // No session - request one to be created
            onSessionNeeded?.invoke(text)
        }
    }
    
    /**
     * Stop the current task.
     */
    fun stopTask() {
        val session = sessionProvider()
        if (session != null) {
            viewModelScope.launch {
                session.submit(Op.Interrupt)
            }
        }
    }
    
    /**
     * Clear conversation history.
     */
    fun clearConversation() {
        _messages.clear()
        streamingBuffer.clear()
        currentAgentMessageId = null
        _uiState.update { it.copy(showEmptyState = true) }
        _taskBannerState.value = TaskBannerState.Idle
    }
    
    // ===== Session History Methods =====
    
    /**
     * Load the list of saved sessions.
     */
    fun loadSessions() {
        val manager = sessionHistoryManager ?: return
        viewModelScope.launch {
            _sessions.value = manager.listSessions()
            android.util.Log.d(TAG, "Loaded ${_sessions.value.size} sessions")
        }
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
        val manager = sessionHistoryManager ?: return
        viewModelScope.launch {
            manager.loadSession(sessionInfo.id)
                .onSuccess { data ->
                    // Clear current messages
                    _messages.clear()
                    streamingBuffer.clear()
                    currentAgentMessageId = null
                    
                    // Restore messages from record
                    val restoredMessages = MessageConverter.fromRecords(data.session.messages)
                    _messages.addAll(restoredMessages)
                    
                    // Update UI state
                    _uiState.update { it.copy(showEmptyState = _messages.isEmpty()) }
                    _taskBannerState.value = TaskBannerState.Idle
                    
                    // Resume the session in recording service
                    manager.resumeSession(data)
                    
                    android.util.Log.i(TAG, "Resumed session ${sessionInfo.id} with ${restoredMessages.size} messages")
                    
                    // Notify caller
                    onResumed?.invoke()
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Failed to resume session", error)
                }
        }
    }
    
    /**
     * Start a new session, clearing current conversation.
     * 
     * @param model The model being used
     * @param appVersion The app version
     */
    fun startNewSession(model: String? = null, appVersion: String? = null) {
        clearConversation()
        sessionHistoryManager?.startNewSession(model, appVersion)
        android.util.Log.d(TAG, "Started new session")
    }
    
    /**
     * Delete a saved session.
     * 
     * @param sessionInfo The session to delete
     */
    fun deleteSession(sessionInfo: SessionInfo) {
        val manager = sessionHistoryManager ?: return
        viewModelScope.launch {
            manager.deleteSession(sessionInfo.id)
                .onSuccess {
                    android.util.Log.d(TAG, "Deleted session ${sessionInfo.id}")
                    loadSessions() // Refresh list
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Failed to delete session", error)
                }
        }
    }
    
    /**
     * Check if session history is available.
     */
    fun hasSessionHistory(): Boolean = sessionHistoryManager != null
    
    override fun onCleared() {
        super.onCleared()
        eventCollectionJob?.cancel()
    }
}

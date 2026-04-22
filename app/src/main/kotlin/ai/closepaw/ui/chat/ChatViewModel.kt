package ai.closepaw.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.closepaw.app.AgentService
import ai.closepaw.history.SessionHistoryManager
import ai.closepaw.history.model.MessageConverter
import ai.closepaw.history.model.MessageRecord
import ai.closepaw.history.model.SessionInfo
import ai.closepaw.protocol.*
import ai.closepaw.session.AgentSession
import ai.closepaw.ui.chat.model.ActionCardData
import ai.closepaw.ui.chat.model.ActionState
import ai.closepaw.ui.chat.model.AgentMessageState
import ai.closepaw.ui.chat.model.ChatMessage
import ai.closepaw.ui.chat.model.ChatUiState
import ai.closepaw.ui.chat.model.ContentBlock
import ai.closepaw.ui.chat.model.RowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun completionSummary(result: String?): String =
        result?.takeIf { it.isNotBlank() } ?: "Task completed"

/** Display name of `complete_task` after [formatToolName] — used to detect the
 *  Turn.kt:205-209 stop signal in the chat trace (the tool's args.answer is
 *  surfaced via [TaskCompleted.result]). */
private val COMPLETE_TASK_DISPLAY = ai.closepaw.ui.common.formatToolName("complete_task")

internal fun shouldHandleReboundEvent(
        eventTimestamp: Long,
        replayCutoffTimestamp: Long?
): Boolean = replayCutoffTimestamp == null || eventTimestamp > replayCutoffTimestamp

/**
 * Apply Turn.kt:205-209 stop criteria to the row at [TaskCompleted] time:
 *  - if a `complete_task` action ran in this row → append [ContentBlock.FinalText]
 *    carrying [rawResult] (which is `arguments.answer` per TurnToolPolicy.kt:103);
 *  - else if the most recent block is a streaming [ContentBlock.Text] → promote
 *    it in place to [ContentBlock.FinalText] (the "last text without tools" path);
 *  - else → append a [ContentBlock.FinalText] with the resolved completion text.
 *  Error rows still get a Text block prefixed with ⚠️ and never produce a final.
 */
internal fun appendCompletionToMessages(
        messages: MutableList<ChatMessage>,
        rawResult: String?,
        timestamp: Long,
        taskId: String,
        isError: Boolean = false
) {
    val terminalRowState = if (isError) RowState.Error else RowState.Complete
    val index = messages.indexOfLast { it is ChatMessage.Agent }
    if (index >= 0) {
        val current = messages[index] as ChatMessage.Agent
        val newBlocks = applyCompletionToBlocks(current.contentBlocks, rawResult, isError)
        messages[index] =
                current.copy(
                        contentBlocks = newBlocks,
                        state = AgentMessageState.Complete,
                        rowState = if (current.rowState == RowState.Error || isError) RowState.Error else RowState.Complete,
                        completedTimestamp = timestamp
                )
        return
    }

    val seedBlocks = applyCompletionToBlocks(emptyList(), rawResult, isError)
    messages.add(
            ChatMessage.Agent(
                    id = taskId,
                    timestamp = timestamp,
                    contentBlocks = seedBlocks,
                    state = AgentMessageState.Complete,
                    rowState = terminalRowState,
                    completedTimestamp = timestamp
            )
    )
}

private fun applyCompletionToBlocks(
        blocks: List<ContentBlock>,
        rawResult: String?,
        isError: Boolean
): List<ContentBlock> {
    if (isError) {
        return blocks + ContentBlock.Text("⚠️ ${completionSummary(rawResult)}")
    }
    val realAnswer = rawResult?.trim()?.takeIf { it.isNotBlank() }
    val hasCompleteTask = blocks.any { block ->
        block is ContentBlock.Action && block.data.toolName == COMPLETE_TASK_DISPLAY
    }
    if (hasCompleteTask) {
        // complete_task path: only emit FinalText when the answer is real.
        // Missing/empty answer ⇒ no final region (per uxfb-3 README §3).
        return if (realAnswer != null) blocks + ContentBlock.FinalText(realAnswer) else blocks
    }
    // Last-text-without-tools path (Turn.kt:205-209 second branch): promote the
    // most recent non-blank Text in place. Otherwise fall back to rawResult only
    // if it's a real answer — never fabricate "Task completed" for USER_STOPPED
    // / side-effect-only completions where rawResult is null.
    val lastTextIndex = blocks.indexOfLast {
        it is ContentBlock.Text && it.text.isNotBlank()
    }
    if (lastTextIndex >= 0) {
        val existing = (blocks[lastTextIndex] as ContentBlock.Text).text
        return blocks.mapIndexed { i, b ->
            if (i == lastTextIndex) ContentBlock.FinalText(existing) else b
        }
    }
    if (realAnswer != null) {
        return blocks + ContentBlock.FinalText(realAnswer)
    }
    return blocks
}

internal fun updateActionBlockForExecution(
        blocks: List<ContentBlock>,
        actionId: String,
        newState: ActionState,
        resultSummary: String?
): Pair<List<ContentBlock>, Boolean> {
    val existingBlockIndex =
            blocks.indexOfLast { block ->
                block is ContentBlock.Action && block.data.id == actionId
            }
    if (existingBlockIndex < 0) return blocks to false

    val updated =
            blocks.mapIndexed { index, block ->
                if (index == existingBlockIndex && block is ContentBlock.Action) {
                    ContentBlock.Action(
                            block.data.copy(
                                    state = newState,
                                    resultSummary = resultSummary
                            )
                    )
                } else {
                    block
                }
            }
    return updated to true
}

internal fun appendStartupFailureMessages(
        messages: MutableList<ChatMessage>,
        inputText: String,
        errorMessage: String,
        timestamp: Long
) {
    messages.add(
            ChatMessage.User(
                    id = "startup-user-$timestamp",
                    timestamp = timestamp,
                    text = inputText
            )
    )
    messages.add(
            ChatMessage.Agent(
                    id = "startup-error-$timestamp",
                    timestamp = timestamp,
                    contentBlocks =
                            listOf(ContentBlock.Text("⚠️ Failed to start: $errorMessage")),
                    state = AgentMessageState.Complete,
                    rowState = RowState.Error,
                    completedTimestamp = timestamp
            )
    )
}

/**
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
        private val onSessionNeeded: ((String) -> Unit)? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Preserved user input from a failed session bootstrap — re-populates the
    // compose box so the user can retry without retyping.
    private val _pendingInput = MutableStateFlow("")
    val pendingInput: StateFlow<String> = _pendingInput.asStateFlow()

    // Startup error message; null when no active error. UI shows retry affordance.
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    // Deep-link target when the startup-failure banner is tapped. Null when no error.
    private val _startupErrorDeepLink = MutableStateFlow<SettingsDeepLink?>(null)
    val startupErrorDeepLink: StateFlow<SettingsDeepLink?> = _startupErrorDeepLink.asStateFlow()

    // Messages (Compose observable list)
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage>
        get() = _messages

    // Streaming accumulator
    private val streamingBuffer = StringBuilder()
    private val chatStateLock = Any()
    private var currentAgentMessageId: String? = null

    // Active event collection job
    private var eventCollectionJob: kotlinx.coroutines.Job? = null

    private val eventReducer =
            ChatEventReducer(
                    uiState = _uiState,
                    messages = _messages,
                    streamingBuffer = streamingBuffer,
                    stateLock = chatStateLock,
                    setCurrentAgentMessageId = { currentAgentMessageId = it }
            )
    private val sessionHistoryController =
            ChatSessionHistoryController(
                    scope = viewModelScope,
                    sessionHistoryManager = sessionHistoryManager,
                    messages = _messages,
                    streamingBuffer = streamingBuffer,
                    stateLock = chatStateLock,
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
                        // Yield to the main looper after streaming deltas so Compose
                        // can recompose between frames (renders incremental text).
                        // Without this, rapid deltas batch in the SharedFlow buffer
                        // and the collector processes them all in one frame.
                        if (event is MessageDelta) {
                            kotlinx.coroutines.delay(1)
                        }
                    }
                }
    }

    /** Handle incoming agent event. */
    private fun handleEvent(event: AgentEvent) {
        eventReducer.handle(event)
    }

    /** Restore chat UI from a recorder snapshot owned by an active session. */
    fun restoreMessagesFromRecords(records: List<MessageRecord>) {
        val restoredMessages = MessageConverter.fromRecords(records)
        synchronized(chatStateLock) {
            _messages.clear()
            streamingBuffer.clear()
            currentAgentMessageId = null
            _messages.addAll(restoredMessages)
            _uiState.update { it.copy(showEmptyState = _messages.isEmpty()) }
        }
    }

    /**
     * Send a user message.
     *
     * If no session exists, calls onSessionNeeded callback to create one.
     */
    fun sendMessage(text: String) {
        val session = sessionProvider()
        if (session != null && session.state.value != SessionState.Shutdown) {
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

    /** Respond to a PolicyEngine approval request (approve/deny). */
    fun sendApprovalResponse(
        callId: String,
        decision: ApprovalDecision,
        scope: ApprovalScope,
        packageName: String?
    ) {
        val session = sessionProvider() ?: return
        viewModelScope.launch {
            session.submit(Op.Approve(callId, decision, scope, packageName))
        }
    }

    fun dismissError() {
        AgentService.instance?.dismissError()
    }

    /**
     * Surface a bootstrap failure (session creation threw before any events
     * reached the chat). Preserves the user's input so they can retry, and
     * appends a visible error message to the chat history.
     */
    fun reportStartupFailure(
        inputText: String,
        errorMessage: String,
        deepLink: SettingsDeepLink? = null,
    ) {
        synchronized(chatStateLock) {
            appendStartupFailureMessages(
                    messages = _messages,
                    inputText = inputText,
                    errorMessage = errorMessage,
                    timestamp = System.currentTimeMillis()
            )
            _uiState.update { it.copy(showEmptyState = false) }
        }
        _pendingInput.value = inputText
        _startupError.value = errorMessage
        _startupErrorDeepLink.value = deepLink
    }

    /** Consume preserved input (e.g. after the compose box has been re-populated). */
    fun consumePendingInput(): String {
        val value = _pendingInput.value
        _pendingInput.value = ""
        return value
    }

    /** Dismiss the startup-error banner without clearing pending input. */
    fun dismissStartupError() {
        _startupError.value = null
        _startupErrorDeepLink.value = null
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
    fun resumeSession(sessionInfo: SessionInfo, onResumed: (suspend () -> Unit)? = null) {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
        sessionHistoryController.resumeSession(sessionInfo, onResumed)
    }

    /**
     * Start a new session, clearing current conversation.
     *
     * @param model The model being used
     * @param appVersion The app version
     */
    fun startNewSession(model: String? = null, appVersion: String? = null) {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
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

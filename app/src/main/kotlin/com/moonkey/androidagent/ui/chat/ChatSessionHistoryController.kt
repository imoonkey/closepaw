package com.moonkey.androidagent.ui.chat

import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.model.MessageConverter
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.ui.chat.model.ChatMessage
import com.moonkey.androidagent.ui.chat.model.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatSessionHistoryController(
    private val scope: CoroutineScope,
    private val sessionHistoryManager: SessionHistoryManager?,
    private val messages: SnapshotStateList<ChatMessage>,
    private val streamingBuffer: StringBuilder,
    private val stateLock: Any,
    private val setCurrentAgentMessageId: (String?) -> Unit,
    private val uiState: MutableStateFlow<ChatUiState>,
) {
    companion object {
        private const val TAG = "ChatSessionHistory"
    }

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    fun clearConversation() {
        synchronized(stateLock) {
            messages.clear()
            streamingBuffer.clear()
            setCurrentAgentMessageId(null)
            uiState.update { it.copy(showEmptyState = true) }
        }
    }

    fun loadSessions() {
        val manager = sessionHistoryManager ?: return
        scope.launch {
            _sessions.value = manager.listSessions()
            Log.d(TAG, "Loaded ${_sessions.value.size} sessions")
        }
    }

    fun resumeSession(sessionInfo: SessionInfo, onResumed: (suspend () -> Unit)? = null) {
        val manager = sessionHistoryManager ?: return
        scope.launch {
            manager.loadSession(sessionInfo.id)
                .onSuccess { data ->
                    val restoredMessages = MessageConverter.fromRecords(data.session.messages)
                    synchronized(stateLock) {
                        messages.clear()
                        streamingBuffer.clear()
                        setCurrentAgentMessageId(null)
                        messages.addAll(restoredMessages)
                        uiState.update { it.copy(showEmptyState = messages.isEmpty()) }
                    }

                    manager.resumeSession(data)

                    Log.i(TAG, "Resumed session ${sessionInfo.id} with ${restoredMessages.size} messages")
                    onResumed?.invoke()
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to resume session", error)
                }
        }
    }

    fun startNewSession(model: String? = null, appVersion: String? = null) {
        clearConversation()
        sessionHistoryManager?.startNewSession(model, appVersion)
        Log.d(TAG, "Started new session")
    }

    fun deleteSession(sessionInfo: SessionInfo) {
        val manager = sessionHistoryManager ?: return
        scope.launch {
            manager.deleteSession(sessionInfo.id)
                .onSuccess {
                    Log.d(TAG, "Deleted session ${sessionInfo.id}")
                    loadSessions()
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to delete session", error)
                }
        }
    }

    fun hasSessionHistory(): Boolean = sessionHistoryManager != null
}

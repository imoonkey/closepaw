package com.moonkey.androidagent.history

import android.util.Log
import com.moonkey.androidagent.history.model.ContentBlockRecord
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionMetadata
import com.moonkey.androidagent.history.model.SessionRecord
import com.moonkey.androidagent.history.storage.SessionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Records chat events to a session file in real-time.
 * 
 * Usage:
 * 1. Call initializeNewSession() to start a new session, or resumeSession() to resume an existing one
 * 2. Call record*() methods as events occur
 * 3. Session is auto-saved after each significant change (debounced)
 * 
 * This service is the bridge between live AgentEvents and persisted SessionRecords.
 */
class SessionRecordingService(
    private val storage: SessionStorage,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SessionRecordingService"
        
        /** Debounce delay for auto-save (ms) */
        private const val SAVE_DEBOUNCE_MS = 500L
    }
    
    private var currentSession: SessionRecord? = null
    private var currentFileName: String? = null
    
    // Current agent message being built
    private var currentAgentMessageId: String? = null
    private var currentTextBuffer: StringBuilder = StringBuilder()
    private var currentContentBlocks: MutableList<ContentBlockRecord> = mutableListOf()
    
    // Debounced save job
    private var saveJob: Job? = null
    
    /**
     * Initialize a new session.
     * 
     * @param model The LLM model being used (e.g., "gpt-4o")
     * @param appVersion The app version creating this session
     * @return The session ID
     */
    fun initializeNewSession(
        model: String? = null,
        appVersion: String? = null
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        
        currentSession = SessionRecord(
            sessionId = sessionId,
            startTime = now,
            lastUpdated = now,
            messages = emptyList(),
            metadata = SessionMetadata(
                appVersion = appVersion,
                model = model
            )
        )
        currentFileName = storage.generateFileName(sessionId)
        
        // Reset state
        currentAgentMessageId = null
        currentTextBuffer.clear()
        currentContentBlocks.clear()
        
        Log.i(TAG, "Initialized new session: $sessionId, file: $currentFileName")
        scheduleSave()
        
        return sessionId
    }
    
    /**
     * Resume an existing session.
     * 
     * @param data The session data to resume from
     */
    fun resumeSession(data: ResumedSessionData) {
        currentSession = data.session
        currentFileName = data.fileName
        
        // Reset agent message state (we're starting fresh)
        currentAgentMessageId = null
        currentTextBuffer.clear()
        currentContentBlocks.clear()
        
        Log.i(TAG, "Resumed session: ${data.session.sessionId}, file: ${data.fileName}")
    }
    
    /**
     * Record a user message.
     */
    fun recordUserMessage(id: String, timestamp: Long, text: String) {
        val session = currentSession ?: run {
            Log.w(TAG, "No active session for recording user message")
            return
        }
        
        // Finalize any pending agent message first
        finalizeCurrentAgentMessage()
        
        val userMessage = MessageRecord.User(
            id = id,
            timestamp = timestamp,
            text = text
        )
        
        currentSession = session.copy(
            messages = session.messages + userMessage,
            lastUpdated = timestamp
        )
        
        Log.d(TAG, "Recorded user message: ${text.take(30)}...")
        scheduleSave()
    }
    
    /**
     * Start recording an agent message.
     */
    fun startAgentMessage(id: String, timestamp: Long) {
        val session = currentSession ?: run {
            Log.w(TAG, "No active session for starting agent message")
            return
        }
        
        // Finalize any previous agent message
        finalizeCurrentAgentMessage()
        
        currentAgentMessageId = id
        currentTextBuffer.clear()
        currentContentBlocks.clear()
        
        Log.d(TAG, "Started agent message: $id")
    }
    
    /**
     * Append text delta to current agent message.
     */
    fun appendTextDelta(delta: String) {
        if (currentAgentMessageId == null) {
            Log.w(TAG, "No active agent message for text delta")
            return
        }
        
        currentTextBuffer.append(delta)
        // Don't save on every delta - wait for action or completion
    }
    
    /**
     * Finalize current text block (called before adding an action).
     * This ensures text before an action is saved separately.
     */
    private fun finalizeCurrentTextBlock() {
        if (currentTextBuffer.isNotEmpty()) {
            currentContentBlocks.add(ContentBlockRecord.Text(currentTextBuffer.toString()))
            currentTextBuffer.clear()
        }
    }
    
    /**
     * Record an action in current agent message.
     */
    fun recordAction(
        actionId: String,
        toolName: String,
        description: String,
        state: String
    ) {
        if (currentAgentMessageId == null) {
            Log.w(TAG, "No active agent message for action")
            return
        }
        
        // Finalize any text before this action
        finalizeCurrentTextBlock()
        
        val action = ContentBlockRecord.Action(
            id = actionId,
            toolName = toolName,
            description = description,
            state = state,
            resultSummary = null
        )
        
        currentContentBlocks.add(action)
        Log.d(TAG, "Recorded action: $toolName ($state)")
        
        // Save after adding action
        updateAgentMessageInSession()
        scheduleSave()
    }
    
    /**
     * Update an action's state and result.
     */
    fun updateActionState(actionId: String, state: String, result: String?) {
        if (currentAgentMessageId == null) {
            Log.w(TAG, "No active agent message for action update")
            return
        }
        
        // Find and update the action in current content blocks
        val updatedBlocks = currentContentBlocks.map { block ->
            if (block is ContentBlockRecord.Action && block.id == actionId) {
                block.copy(state = state, resultSummary = result)
            } else {
                block
            }
        }
        currentContentBlocks.clear()
        currentContentBlocks.addAll(updatedBlocks)
        
        Log.d(TAG, "Updated action $actionId state to $state")
        
        updateAgentMessageInSession()
        scheduleSave()
    }
    
    /**
     * Mark current agent message as complete.
     */
    fun completeAgentMessage() {
        finalizeCurrentAgentMessage()
        Log.d(TAG, "Completed agent message")
        scheduleSave()
    }
    
    /**
     * Mark session as completed normally.
     */
    fun completeSession() {
        val session = currentSession ?: return
        
        // Finalize any pending agent message
        finalizeCurrentAgentMessage()
        
        // Extract summary from first user message if not set
        val summary = session.summary ?: session.messages
            .filterIsInstance<MessageRecord.User>()
            .firstOrNull()?.text?.take(50)?.let { 
                if (it.length < 50) it else "$it..." 
            }
        
        currentSession = session.copy(
            lastUpdated = System.currentTimeMillis(),
            summary = summary,
            metadata = session.metadata.copy(
                completedNormally = true,
                turnCount = session.messages.count { it is MessageRecord.Agent }
            )
        )
        
        Log.i(TAG, "Session completed: ${session.sessionId}")
        
        // Force immediate save - cancel debounce but let any in-progress save complete
        val pendingSave = saveJob
        saveJob = null
        scope.launch {
            // Wait for any pending save to complete before doing final save
            pendingSave?.join()
            save()
        }
    }
    
    /**
     * Get the current session record.
     */
    fun getCurrentSession(): SessionRecord? = currentSession
    
    /**
     * Get current file name.
     */
    fun getCurrentFileName(): String? = currentFileName
    
    /**
     * Check if there's an active session.
     */
    fun hasActiveSession(): Boolean = currentSession != null
    
    /**
     * Get current session ID.
     */
    fun getCurrentSessionId(): String? = currentSession?.sessionId
    
    /**
     * Clear session tracking (called when session ends).
     * Waits for any pending save to complete before clearing.
     */
    fun clearSession() {
        // Let any pending save complete before clearing state
        val pendingSave = saveJob
        saveJob = null
        scope.launch {
            pendingSave?.join()
            currentSession = null
            currentFileName = null
            currentAgentMessageId = null
            currentTextBuffer.clear()
            currentContentBlocks.clear()
            Log.d(TAG, "Session tracking cleared")
        }
    }
    
    // ===== Private Helpers =====
    
    /**
     * Finalize the current agent message and add it to the session.
     */
    private fun finalizeCurrentAgentMessage() {
        val msgId = currentAgentMessageId ?: return
        val session = currentSession ?: return
        
        // Finalize any remaining text
        finalizeCurrentTextBlock()
        
        // Only add if we have content
        if (currentContentBlocks.isEmpty()) {
            currentAgentMessageId = null
            return
        }
        
        // Check if message already exists (update) or needs to be added
        val existingIndex = session.messages.indexOfFirst { 
            it is MessageRecord.Agent && it.id == msgId 
        }
        
        val agentMessage = MessageRecord.Agent(
            id = msgId,
            timestamp = System.currentTimeMillis(),
            contentBlocks = currentContentBlocks.toList(),
            isComplete = true
        )
        
        currentSession = if (existingIndex >= 0) {
            // Update existing
            session.copy(
                messages = session.messages.mapIndexed { index, msg ->
                    if (index == existingIndex) agentMessage else msg
                },
                lastUpdated = System.currentTimeMillis()
            )
        } else {
            // Add new
            session.copy(
                messages = session.messages + agentMessage,
                lastUpdated = System.currentTimeMillis()
            )
        }
        
        // Reset state
        currentAgentMessageId = null
        currentContentBlocks.clear()
    }
    
    /**
     * Update the current agent message in the session without finalizing.
     * Used for incremental updates (action state changes).
     */
    private fun updateAgentMessageInSession() {
        val msgId = currentAgentMessageId ?: return
        val session = currentSession ?: return
        
        // Build blocks including current text buffer
        val blocks = currentContentBlocks.toMutableList()
        if (currentTextBuffer.isNotEmpty()) {
            blocks.add(ContentBlockRecord.Text(currentTextBuffer.toString()))
        }
        
        if (blocks.isEmpty()) return
        
        val agentMessage = MessageRecord.Agent(
            id = msgId,
            timestamp = System.currentTimeMillis(),
            contentBlocks = blocks,
            isComplete = false
        )
        
        // Check if message already exists
        val existingIndex = session.messages.indexOfFirst { 
            it is MessageRecord.Agent && it.id == msgId 
        }
        
        currentSession = if (existingIndex >= 0) {
            session.copy(
                messages = session.messages.mapIndexed { index, msg ->
                    if (index == existingIndex) agentMessage else msg
                },
                lastUpdated = System.currentTimeMillis()
            )
        } else {
            session.copy(
                messages = session.messages + agentMessage,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Schedule a debounced save.
     */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }
    
    /**
     * Save current state to disk.
     */
    private suspend fun save() {
        val session = currentSession ?: return
        val fileName = currentFileName ?: return
        
        storage.writeSession(fileName, session).onFailure { e ->
            Log.e(TAG, "Failed to save session", e)
        }
    }
}

/**
 * Data class for resuming a session.
 */
data class ResumedSessionData(
    val session: SessionRecord,
    val fileName: String
)

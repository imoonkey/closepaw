package com.moonkey.androidagent.history

import android.util.Log
import com.moonkey.androidagent.history.model.ContentBlockRecord
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.ScreenStateRecord
import com.moonkey.androidagent.history.model.SessionMetadata
import com.moonkey.androidagent.history.model.SessionRecord
import com.moonkey.androidagent.history.storage.SessionStorage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Records chat events to a session file in real-time.
 *
 * Usage:
 * 1. Call initializeNewSession() to start a new session, or resumeSession() to resume an existing
 * one
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
    private val agentMessageBuffer = AgentMessageBuffer()

    // Debounced save job
    private var saveJob: Job? = null

    /**
     * Initialize a new session.
     *
     * @param model The LLM model being used (e.g., "gpt-5.2")
     * @param appVersion The app version creating this session
     * @return The session ID
     */
    fun initializeNewSession(
            sessionId: String? = null,
            model: String? = null,
            appVersion: String? = null
    ): String {
        val finalSessionId = sessionId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        currentSession =
                SessionRecord(
                        sessionId = finalSessionId,
                        startTime = now,
                        lastUpdated = now,
                        messages = emptyList(),
                        metadata = SessionMetadata(appVersion = appVersion, model = model)
                )
        currentFileName = storage.generateFileName(finalSessionId)

        // Reset state
        agentMessageBuffer.clear()

        Log.i(TAG, "Initialized new session: $finalSessionId, file: $currentFileName")
        scheduleSave()

        return finalSessionId
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
        agentMessageBuffer.clear()

        Log.i(TAG, "Resumed session: ${data.session.sessionId}, file: ${data.fileName}")
    }

    /** Record a user message. */
    fun recordUserMessage(id: String, timestamp: Long, text: String) {
        val session =
                currentSession
                        ?: run {
                            Log.w(TAG, "No active session for recording user message")
                            return
                        }

        // Finalize any pending agent message first
        finalizeCurrentAgentMessage()

        val userMessage = MessageRecord.User(id = id, timestamp = timestamp, text = text)

        currentSession =
                session.copy(messages = session.messages + userMessage, lastUpdated = timestamp)

        Log.d(TAG, "Recorded user message: ${text.take(30)}...")
        scheduleSave()
    }

    /** Start recording an agent message. */
    fun startAgentMessage(id: String, timestamp: Long) {
        val session =
                currentSession
                        ?: run {
                            Log.w(TAG, "No active session for starting agent message")
                            return
                        }

        // Finalize any previous agent message
        finalizeCurrentAgentMessage()

        agentMessageBuffer.start(id)

        Log.d(TAG, "Started agent message: $id")
    }

    /** Append text delta to current agent message. */
    fun appendTextDelta(delta: String) {
        if (!agentMessageBuffer.hasActiveMessage()) {
            Log.w(TAG, "No active agent message for text delta")
            return
        }
        agentMessageBuffer.appendText(delta)
        // Don't save on every delta - wait for action or completion
    }

    /** Record an action in current agent message. */
    fun recordAction(actionId: String, toolName: String, description: String, state: String) {
        if (!agentMessageBuffer.hasActiveMessage()) {
            Log.w(TAG, "No active agent message for action")
            return
        }

        val action =
                ContentBlockRecord.Action(
                        id = actionId,
                        toolName = toolName,
                        description = description,
                        state = state,
                        resultSummary = null
                )
        agentMessageBuffer.recordAction(action)
        Log.d(TAG, "Recorded action: $toolName ($state)")

        // Save after adding action
        updateAgentMessageInSession()
        scheduleSave()
    }

    /** Update an action's state and result. */
    fun updateActionState(actionId: String, state: String, result: String?) {
        if (!agentMessageBuffer.hasActiveMessage()) {
            Log.w(TAG, "No active agent message for action update")
            return
        }
        agentMessageBuffer.updateActionState(actionId, state, result)

        Log.d(TAG, "Updated action $actionId state to $state")

        updateAgentMessageInSession()
        scheduleSave()
    }

    /** Mark current agent message as complete. */
    fun completeAgentMessage() {
        finalizeCurrentAgentMessage()
        Log.d(TAG, "Completed agent message")
        scheduleSave()
    }

    /** Mark session as completed normally. */
    fun completeSession() {
        val session = currentSession ?: return

        // Finalize any pending agent message
        finalizeCurrentAgentMessage()

        // Extract summary from first user message if not set
        val summary =
                session.summary
                        ?: session.messages
                                .filterIsInstance<MessageRecord.User>()
                                .firstOrNull()
                                ?.text
                                ?.take(50)
                                ?.let { if (it.length < 50) it else "$it..." }

        currentSession =
                session.copy(
                        lastUpdated = System.currentTimeMillis(),
                        summary = summary,
                        metadata =
                                session.metadata.copy(
                                        completedNormally = true,
                                        turnCount =
                                                session.messages.count { it is MessageRecord.Agent }
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

    /** Get the current session record. */
    fun getCurrentSession(): SessionRecord? = currentSession

    /** Get current file name. */
    fun getCurrentFileName(): String? = currentFileName

    /** Check if there's an active session. */
    fun hasActiveSession(): Boolean = currentSession != null

    /** Get current session ID. */
    fun getCurrentSessionId(): String? = currentSession?.sessionId

    /**
     * Clear session tracking (called when session ends). Waits for any pending save to complete
     * before clearing.
     */
    fun clearSession() {
        // Let any pending save complete before clearing state
        val pendingSave = saveJob
        saveJob = null
        scope.launch {
            pendingSave?.join()
            currentSession = null
            currentFileName = null
            agentMessageBuffer.clear()
            Log.d(TAG, "Session tracking cleared")
        }
    }

    // ===== Private Helpers =====

    /** Finalize the current agent message and add it to the session. */
    private fun finalizeCurrentAgentMessage() {
        val snapshot = agentMessageBuffer.finalizeSnapshot() ?: return
        val session = currentSession ?: return

        val existingIndex =
                session.messages.indexOfFirst { it is MessageRecord.Agent && it.id == snapshot.id }

        val agentMessage =
                MessageRecord.Agent(
                        id = snapshot.id,
                        timestamp = System.currentTimeMillis(),
                        contentBlocks = snapshot.blocks,
                        isComplete = true
                )

        currentSession =
                if (existingIndex >= 0) {
                    // Update existing
                    session.copy(
                            messages =
                                    session.messages.mapIndexed { index, msg ->
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
    }

    /**
     * Update the current agent message in the session without finalizing. Used for incremental
     * updates (action state changes).
     */
    private fun updateAgentMessageInSession() {
        val snapshot = agentMessageBuffer.buildPartialSnapshot() ?: return
        val session = currentSession ?: return

        val agentMessage =
                MessageRecord.Agent(
                        id = snapshot.id,
                        timestamp = System.currentTimeMillis(),
                        contentBlocks = snapshot.blocks,
                        isComplete = false
                )

        // Check if message already exists
        val existingIndex =
                session.messages.indexOfFirst { it is MessageRecord.Agent && it.id == snapshot.id }

        currentSession =
                if (existingIndex >= 0) {
                    session.copy(
                            messages =
                                    session.messages.mapIndexed { index, msg ->
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

    /** Schedule a debounced save. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
                scope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    save()
                }
    }

    /** Record a screen state reference for replay/debug. */
    fun recordScreenState(state: ScreenStateRecord) {
        val session =
                currentSession
                        ?: run {
                            Log.w(TAG, "No active session for recording screen state")
                            return
                        }
        val normalizedState = normalizeScreenStateRecord(state)
        val hasArtifactPath =
                normalizedState.rawA11yTreePath != null ||
                        normalizedState.sanitizedA11yTreePath != null ||
                        normalizedState.screenshotPath != null
        if (!hasArtifactPath && !normalizedState.traceRunId.isNullOrBlank()) {
            Log.w(
                    TAG,
                    "Screen state recorded without artifact paths: turn=${normalizedState.turnNumber}, phase=${normalizedState.phase}"
            )
        }

        val updatedMetadata =
                if (!normalizedState.traceRunId.isNullOrBlank() &&
                                session.metadata.traceRunId.isNullOrBlank()
                ) {
                    session.metadata.copy(traceRunId = normalizedState.traceRunId)
                } else {
                    session.metadata
                }

        currentSession =
                session.copy(
                        screenStates = session.screenStates + normalizedState,
                        lastUpdated = normalizedState.timestamp,
                        metadata = updatedMetadata
                )

        Log.d(
                TAG,
                "Recorded screen state: turn=${normalizedState.turnNumber}, phase=${normalizedState.phase}"
        )
        scheduleSave()
    }

    private fun normalizeScreenStateRecord(state: ScreenStateRecord): ScreenStateRecord {
        return state.copy(
                rawA11yTreePath = normalizePath(state.rawA11yTreePath),
                sanitizedA11yTreePath = normalizePath(state.sanitizedA11yTreePath),
                screenshotPath = normalizePath(state.screenshotPath),
                traceRunId = state.traceRunId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun normalizePath(path: String?): String? {
        return path?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Save current state to disk. */
    private suspend fun save() {
        val session = currentSession ?: return
        val fileName = currentFileName ?: return

        storage.writeSession(fileName, session).onFailure { e ->
            Log.e(TAG, "Failed to save session", e)
        }
    }
}

/** Data class for resuming a session. */
data class ResumedSessionData(val session: SessionRecord, val fileName: String)

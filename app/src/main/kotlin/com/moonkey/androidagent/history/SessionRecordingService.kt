package com.moonkey.androidagent.history

import android.util.Log
import com.moonkey.androidagent.history.model.ContentBlockRecord
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.ScreenStateRecord
import com.moonkey.androidagent.history.model.SessionMetadata
import com.moonkey.androidagent.history.model.SessionRecord
import com.moonkey.androidagent.history.model.SessionRuntimeSnapshot
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.protocol.TaskOutcome
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var lastTaskOutcome: TaskOutcome? = null
    private val stateLock = Any()

    private val agentMessageBuffer = AgentMessageBuffer()

    private var saveJob: Job? = null

    /** Mutex serializing ALL disk writes (session + checkpoint). */
    private val writeMutex = Mutex()

    /** Monotonically increasing revision for session disk writes. */
    private val sessionRevision = AtomicLong(0)

    /** Monotonically increasing revision for checkpoint disk writes. */
    private val checkpointRevision = AtomicLong(0)

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

        synchronized(stateLock) {
            currentSession =
                    SessionRecord(
                            sessionId = finalSessionId,
                            startTime = now,
                            lastUpdated = now,
                            messages = emptyList(),
                            metadata = SessionMetadata(appVersion = appVersion, model = model)
                    )
            currentFileName = storage.generateFileName(finalSessionId)
            contextFileName = currentFileName?.let { storage.contextFileNameFor(it) }
            // Reset state
            agentMessageBuffer.clear()
            lastTaskOutcome = null
            saveJob?.cancel()
            saveJob = null
            checkpointSaveJob?.cancel()
            checkpointSaveJob = null
        }

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
        synchronized(stateLock) {
            currentSession = data.session
            currentFileName = data.fileName
            contextFileName = storage.contextFileNameFor(data.fileName)
            // Reset agent message state (we're starting fresh)
            agentMessageBuffer.clear()
            checkpointSaveJob?.cancel()
            checkpointSaveJob = null
        }

        Log.i(TAG, "Resumed session: ${data.session.sessionId}, file: ${data.fileName}")
    }

    /** Record a user message. */
    fun recordUserMessage(id: String, timestamp: Long, text: String) {
        val recorded =
                synchronized(stateLock) {
                    if (currentSession == null) {
                        Log.w(TAG, "No active session for recording user message")
                        return@synchronized false
                    }

                    // Finalize any pending agent message first
                    finalizeCurrentAgentMessage()

                    // Re-read after finalize (it may have updated currentSession)
                    val session = currentSession ?: return@synchronized false
                    val userMessage = MessageRecord.User(id = id, timestamp = timestamp, text = text)
                    currentSession =
                            session.copy(messages = session.messages + userMessage, lastUpdated = timestamp)
                    true
                }
        if (!recorded) return
        Log.d(TAG, "Recorded user message: ${text.take(30)}...")
        scheduleSave()
    }

    /** Start recording an agent message. */
    fun startAgentMessage(id: String, timestamp: Long) {
        val started =
                synchronized(stateLock) {
                    if (currentSession == null) {
                        Log.w(TAG, "No active session for starting agent message")
                        return@synchronized false
                    }
                    // Finalize any previous agent message
                    finalizeCurrentAgentMessage()
                    agentMessageBuffer.start(id)
                    true
                }
        if (!started) return

        Log.d(TAG, "Started agent message: $id")
    }

    /** Append text delta to current agent message. */
    fun appendTextDelta(delta: String) {
        synchronized(stateLock) {
            if (!agentMessageBuffer.hasActiveMessage()) {
                Log.w(TAG, "No active agent message for text delta")
                return
            }
            agentMessageBuffer.appendText(delta)
        }
        // Don't save on every delta - wait for action or completion
    }

    /** Record an action in current agent message. */
    fun recordAction(actionId: String, toolName: String, description: String, state: String) {
        synchronized(stateLock) {
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
        }
        Log.d(TAG, "Recorded action: $toolName ($state)")

        // Save after adding action
        updateAgentMessageInSession()
        scheduleSave()
    }

    /** Update an action's state and result. */
    fun updateActionState(actionId: String, state: String, result: String?) {
        synchronized(stateLock) {
            if (!agentMessageBuffer.hasActiveMessage()) {
                Log.w(TAG, "No active agent message for action update")
                return
            }
            agentMessageBuffer.updateActionState(actionId, state, result)
        }

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

    /** Record the outcome of the most recently completed task in the session. */
    fun recordTaskOutcome(outcome: TaskOutcome) {
        synchronized(stateLock) { lastTaskOutcome = outcome }
    }

    /** Mark session as completed. completedNormally derives from last task outcome. */
    fun completeSession() {
        val completedNormally = synchronized(stateLock) {
            lastTaskOutcome == TaskOutcome.GOAL_ACHIEVED
        }
        val pendingSave: Job? =
                synchronized(stateLock) {
                    // Finalize any pending agent message
                    finalizeCurrentAgentMessage()
                    val session = currentSession ?: return

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
                                                    completedNormally = completedNormally,
                                                    turnCount =
                                                            session.messages.count { it is MessageRecord.Agent }
                                            )
                            )
                    val pending = saveJob
                    saveJob = null
                    sessionRevision.incrementAndGet()
                    pending
                }

        Log.i(TAG, "Session completed")

        // Cancel debounced save, then force immediate save under write mutex
        pendingSave?.cancel()
        scope.launch {
            writeMutex.withLock { save() }
        }
    }

    /** Get the current session record. */
    fun getCurrentSession(): SessionRecord? = synchronized(stateLock) { currentSession }

    /** Get current file name. */
    fun getCurrentFileName(): String? = synchronized(stateLock) { currentFileName }

    /** Check if there's an active session. */
    fun hasActiveSession(): Boolean = synchronized(stateLock) { currentSession != null }

    /** Get current session ID. */
    fun getCurrentSessionId(): String? = synchronized(stateLock) { currentSession?.sessionId }

    /**
     * Clear session tracking (called when session ends).
     *
     * Suspends until any pending save/checkpoint jobs complete, then clears
     * in-memory state synchronously before returning. No fire-and-forget —
     * callers can safely create a new session immediately after this returns.
     */
    suspend fun clearSessionAndAwait() {
        val (pendingSave, pendingCheckpoint) =
                synchronized(stateLock) {
                    // Bump revisions so any lingering writes detect staleness
                    sessionRevision.incrementAndGet()
                    checkpointRevision.incrementAndGet()
                    val pending = saveJob to checkpointSaveJob
                    saveJob = null
                    checkpointSaveJob = null
                    pending
                }
        pendingSave?.cancel()
        pendingCheckpoint?.cancel()
        pendingSave?.join()
        pendingCheckpoint?.join()
        synchronized(stateLock) {
            currentSession = null
            currentFileName = null
            agentMessageBuffer.clear()
            contextFileName = null
        }
        Log.d(TAG, "Session tracking cleared")
    }

    // ===== Checkpoint (LLM context snapshot) =====

    private var contextFileName: String? = null
    private var checkpointSaveJob: Job? = null

    /**
     * Schedule a debounced checkpoint save. Called when HistoryManager, TodoState,
     * or ScratchpadState mutates.
     */
    fun scheduleCheckpoint(snapshotProvider: () -> SessionRuntimeSnapshot) {
        synchronized(stateLock) {
            checkpointSaveJob?.cancel()
            val rev = checkpointRevision.incrementAndGet()
            checkpointSaveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                writeMutex.withLock {
                    if (rev < checkpointRevision.get()) {
                        Log.d(TAG, "Skipping stale checkpoint write rev=$rev")
                        return@launch
                    }
                    val snapshot = snapshotProvider()
                    saveCheckpoint(snapshot)
                }
            }
        }
    }

    /**
     * Force-flush a checkpoint immediately (no debounce).
     * Preempts any pending debounced checkpoint — cancels it, then writes under mutex.
     */
    suspend fun forceCheckpoint(snapshot: SessionRuntimeSnapshot): Boolean {
        synchronized(stateLock) {
            checkpointSaveJob?.cancel()
            checkpointSaveJob = null
            checkpointRevision.incrementAndGet()
        }
        return writeMutex.withLock {
            saveCheckpoint(snapshot)
        }
    }

    /** Get the context snapshot filename for the current session. */
    fun getContextFileName(): String? = synchronized(stateLock) {
        contextFileName ?: currentFileName?.let { sessionFile ->
            val name = storage.contextFileNameFor(sessionFile)
            contextFileName = name
            name
        }
    }

    private suspend fun saveCheckpoint(snapshot: SessionRuntimeSnapshot): Boolean {
        val fileName = getContextFileName() ?: run {
            Log.w(TAG, "No context file name for checkpoint")
            return false
        }
        return storage.writeSnapshot(fileName, snapshot).fold(
            onSuccess = { true },
            onFailure = { e ->
                Log.e(TAG, "Failed to save checkpoint", e)
                false
            }
        )
    }

    private suspend fun save() {
        val (fileName, session) =
                synchronized(stateLock) {
                    val current = currentSession ?: return
                    val file = currentFileName ?: return
                    file to current
                }

        storage.writeSession(fileName, session).onFailure { e ->
            Log.e(TAG, "Failed to save session", e)
        }
    }

    /** Finalize the current agent message and add it to the session. */
    private fun finalizeCurrentAgentMessage() {
        synchronized(stateLock) {
            val snapshot = agentMessageBuffer.finalizeSnapshot() ?: return
            val session = currentSession ?: return
            currentSession =
                SessionRecordMessageMerger.mergeAgentSnapshot(
                    session = session,
                    snapshot = snapshot,
                    isComplete = true
                )
        }
    }

    /**
     * Update the current agent message in the session without finalizing. Used for incremental
     * updates (action state changes).
     */
    private fun updateAgentMessageInSession() {
        synchronized(stateLock) {
            val snapshot = agentMessageBuffer.buildPartialSnapshot() ?: return
            val session = currentSession ?: return
            currentSession =
                SessionRecordMessageMerger.mergeAgentSnapshot(
                    session = session,
                    snapshot = snapshot,
                    isComplete = false
                )
        }
    }

    /** Schedule a debounced save. Writes are serialized through writeMutex with revision check. */
    private fun scheduleSave() {
        synchronized(stateLock) {
            saveJob?.cancel()
            val rev = sessionRevision.incrementAndGet()
            saveJob =
                    scope.launch {
                        delay(SAVE_DEBOUNCE_MS)
                        writeMutex.withLock {
                            if (rev < sessionRevision.get()) {
                                Log.d(TAG, "Skipping stale session write rev=$rev")
                                return@launch
                            }
                            save()
                        }
                    }
        }
    }

    /** Record a screen state reference for replay/debug. */
    fun recordScreenState(state: ScreenStateRecord) {
        val normalizedState = normalizeScreenStateRecord(state)
        val recorded =
                synchronized(stateLock) {
                    val session = currentSession
                    if (session == null) {
                        Log.w(TAG, "No active session for recording screen state")
                        return@synchronized false
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
                    true
                }
        if (!recorded) return

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

}

/** Data class for resuming a session. */
data class ResumedSessionData(val session: SessionRecord, val fileName: String)

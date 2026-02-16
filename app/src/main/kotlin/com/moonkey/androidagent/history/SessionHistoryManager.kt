package com.moonkey.androidagent.history

import android.util.Log
import com.moonkey.androidagent.history.model.MessageRecord
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.protocol.CompletionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level session management API.
 * 
 * This class provides the main interface for:
 * - Listing all sessions (lightweight, for UI display)
 * - Loading a session for resuming
 * - Deleting sessions
 * - Creating new sessions
 * - Resuming existing sessions
 * 
 * It coordinates between SessionStorage (file I/O) and SessionRecordingService (real-time recording).
 */
class SessionHistoryManager(
    private val storage: SessionStorage,
    private val recordingService: SessionRecordingService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SessionHistoryManager"
        
        /** Maximum characters for display title */
        private const val MAX_TITLE_LENGTH = 50
        
        /**
         * Factory method for creating a SessionHistoryManager.
         */
        fun create(
            storage: SessionStorage,
            scope: CoroutineScope
        ): SessionHistoryManager {
            val recordingService = SessionRecordingService(storage, scope)
            return SessionHistoryManager(storage, recordingService, scope)
        }
    }

    private data class CachedSessionInfo(
        val lastModified: Long,
        val info: SessionInfo
    )

    private val sessionInfoCache = ConcurrentHashMap<String, CachedSessionInfo>()
    private val cacheMutex = Mutex()
    
    /**
     * List all sessions (lightweight, doesn't load full content).
     * 
     * @return List of SessionInfo sorted by last updated (newest first)
     */
    suspend fun listSessions(): List<SessionInfo> {
        val files = storage.listSessionFiles()
        Log.d(TAG, "Found ${files.size} session files")

        return files.mapNotNull { file ->
            getSessionInfoCached(file)
        }
    }
    
    /**
     * Load a session for resuming.
     * 
     * @param sessionId The full session ID to load
     * @return Result containing ResumedSessionData or an error
     */
    suspend fun loadSession(sessionId: String): Result<ResumedSessionData> {
        // Find the file for this session using full session ID to avoid collisions
        val files = storage.listSessionFiles()
        val file = files.find { it.name.contains(sessionId) }
            ?: return Result.failure(NoSuchElementException("Session not found: $sessionId"))
        
        return loadSessionByFileName(file.name)
    }
    
    /**
     * Load a session by file name.
     * 
     * @param fileName The session file name
     * @return Result containing ResumedSessionData or an error
     */
    suspend fun loadSessionByFileName(fileName: String): Result<ResumedSessionData> {
        return storage.readSession(fileName).map { record ->
            ResumedSessionData(
                session = record,
                fileName = fileName
            )
        }
    }
    
    /**
     * Delete a session.
     * 
     * @param sessionId The full session ID to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> {
        // Find the file for this session using full session ID to avoid collisions
        val files = storage.listSessionFiles()
        val file = files.find { it.name.contains(sessionId) }
            ?: return Result.failure(NoSuchElementException("Session not found: $sessionId"))
        
        return storage.deleteSession(file.name).onSuccess {
            cacheMutex.withLock {
                sessionInfoCache.remove(file.name)
            }
        }
    }
    
    /**
     * Delete a session by file name.
     */
    suspend fun deleteSessionByFileName(fileName: String): Result<Unit> {
        return storage.deleteSession(fileName).onSuccess {
            cacheMutex.withLock {
                sessionInfoCache.remove(fileName)
            }
        }
    }
    
    /**
     * Get the most recent session (for "resume latest" feature).
     * 
     * @return The most recent SessionInfo, or null if no sessions exist
     */
    suspend fun getMostRecentSession(): SessionInfo? {
        val files = storage.listSessionFiles()
        if (files.isEmpty()) return null
        
        // Files are already sorted by modification time (newest first)
        return getSessionInfoCached(files.first())
    }
    
    /**
     * Start a new session.
     * 
     * @param model The LLM model being used
     * @param appVersion The app version
     * @return The new session ID
     */
    fun startNewSession(model: String? = null, appVersion: String? = null): String {
        return recordingService.initializeNewSession(model = model, appVersion = appVersion)
    }
    
    /**
     * Resume an existing session.
     * 
     * @param data The session data to resume
     */
    fun resumeSession(data: ResumedSessionData) {
        recordingService.resumeSession(data)
    }
    
    /**
     * Get current session ID (if any).
     */
    fun getCurrentSessionId(): String? {
        return recordingService.getCurrentSessionId()
    }
    
    /**
     * Check if there's an active session.
     */
    fun hasActiveSession(): Boolean {
        return recordingService.hasActiveSession()
    }
    
    /**
     * Get the recording service (for event recording).
     */
    fun getRecordingService(): SessionRecordingService = recordingService
    
    /**
     * Clear session tracking (when ending a session).
     */
    fun endSession(reason: CompletionReason = CompletionReason.GOAL_ACHIEVED) {
        recordingService.completeSession(reason)
    }
    
    // ===== Private Helpers =====
    
    /**
     * Extract SessionInfo from a session file.
     */
    private suspend fun extractSessionInfo(fileName: String): SessionInfo? {
        val record = storage.readSession(fileName).getOrNull() ?: return null
        
        // Extract first user message for preview
        val firstUserMessage = record.messages
            .filterIsInstance<MessageRecord.User>()
            .firstOrNull()?.text ?: "Empty session"
        
        // Use summary if available, otherwise truncate first user message
        val displayTitle = record.summary ?: firstUserMessage.let { msg ->
            if (msg.length > MAX_TITLE_LENGTH) {
                "${msg.take(MAX_TITLE_LENGTH)}..."
            } else {
                msg
            }
        }
        
        return SessionInfo(
            id = record.sessionId,
            fileName = fileName,
            startTime = record.startTime,
            lastUpdated = record.lastUpdated,
            messageCount = record.messages.size,
            displayTitle = displayTitle,
            firstUserMessage = firstUserMessage,
            isActive = record.sessionId == getCurrentSessionId()
        )
    }

    private suspend fun getSessionInfoCached(file: File): SessionInfo? {
        val fileName = file.name
        val initialLastModified = file.lastModified()

        val cached = cacheMutex.withLock { sessionInfoCache[fileName] }
        val baseInfo: SessionInfo? =
            if (cached != null && cached.lastModified == initialLastModified) {
                cached.info
            } else {
                try {
                    val fresh = extractSessionInfo(fileName) ?: run {
                        cacheMutex.withLock { sessionInfoCache.remove(fileName) }
                        return null
                    }

                    val currentLastModified = file.lastModified()
                    if (currentLastModified != initialLastModified) {
                        Log.w(
                            TAG,
                            "Session file $fileName modified during read; returning uncached info"
                        )
                        fresh
                    } else {
                        cacheMutex.withLock {
                            sessionInfoCache[fileName] = CachedSessionInfo(currentLastModified, fresh)
                        }
                        fresh
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract session info for $fileName", e)
                    cacheMutex.withLock { sessionInfoCache.remove(fileName) }
                    return null
                }
            }

        val currentSessionId = getCurrentSessionId()
        return baseInfo?.copy(isActive = baseInfo.id == currentSessionId)
    }
}

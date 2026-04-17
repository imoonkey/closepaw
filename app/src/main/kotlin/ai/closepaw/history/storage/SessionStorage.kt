package ai.closepaw.history.storage

import android.content.Context
import android.util.Log
import ai.closepaw.history.model.SessionRecord
import ai.closepaw.history.model.SessionRuntimeSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Low-level storage operations for session files.
 * 
 * Files are stored in: /data/data/{package}/files/sessions/
 * File naming: session-{yyyy-MM-ddTHH-mm-ss}-{uuid_8chars}.json
 * 
 * This class handles all file I/O operations and is the only component
 * that should directly interact with the filesystem for session data.
 */
class SessionStorage(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    
    companion object {
        private const val TAG = "SessionStorage"
        private const val SESSIONS_DIR = "sessions"
        private const val SESSION_PREFIX = "session-"
        private const val CONTEXT_PREFIX = "context-"
        private const val SESSION_SUFFIX = ".json"
        
        // DateTimeFormatter is thread-safe unlike SimpleDateFormat
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    }
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Get the sessions directory, creating if needed.
     */
    fun getSessionsDir(): File {
        val dir = File(context.filesDir, SESSIONS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            Log.d(TAG, "Created sessions directory: ${dir.absolutePath}")
        }
        return dir
    }
    
    /**
     * Generate a filename for a new session.
     * 
     * Format: session-{timestamp}-{uuid}.json
     * Example: session-2024-01-21T14-30-45-a1b2c3d4-e5f6-7890-abcd-ef1234567890.json
     */
    fun generateFileName(sessionId: String): String {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        return "$SESSION_PREFIX$timestamp-$sessionId$SESSION_SUFFIX"
    }
    
    /**
     * Write a session record to disk.
     * 
     * @param fileName The name of the file to write to
     * @param record The session record to persist
     * @return Result indicating success or failure
     */
    suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dir = getSessionsDir()
            val target = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            val jsonString = json.encodeToString(record)
            tmp.writeText(jsonString)
            if (!tmp.renameTo(target)) {
                target.writeText(jsonString)
                tmp.delete()
            }
            Log.d(TAG, "Wrote session to ${target.name}, size=${jsonString.length} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write session $fileName", e)
            Result.failure(e)
        }
    }
    
    /**
     * Read a session record from disk.
     * 
     * @param fileName The name of the file to read
     * @return Result containing the session record or an error
     */
    suspend fun readSession(fileName: String): Result<SessionRecord> = withContext(ioDispatcher) {
        try {
            val file = File(getSessionsDir(), fileName)
            if (!file.exists()) {
                return@withContext Result.failure(NoSuchFileException(file))
            }
            val jsonString = file.readText()
            val record = json.decodeFromString<SessionRecord>(jsonString)
            Log.d(TAG, "Read session from ${file.name}, messages=${record.messages.size}")
            Result.success(record)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read session $fileName", e)
            Result.failure(e)
        }
    }
    
    /**
     * List all session files (sorted by modification time, newest first).
     * 
     * @return List of session files, sorted by last modified descending
     */
    suspend fun listSessionFiles(): List<File> = withContext(ioDispatcher) {
        val dir = getSessionsDir()
        val files = dir.listFiles { file ->
            file.isFile && 
            file.name.startsWith(SESSION_PREFIX) && 
            file.name.endsWith(SESSION_SUFFIX)
        } ?: emptyArray()
        
        files.sortedByDescending { it.lastModified() }
    }
    
    /**
     * Delete a session file.
     * 
     * @param fileName The name of the file to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteSession(fileName: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val file = File(getSessionsDir(), fileName)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted session: $fileName")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to delete file: ${file.absolutePath}"))
                }
            } else {
                Log.w(TAG, "Session file not found for deletion: $fileName")
                Result.success(Unit) // Consider already deleted as success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session $fileName", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if a session file exists.
     */
    fun sessionExists(fileName: String): Boolean {
        return File(getSessionsDir(), fileName).exists()
    }
    
    /**
     * Get the full path to a session file.
     */
    fun getSessionFile(fileName: String): File {
        return File(getSessionsDir(), fileName)
    }

    /**
     * Generate context snapshot filename that pairs with a session filename.
     * Shares the same `{ts}-{uuid}` suffix so files can be correlated.
     */
    fun contextFileNameFor(sessionFileName: String): String {
        val suffix = sessionFileName.removePrefix(SESSION_PREFIX)
        return "$CONTEXT_PREFIX$suffix"
    }

    /**
     * Write a runtime snapshot atomically (temp file + rename).
     */
    suspend fun writeSnapshot(
        fileName: String,
        snapshot: SessionRuntimeSnapshot
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dir = getSessionsDir()
            val target = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            val jsonString = json.encodeToString(snapshot)
            tmp.writeText(jsonString)
            if (!tmp.renameTo(target)) {
                target.writeText(jsonString)
                tmp.delete()
            }
            Log.d(TAG, "Wrote snapshot to ${target.name}, size=${jsonString.length} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapshot $fileName", e)
            Result.failure(e)
        }
    }

    /**
     * Read a runtime snapshot from disk.
     */
    suspend fun readSnapshot(fileName: String): Result<SessionRuntimeSnapshot> = withContext(ioDispatcher) {
        try {
            val file = File(getSessionsDir(), fileName)
            if (!file.exists()) {
                return@withContext Result.failure(NoSuchFileException(file))
            }
            val jsonString = file.readText()
            val snapshot = json.decodeFromString<SessionRuntimeSnapshot>(jsonString)
            Log.d(TAG, "Read snapshot from ${file.name}, items=${snapshot.historyItems.size}")
            Result.success(snapshot)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snapshot $fileName", e)
            Result.failure(e)
        }
    }

    /**
     * List context snapshot files (newest first).
     */
    suspend fun listSnapshotFiles(): List<File> = withContext(ioDispatcher) {
        val dir = getSessionsDir()
        val files = dir.listFiles { file ->
            file.isFile &&
            file.name.startsWith(CONTEXT_PREFIX) &&
            file.name.endsWith(SESSION_SUFFIX)
        } ?: emptyArray()
        files.sortedByDescending { it.lastModified() }
    }

    /**
     * Delete both session record and context snapshot for a given session file name.
     */
    suspend fun deleteSessionPair(sessionFileName: String): Result<Unit> = withContext(ioDispatcher) {
        val contextFileName = contextFileNameFor(sessionFileName)
        val sessionResult = deleteSession(sessionFileName)
        try {
            val contextFile = File(getSessionsDir(), contextFileName)
            if (contextFile.exists()) {
                contextFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete context file $contextFileName", e)
        }
        sessionResult
    }
}

package com.moonkey.androidagent.history.storage

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.history.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Low-level storage operations for session files.
 * 
 * Files are stored in: /data/data/{package}/files/sessions/
 * File naming: session-{yyyy-MM-ddTHH-mm-ss}-{uuid_8chars}.json
 * 
 * This class handles all file I/O operations and is the only component
 * that should directly interact with the filesystem for session data.
 */
class SessionStorage(private val context: Context) {
    
    companion object {
        private const val TAG = "SessionStorage"
        private const val SESSIONS_DIR = "sessions"
        private const val SESSION_PREFIX = "session-"
        private const val SESSION_SUFFIX = ".json"
        
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US)
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
     * Format: session-{timestamp}-{uuid_8chars}.json
     * Example: session-2024-01-21T14-30-45-a1b2c3d4.json
     */
    fun generateFileName(sessionId: String): String {
        val timestamp = dateFormat.format(Date())
        val shortId = sessionId.take(8)
        return "$SESSION_PREFIX$timestamp-$shortId$SESSION_SUFFIX"
    }
    
    /**
     * Write a session record to disk.
     * 
     * @param fileName The name of the file to write to
     * @param record The session record to persist
     * @return Result indicating success or failure
     */
    suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(getSessionsDir(), fileName)
            val jsonString = json.encodeToString(record)
            file.writeText(jsonString)
            Log.d(TAG, "Wrote session to ${file.name}, size=${jsonString.length} bytes")
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
    suspend fun readSession(fileName: String): Result<SessionRecord> = withContext(Dispatchers.IO) {
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
    suspend fun listSessionFiles(): List<File> = withContext(Dispatchers.IO) {
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
    suspend fun deleteSession(fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(getSessionsDir(), fileName)
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted session: $fileName")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to delete file"))
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
}

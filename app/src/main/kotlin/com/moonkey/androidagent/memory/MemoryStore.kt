package com.moonkey.androidagent.memory

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent markdown-based memory store for cross-session agent experience.
 *
 * Files live under [memoryDir] organized by entity (app package, user_prefs, device).
 * Each file is a simple markdown list with timestamped entries.
 */
class MemoryStore(
    private val memoryDir: File,
    private val readOnly: Boolean = false,
    val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH
) {
    companion object {
        const val APP_ENTRY_CAP = 30
        const val USER_PREFS_ENTRY_CAP = 20
        const val DEVICE_ENTRY_CAP = 10
        const val DEFAULT_MAX_CONTENT_LENGTH = 2000
        private const val APPS_DIR = "apps"
        private const val USER_PREFS_FILE = "user_prefs.md"
        private const val DEVICE_FILE = "device.md"
    }

    private val writtenThisSession = AtomicBoolean(false)

    fun hasWrittenThisSession(): Boolean = writtenThisSession.get()

    @Synchronized
    fun appendAppMemory(packageName: String, content: String) {
        if (readOnly) return
        val file = File(File(memoryDir, APPS_DIR), "$packageName.md")
        appendEntry(file, "# App Memory: $packageName", content, APP_ENTRY_CAP)
        writtenThisSession.set(true)
    }

    @Synchronized
    fun appendUserPref(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, USER_PREFS_FILE), "# User Preferences", content, USER_PREFS_ENTRY_CAP)
        writtenThisSession.set(true)
    }

    @Synchronized
    fun appendDeviceMemory(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, DEVICE_FILE), "# Device", content, DEVICE_ENTRY_CAP)
        writtenThisSession.set(true)
    }

    @Synchronized
    fun readAppMemory(packageName: String): String? =
        readFileIfExists(File(File(memoryDir, APPS_DIR), "$packageName.md"))

    @Synchronized
    fun readUserPrefs(): String? =
        readFileIfExists(File(memoryDir, USER_PREFS_FILE))

    @Synchronized
    fun readDevice(): String? =
        readFileIfExists(File(memoryDir, DEVICE_FILE))

    private fun appendEntry(file: File, header: String, content: String, cap: Int) {
        try {
            file.parentFile?.mkdirs()
            val date = LocalDate.now().toString()
            val truncated = content.take(maxContentLength)
            val entry = "- [$date] $truncated"

            if (!file.exists()) {
                file.writeText("$header\n\n$entry\n")
                return
            }
            file.appendText("$entry\n")
            enforceEntryCap(file, header, cap)
        } catch (_: IOException) {
            // Silent failure — memory ops must never block task completion
        }
    }

    private fun enforceEntryCap(file: File, header: String, cap: Int) {
        val lines = file.readLines()
        val entries = lines.filter { it.trimStart().startsWith("- [") }
        if (entries.size <= cap) return
        val kept = entries.takeLast(cap)
        file.writeText("$header\n\n${kept.joinToString("\n")}\n")
    }

    private fun readFileIfExists(file: File): String? {
        if (!file.exists()) return null
        return try {
            val content = file.readText().trim()
            content.takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }
}

package com.moonkey.androidagent.memory

/**
 * Selects and formats relevant memory for prompt injection each turn.
 *
 * Elastic budget: device and user_prefs get fixed budgets, app memory gets the remainder.
 * Truncation is newest-first (keep tail, drop head).
 */
class MemoryRecaller(private val store: MemoryStore) {

    companion object {
        private const val DEVICE_BUDGET = 1024       // 1 KB
        private const val USER_PREFS_BUDGET = 1536   // 1.5 KB
        private const val TOTAL_BUDGET = 6144         // 6 KB
    }

    fun recall(currentPackageName: String?): String? {
        val sections = mutableListOf<String>()
        var remaining = TOTAL_BUDGET

        // 1. Device
        store.readDevice()?.let { raw ->
            val entries = extractEntries(raw).truncateToRecent(minOf(DEVICE_BUDGET, remaining))
            if (entries != null) {
                sections.add("### Device\n$entries")
                remaining -= entries.toByteArray().size
            }
        }

        // 2. User Preferences
        if (remaining > 0) {
            store.readUserPrefs()?.let { raw ->
                val entries = extractEntries(raw).truncateToRecent(minOf(USER_PREFS_BUDGET, remaining))
                if (entries != null) {
                    sections.add("### User Preferences\n$entries")
                    remaining -= entries.toByteArray().size
                }
            }
        }

        // 3. App memory (gets remaining budget, up to 3.5 KB)
        if (currentPackageName != null && remaining > 0) {
            store.readAppMemory(currentPackageName)?.let { raw ->
                val entries = extractEntries(raw).truncateToRecent(remaining)
                if (entries != null) {
                    sections.add("### App: $currentPackageName\n$entries")
                }
            }
        }

        if (sections.isEmpty()) return null

        return buildString {
            appendLine("## Recalled Memory")
            appendLine()
            appendLine("These are learnings from previous sessions. Use them to avoid repeating mistakes.")
            for (section in sections) {
                appendLine()
                appendLine(section)
            }
        }.trim()
    }

    private fun extractEntries(content: String): String =
        content.lines().filter { it.trimStart().startsWith("- [") }.joinToString("\n")

    /** Keep newest entries (tail) that fit within [maxBytes]. */
    private fun String.truncateToRecent(maxBytes: Int): String? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.toByteArray().size <= maxBytes) return trimmed
        val lines = trimmed.lines()
        val result = mutableListOf<String>()
        var size = 0
        for (line in lines.reversed()) {
            val lineSize = line.toByteArray().size + 1
            if (size + lineSize > maxBytes) break
            result.add(0, line)
            size += lineSize
        }
        return result.joinToString("\n").trim().ifEmpty { null }
    }
}

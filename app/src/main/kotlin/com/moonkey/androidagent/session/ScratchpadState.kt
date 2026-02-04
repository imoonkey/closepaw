package com.moonkey.androidagent.session

class ScratchpadState(
    private val data: MutableMap<String, String> = mutableMapOf()
) {
    fun write(key: String, value: String) {
        data[key] = value
    }

    fun read(key: String): String? = data[key]

    fun delete(key: String): Boolean = data.remove(key) != null

    fun list(): List<String> = data.keys.sorted()

    fun clear() {
        data.clear()
    }

    fun toPromptContext(): String {
        if (data.isEmpty()) return ""
        return data.entries
            .sortedBy { it.key }
            .joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}

package com.moonkey.androidagent.session

/**
 * Thread-safe key-value scratchpad with size limits to protect prompt budget.
 */
class ScratchpadState(
    private val data: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2048
    }

    private val lock = Any()
    private var onMutation: (() -> Unit)? = null

    fun setMutationListener(listener: (() -> Unit)?) {
        onMutation = listener
    }

    fun write(key: String, value: String) {
        require(key.length <= MAX_KEY_LENGTH) {
            "Scratchpad key too long (max $MAX_KEY_LENGTH chars)"
        }
        require(value.length <= MAX_VALUE_LENGTH) {
            "Scratchpad value too long (max $MAX_VALUE_LENGTH chars)"
        }
        synchronized(lock) {
            val exists = data.containsKey(key)
            if (!exists && data.size >= MAX_ENTRIES) {
                throw IllegalStateException("Scratchpad is full (max $MAX_ENTRIES entries)")
            }
            data[key] = value
        }
        onMutation?.invoke()
    }

    fun read(key: String): String? = synchronized(lock) { data[key] }

    fun delete(key: String): Boolean {
        val removed = synchronized(lock) { data.remove(key) != null }
        if (removed) {
            onMutation?.invoke()
        }
        return removed
    }

    fun list(): List<String> = synchronized(lock) { data.keys.sorted() }

    fun toMap(): Map<String, String> = synchronized(lock) { data.toMap() }

    fun clear() {
        synchronized(lock) {
            data.clear()
        }
        onMutation?.invoke()
    }

    fun toPromptContext(): String {
        val keys = synchronized(lock) { data.keys.sorted() }
        if (keys.isEmpty()) {
            return "- (empty) Store important facts with scratchpad(action=\"write\", key=\"...\", value=\"...\") before navigating away."
        }
        return keys.joinToString("\n") { "- $it" }
    }
}

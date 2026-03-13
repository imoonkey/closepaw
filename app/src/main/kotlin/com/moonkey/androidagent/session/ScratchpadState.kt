package com.moonkey.androidagent.session

import org.json.JSONObject

/**
 * Thread-safe key-value scratchpad backed by [JSONObject].
 *
 * JSON-in (write) → JSON-store → JSON-out (prompt): no format translation at any stage.
 * Values can be any JSON-compatible type (String, Number, Boolean, JSONArray, JSONObject).
 */
class ScratchpadState {

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2048

        /** Per-value char limit before truncation in prompt display. */
        const val DISPLAY_TRUNCATE_LENGTH = 400

        /** Total char budget for the entire scratchpad prompt section. */
        const val TOTAL_BUDGET = 3000
    }

    private val lock = Any()
    private val data = JSONObject()
    @Volatile private var onMutation: (() -> Unit)? = null

    fun setMutationListener(listener: (() -> Unit)?) {
        onMutation = listener
    }

    /**
     * Write a single key-value pair. Value can be any JSON-compatible type.
     * Validation: key length, value length (via toString()), entry count.
     */
    fun write(key: String, value: Any) {
        require(key.length <= MAX_KEY_LENGTH) {
            "Scratchpad key too long (max $MAX_KEY_LENGTH chars)"
        }
        val serialized = value.toString()
        require(serialized.length <= MAX_VALUE_LENGTH) {
            "Scratchpad value too long (max $MAX_VALUE_LENGTH chars)"
        }
        synchronized(lock) {
            val exists = data.has(key)
            if (!exists && data.length() >= MAX_ENTRIES) {
                throw IllegalStateException("Scratchpad is full (max $MAX_ENTRIES entries)")
            }
            data.put(key, value)
        }
        onMutation?.invoke()
    }

    /** Read a value by key. Returns the native JSON type, or null if absent. */
    fun read(key: String): Any? = synchronized(lock) {
        if (data.has(key)) data.get(key) else null
    }

    fun delete(key: String): Boolean {
        val removed = synchronized(lock) {
            if (data.has(key)) {
                data.remove(key)
                true
            } else {
                false
            }
        }
        if (removed) {
            onMutation?.invoke()
        }
        return removed
    }

    fun list(): List<String> = synchronized(lock) {
        data.keys().asSequence().toList().sorted()
    }

    /** Deep copy of internal state as a new JSONObject. */
    fun toJsonObject(): JSONObject = synchronized(lock) {
        JSONObject(data.toString())
    }

    fun clear() {
        synchronized(lock) {
            val keys = data.keys().asSequence().toList()
            keys.forEach { data.remove(it) }
        }
        onMutation?.invoke()
    }

    /**
     * Build the prompt-visible scratchpad section.
     *
     * Format: JSON object with truncation for long values and a total budget cap.
     * Non-truncated values are valid JSON. Truncated values end with `...` and a comment.
     */
    fun toPromptContext(): String {
        val snapshot = synchronized(lock) { JSONObject(data.toString()) }
        val keys = snapshot.keys().asSequence().toList().sorted()

        if (keys.isEmpty()) {
            return "(empty) Store important facts with scratchpad(action=\"write\"," +
                " content='{\"key\": \"value\"}') before navigating away."
        }

        val sb = StringBuilder()
        sb.append("{\n")
        var remainingBudget = TOTAL_BUDGET

        for (key in keys) {
            if (remainingBudget <= 0) {
                val line = "  \"$key\": \"...\" // use read\n"
                sb.append(line)
                continue
            }

            val value = snapshot.get(key)
            val serialized = serializeValue(value)

            if (serialized.length <= DISPLAY_TRUNCATE_LENGTH) {
                val line = "  \"$key\": $serialized\n"
                sb.append(line)
                remainingBudget -= line.length
            } else {
                val truncated = serialized.take(DISPLAY_TRUNCATE_LENGTH) + "..."
                val line = "  \"$key\": $truncated // truncated, ${serialized.length} chars\n"
                sb.append(line)
                remainingBudget -= line.length
            }
        }

        sb.append("}")
        return sb.toString()
    }

    /** Serialize a value to its JSON representation string. */
    private fun serializeValue(value: Any): String {
        return when (value) {
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }

    fun entryCount(): Int = synchronized(lock) { data.length() }
}

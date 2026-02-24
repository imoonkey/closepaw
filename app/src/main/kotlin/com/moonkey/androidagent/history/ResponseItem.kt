package com.moonkey.androidagent.history

import org.json.JSONObject

/**
 * Classification of [ResponseItem.Message] content.
 *
 * Replaces the ambiguous `role: String` + `isScreenObservation: Boolean` pairing.
 * API role is derived: [USER_INTENT]/[SCREEN_OBSERVATION] → "user",
 * [ASSISTANT_TEXT]/[COMPRESSION_DIGEST] → "assistant".
 */
enum class MessageKind {
    USER_INTENT,
    SCREEN_OBSERVATION,
    ASSISTANT_TEXT,
    COMPRESSION_DIGEST;

    /** Derive the API-level role string for LLM requests. */
    val apiRole: String
        get() = when (this) {
            USER_INTENT, SCREEN_OBSERVATION -> "user"
            ASSISTANT_TEXT, COMPRESSION_DIGEST -> "assistant"
        }
}

/** Conversation item persisted in history and sent back to the LLM. */
sealed class ResponseItem {
    abstract fun estimateTokens(): Long

    data class Message(
        val kind: MessageKind,
        val content: String,
        val name: String? = null
    ) : ResponseItem() {
        /** API role derived from [kind]. */
        val role: String get() = kind.apiRole

        override fun estimateTokens(): Long = (content.length * TOKENS_PER_CHAR).toLong() + 4
    }

    data class FunctionCall(
        val id: String,
        val name: String,
        val arguments: JSONObject
    ) : ResponseItem() {
        override fun estimateTokens(): Long =
            ((name.length + arguments.toString().length) * TOKENS_PER_CHAR).toLong() + 10
    }

    data class FunctionCallOutput(
        val callId: String,
        val content: String,
        val success: Boolean = true,
        val truncated: Boolean = false
    ) : ResponseItem() {
        override fun estimateTokens(): Long = (content.length * TOKENS_PER_CHAR).toLong() + 4
    }
}

private const val TOKENS_PER_CHAR = 0.25f

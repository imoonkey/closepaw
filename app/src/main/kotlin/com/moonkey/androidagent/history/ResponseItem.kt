package com.moonkey.androidagent.history

import org.json.JSONObject

/** Conversation item persisted in history and sent back to the LLM. */
sealed class ResponseItem {
    abstract fun estimateTokens(): Long

    data class Message(
        val role: String,
        val content: String,
        val name: String? = null,
        val isScreenObservation: Boolean = false
    ) : ResponseItem() {
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

package com.moonkey.androidagent.llm

import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object OpenAIErrorClassifier {
    private const val TAG = "OpenAIErrorClassifier"

    fun classify(e: Exception): Exception = when (e) {
        // Fast-path: preserve existing domain exceptions
        is RateLimitException, is TransientException -> e

        // Typed SDK exceptions — no string matching needed
        is com.openai.errors.RateLimitException ->
            RateLimitException(e.message ?: "Rate limited")
        is com.openai.errors.InternalServerException ->
            TransientException("Server error", e)

        // Everything else goes through message-based fallback
        else -> classifyByMessage(e)
    }

    private fun classifyByMessage(e: Exception): Exception {
        val message = e.message.orEmpty()
        val cause = e.cause?.message.orEmpty()

        return when {
            isRateLimit(message, cause) -> {
                val retryAfter = extractRetryAfter(message) ?: extractRetryAfter(cause)
                Log.w(TAG, "Rate limit detected: ${e.message}")
                RateLimitException("Rate limited by OpenAI", retryAfter)
            }

            e is SocketTimeoutException -> {
                Log.w(TAG, "Request timeout", e)
                TransientException("Request timeout - try again", e)
            }

            isUnknownHost(e, message, cause) -> {
                Log.e(TAG, "Network error - cannot reach OpenAI: ${e.message}", e)
                RuntimeException("No internet connection. Please check your network settings.", e)
            }

            isServerError(message) || isServerError(cause) -> {
                Log.w(TAG, "Server error (transient): ${e.message}")
                TransientException("OpenAI server error", e)
            }

            e is IOException -> {
                val connectivityIssue = isConnectivityIssue(message) || isConnectivityIssue(cause)
                if (connectivityIssue) {
                    Log.e(TAG, "Network connectivity error: ${e.message}", e)
                    RuntimeException("Network error: Check your internet connection", e)
                } else {
                    Log.w(TAG, "Network/IO error: ${e.message}", e)
                    TransientException("Network error: ${e.message}", e)
                }
            }

            else -> {
                Log.e(TAG, "Responses API call failed: ${e.javaClass.name}: ${e.message}")
                RuntimeException("LLM error: ${e.javaClass.simpleName} - ${e.message}", e)
            }
        }
    }

    private fun isRateLimit(message: String, cause: String): Boolean {
        return message.contains("429") ||
            cause.contains("429") ||
            message.contains("rate limit", ignoreCase = true) ||
            cause.contains("rate limit", ignoreCase = true)
    }

    private fun isServerError(message: String): Boolean {
        return message.contains("500") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504")
    }

    private fun isUnknownHost(e: Exception, message: String, cause: String): Boolean {
        return e is UnknownHostException ||
            message.contains("Unable to resolve host") ||
            cause.contains("Unable to resolve host")
    }

    private fun isConnectivityIssue(message: String): Boolean {
        return message.contains("resolve", ignoreCase = true) ||
            message.contains("no address", ignoreCase = true)
    }

    private fun extractRetryAfter(message: String): Long? {
        val patterns = listOf(
            Regex("""retry.?after[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:please\s+)?wait(?:\s+for)?\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE),
            Regex("""try\s+again\s+in\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE),
            Regex("""available\s+in\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                val seconds = match.groupValues[1].toLongOrNull()
                if (seconds != null && seconds > 0 && seconds <= 3600) {
                    return seconds * 1000
                }
            }
        }
        return null
    }
}

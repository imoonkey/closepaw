package com.moonkey.androidagent.data.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * LLMClient - Wrapper for OpenAI API with rate limit handling.
 * 
 * Features:
 * - Automatic retry with exponential backoff on rate limits (429)
 * - Configurable retry parameters
 * - Detailed logging for debugging
 */
object LLMClient {

    private const val TAG = "LLMClient"
    private var client: OpenAIClient? = null
    private var isInitialized = false
    
    // Rate limit configuration
    private const val MAX_RETRIES = 5
    private const val INITIAL_BACKOFF_MS = 1000L
    private const val MAX_BACKOFF_MS = 60000L
    private const val BACKOFF_MULTIPLIER = 2.0

    fun initialize(apiKey: String) {
        Log.d(TAG, "Initializing LLMClient with key: ${apiKey.take(10)}...")
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
        isInitialized = true
        Log.i(TAG, "LLMClient initialized successfully")
    }

    suspend fun chat(messages: List<ChatMessage>): String {
        return withContext(Dispatchers.IO) {
            if (!isInitialized || client == null) {
                throw IllegalStateException("LLMClient not initialized. Call initialize() first.")
            }

            var lastException: Exception? = null
            var backoffMs = INITIAL_BACKOFF_MS
            
            for (attempt in 1..MAX_RETRIES) {
                try {
                    return@withContext executeChat(messages)
                } catch (e: RateLimitException) {
                    lastException = e
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for rate limit")
                        throw e
                    }
                    
                    // Use retry-after header if available, otherwise exponential backoff
                    val waitMs = e.retryAfterMs ?: backoffMs
                    Log.w(TAG, "Rate limited (attempt $attempt/$MAX_RETRIES), waiting ${waitMs}ms...")
                    
                    delay(waitMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    
                } catch (e: TransientException) {
                    lastException = e
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for transient error")
                        throw e.cause ?: e
                    }
                    
                    Log.w(TAG, "Transient error (attempt $attempt/$MAX_RETRIES): ${e.message}, retrying in ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            
            throw lastException ?: RuntimeException("Unexpected error in retry loop")
        }
    }
    
    /**
     * Execute the actual chat API call.
     */
    private fun executeChat(messages: List<ChatMessage>): String {
        Log.d(TAG, "Sending ${messages.size} messages to OpenAI...")
        messages.forEachIndexed { index, msg ->
            Log.d(TAG, "  [$index] ${msg.role}: ${msg.content.take(100)}...")
        }

        val builder = ChatCompletionCreateParams.builder().model(ChatModel.GPT_4O)

        messages.forEach { msg ->
            when (msg.role) {
                Role.SYSTEM -> builder.addSystemMessage(msg.content)
                Role.USER -> builder.addUserMessage(msg.content)
                Role.ASSISTANT -> builder.addAssistantMessage(msg.content)
            }
        }

        Log.d(TAG, "Making API call to OpenAI...")
        
        try {
            val response = client!!.chat().completions().create(builder.build())
            
            val choice = response.choices().firstOrNull()
            if (choice == null) {
                Log.e(TAG, "No choices in response")
                throw RuntimeException("No choices in LLM response")
            }
            
            val content = choice.message().content().orElse("")
            Log.d(TAG, "LLM Response (${content.length} chars): ${content.take(200)}...")
            return content.cleanJson()
            
        } catch (e: Exception) {
            // Check for rate limit (429) responses
            val message = e.message ?: ""
            val cause = e.cause?.message ?: ""
            
            when {
                message.contains("429") || cause.contains("429") || 
                message.contains("rate limit", ignoreCase = true) ||
                cause.contains("rate limit", ignoreCase = true) -> {
                    Log.w(TAG, "Rate limit detected: ${e.message}")
                    val retryAfter = extractRetryAfter(message) ?: extractRetryAfter(cause)
                    throw RateLimitException("Rate limited by OpenAI", retryAfter)
                }
                
                e is java.net.SocketTimeoutException -> {
                    Log.e(TAG, "Request timeout", e)
                    throw TransientException("Request timeout - try again", e)
                }
                
                e is java.net.UnknownHostException -> {
                    Log.e(TAG, "Network error - no internet: ${e.message}", e)
                    throw RuntimeException("No internet connection", e)
                }
                
                message.contains("500") || message.contains("502") || 
                message.contains("503") || message.contains("504") -> {
                    Log.w(TAG, "Server error (transient): ${e.message}")
                    throw TransientException("OpenAI server error", e)
                }
                
                e is java.io.IOException -> {
                    Log.e(TAG, "Network/IO error: ${e.message}", e)
                    throw TransientException("Network error: ${e.message}", e)
                }
                
                e is IllegalStateException -> {
                    Log.e(TAG, "State error: ${e.message}", e)
                    throw e
                }
                
                else -> {
                    Log.e(TAG, "LLM call failed: ${e.javaClass.name}: ${e.message}")
                    Log.e(TAG, "Exception cause: ${e.cause?.message}")
                    e.printStackTrace()
                    throw RuntimeException("LLM error: ${e.javaClass.simpleName} - ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Extract retry-after value from error message if present.
     */
    private fun extractRetryAfter(message: String): Long? {
        // Try to extract "retry after X seconds" or "retry-after: X"
        val patterns = listOf(
            Regex("""retry.?after[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""wait[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                val seconds = match.groupValues[1].toLongOrNull()
                if (seconds != null && seconds > 0) {
                    return seconds * 1000 // Convert to milliseconds
                }
            }
        }
        return null
    }

    /**
     * Clean response but preserve tool blocks for parsing.
     * Only strips ```json markers, not ```tool markers.
     */
    private fun String.cleanJson(): String {
        // Don't strip backticks - let Turn.kt parse tool blocks
        return this.trim()
    }
}

/**
 * Exception thrown when rate limited by the API.
 */
class RateLimitException(
    message: String,
    val retryAfterMs: Long? = null
) : Exception(message)

/**
 * Exception for transient errors that may succeed on retry.
 */
class TransientException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

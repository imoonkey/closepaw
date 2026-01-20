package com.moonkey.androidagent.data.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * LLMClient - Wrapper for OpenAI Responses API with rate limit handling.
 * 
 * Uses the Responses API with proper tool calling support.
 * 
 * Features:
 * - Native function/tool calling via Responses API
 * - Automatic retry with exponential backoff on rate limits (429)
 * - Proper ResponseInputItem types for conversation history
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
    
    /**
     * Call the Responses API with tool/function calling support.
     * 
     * Uses proper ResponseInputItem types for conversation history,
     * which enables correct function call/output correlation.
     * 
     * @param systemPrompt System/developer instructions
     * @param inputItems Conversation history as ResponseInputItem list
     * @param tools Tool definitions for function calling
     * @param model Model to use (defaults to GPT-4o)
     * @return ResponsesResult containing text output and/or tool calls
     */
    suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_4O
    ): ResponsesResult {
        return withContext(Dispatchers.IO) {
            if (!isInitialized || client == null) {
                throw IllegalStateException("LLMClient not initialized. Call initialize() first.")
            }

            var lastException: Exception? = null
            var backoffMs = INITIAL_BACKOFF_MS
            
            for (attempt in 1..MAX_RETRIES) {
                try {
                    return@withContext executeChatWithTools(systemPrompt, inputItems, tools, model)
                } catch (e: RateLimitException) {
                    lastException = e
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for rate limit")
                        throw e
                    }
                    
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
     * Execute the Responses API call with tools.
     */
    private fun executeChatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        Log.d(TAG, "Calling Responses API with ${inputItems.size} input items, ${tools.size} tools")
        
        try {
            val builder = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemPrompt)
                // Use Input.ofResponse to wrap the list of input items
                .input(ResponseCreateParams.Input.ofResponse(inputItems))
            
            // Add tools
            tools.forEach { tool ->
                builder.addTool(tool)
            }
            
            Log.d(TAG, "Making Responses API call to OpenAI...")
            
            val response = client!!.responses().create(builder.build())
            
            // Parse output items
            val textContent = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()
            
            for (item in response.output()) {
                when {
                    item.isFunctionCall() -> {
                        val funcCall = item.asFunctionCall()
                        toolCalls.add(LLMToolCall(
                            callId = funcCall.callId(),
                            name = funcCall.name(),
                            arguments = funcCall.arguments()
                        ))
                        Log.d(TAG, "Tool call: ${funcCall.name()} with id ${funcCall.callId()}")
                    }
                    item.isMessage() -> {
                        val message = item.asMessage()
                        for (content in message.content()) {
                            if (content.isOutputText()) {
                                textContent.append(content.asOutputText().text())
                            }
                        }
                    }
                }
            }
            
            val result = ResponsesResult(
                textContent = textContent.toString().takeIf { it.isNotEmpty() },
                toolCalls = toolCalls,
                responseId = response.id()
            )
            
            Log.d(TAG, "Responses API result: ${result.textContent?.take(200)}..., ${result.toolCalls.size} tool calls")
            return result
            
        } catch (e: Exception) {
            handleApiException(e)
        }
    }
    
    /**
     * Handle API exceptions with proper categorization.
     */
    private fun handleApiException(e: Exception): Nothing {
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
            
            e is java.net.UnknownHostException || 
            message.contains("Unable to resolve host") ||
            cause.contains("Unable to resolve host") -> {
                Log.e(TAG, "Network error - cannot reach OpenAI: ${e.message}", e)
                throw RuntimeException("No internet connection. Please check your network settings.", e)
            }
            
            message.contains("500") || message.contains("502") || 
            message.contains("503") || message.contains("504") -> {
                Log.w(TAG, "Server error (transient): ${e.message}")
                throw TransientException("OpenAI server error", e)
            }
            
            e is java.io.IOException -> {
                val isConnectivityIssue = message.contains("resolve") || 
                    cause.contains("resolve") ||
                    message.contains("No address") ||
                    cause.contains("No address")
                
                if (isConnectivityIssue) {
                    Log.e(TAG, "Network connectivity error: ${e.message}", e)
                    throw RuntimeException("Network error: Check your internet connection", e)
                }
                
                Log.e(TAG, "Network/IO error: ${e.message}", e)
                throw TransientException("Network error: ${e.message}", e)
            }
            
            else -> {
                Log.e(TAG, "Responses API call failed: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                throw RuntimeException("LLM error: ${e.javaClass.simpleName} - ${e.message}", e)
            }
        }
    }
    
    /**
     * Extract retry-after value from error message if present.
     */
    private fun extractRetryAfter(message: String): Long? {
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
}

/**
 * Result from the Responses API.
 */
data class ResponsesResult(
    /** Text content from the model (may be null if only tool calls) */
    val textContent: String?,
    /** Tool calls requested by the model */
    val toolCalls: List<LLMToolCall>,
    /** Response ID for multi-turn conversation tracking */
    val responseId: String
)

/**
 * A tool call from the LLM via the Responses API.
 */
data class LLMToolCall(
    /** The call ID assigned by OpenAI - use this for tool result correlation */
    val callId: String,
    /** The name of the tool/function to call */
    val name: String,
    /** The arguments as a JSON string */
    val arguments: String
)

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

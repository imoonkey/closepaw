package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * - Native streaming support via ResponseStreamEvent
 * 
 * Now instance-based (not singleton) to support:
 * - Thread-safe initialization
 * - Different API keys for different sessions
 * - Proper dependency injection
 */
class LLMClient(apiKey: String) {
    
    companion object {
        private const val TAG = "LLMClient"
        
        // Rate limit configuration
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60000L
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    private val client: OpenAIClient
    
    init {
        Log.d(TAG, "Creating LLMClient with key: ${apiKey.take(10)}...")
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
        Log.i(TAG, "LLMClient created successfully")
    }
    
    /**
     * Call the Responses API with tool/function calling support (non-streaming).
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
     * Streaming version of chatWithTools using native OpenAI SDK streaming.
     * 
     * Uses the OpenAI Java SDK's native streaming support via createStreaming().
     * Emits ResponseStreamEvent directly from the SDK, allowing consumers to
     * process text deltas and tool calls as they arrive.
     * 
     * The consumer should use ResponseAccumulator to build the final response.
     * 
     * @param systemPrompt System/developer instructions
     * @param inputItems Conversation history as ResponseInputItem list
     * @param tools Tool definitions for function calling
     * @param model Model to use (defaults to GPT-4o)
     * @return Flow of ResponseStreamEvent (native OpenAI SDK type)
     */
    fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_4O
    ): Flow<ResponseStreamEvent> = callbackFlow {
        Log.d(TAG, "Starting native streaming chat with ${inputItems.size} input items")
        
        var lastException: Exception? = null
        var backoffMs = INITIAL_BACKOFF_MS
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                // Build request params
                val builder = ResponseCreateParams.builder()
                    .model(model)
                    .instructions(systemPrompt)
                    .input(ResponseCreateParams.Input.ofResponse(inputItems))
                
                // Add tools
                tools.forEach { tool ->
                    builder.addTool(tool)
                }
                
                val params = builder.build()
                
                Log.d(TAG, "Making streaming Responses API call to OpenAI (attempt $attempt)...")
                
                // Use native streaming on IO dispatcher
                withContext(Dispatchers.IO) {
                    client.responses().createStreaming(params).use { streamResponse ->
                        streamResponse.stream().forEach { event ->
                            // trySend works from any context in callbackFlow
                            trySend(event)
                        }
                    }
                }
                
                Log.d(TAG, "Streaming completed successfully")
                close() // Signal completion
                return@callbackFlow
                
            } catch (e: Exception) {
                val message = e.message ?: ""
                val cause = e.cause?.message ?: ""
                
                // Check if rate limited
                if (message.contains("429") || cause.contains("429") || 
                    message.contains("rate limit", ignoreCase = true) ||
                    cause.contains("rate limit", ignoreCase = true)) {
                    
                    lastException = e
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for rate limit in streaming")
                        close(RateLimitException("Rate limited by OpenAI", extractRetryAfter(message) ?: extractRetryAfter(cause)))
                        return@callbackFlow
                    }
                    
                    val waitMs = extractRetryAfter(message) ?: extractRetryAfter(cause) ?: backoffMs
                    Log.w(TAG, "Rate limited (attempt $attempt/$MAX_RETRIES), waiting ${waitMs}ms...")
                    
                    delay(waitMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                
                // Check for transient errors
                if (e is java.net.SocketTimeoutException ||
                    message.contains("500") || message.contains("502") || 
                    message.contains("503") || message.contains("504")) {
                    
                    lastException = e
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for transient error in streaming")
                        close(e)
                        return@callbackFlow
                    }
                    
                    Log.w(TAG, "Transient error (attempt $attempt/$MAX_RETRIES): ${e.message}, retrying in ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                
                // Non-retryable error
                Log.e(TAG, "Streaming chat failed with non-retryable error", e)
                close(e)
                return@callbackFlow
            }
        }
        
        close(lastException ?: RuntimeException("Unexpected error in streaming retry loop"))
        
        awaitClose { 
            Log.d(TAG, "Streaming flow closed")
        }
    }
    
    /**
     * Create a ResponseAccumulator for building the final Response from streamed events.
     * Call this before streaming and pass events through accumulator.accumulate().
     */
    fun createResponseAccumulator(): ResponseAccumulator = ResponseAccumulator.create()
    
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
            
            val response = client.responses().create(builder.build())
            
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

/**
 * Result from the Responses API (non-streaming).
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

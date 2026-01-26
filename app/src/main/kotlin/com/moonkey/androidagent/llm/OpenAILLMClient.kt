package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * OpenAILLMClient - LLM client using OpenAI Responses API.
 * 
 * Features:
 * - Native function/tool calling via Responses API
 * - Automatic retry with exponential backoff on rate limits (429)
 * - Proper ResponseInputItem types for conversation history
 * - Native streaming support converted to LLMStreamEvent
 * 
 * This is the cloud-based implementation that connects to OpenAI's API.
 */
class OpenAILLMClient(apiKey: String) : LLMClient() {
    
    companion object {
        private const val TAG = "OpenAILLMClient"
    }

    private val client: OpenAIClient
    
    init {
        Log.d(TAG, "Creating OpenAILLMClient with key: ${apiKey.take(10)}...")
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
        Log.i(TAG, "OpenAILLMClient created successfully")
    }
    
    /**
     * Call the Responses API with tool/function calling support (non-streaming).
     * 
     * Uses proper ResponseInputItem types for conversation history,
     * which enables correct function call/output correlation.
     */
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
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
     * Streaming version of chatWithTools using the OpenAI SDK's streaming API.
     * 
     * Converts OpenAI's ResponseStreamEvent to our unified LLMStreamEvent.
     */
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<LLMStreamEvent> = callbackFlow {
        Log.d(TAG, "Starting native streaming chat with ${inputItems.size} input items")
        
        var lastException: Exception? = null
        var backoffMs = INITIAL_BACKOFF_MS
        var streamCompleted = false
        
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
                            // Convert ResponseStreamEvent to LLMStreamEvent
                            when {
                                event.isCreated() -> {
                                    val created = event.asCreated()
                                    trySend(LLMStreamEvent.Created(created.response().id()))
                                }
                                event.isOutputTextDelta() -> {
                                    val textDelta = event.asOutputTextDelta()
                                    trySend(LLMStreamEvent.TextDelta(textDelta.delta()))
                                }
                                event.isOutputItemDone() -> {
                                    val itemDone = event.asOutputItemDone()
                                    val item = itemDone.item()
                                    if (item.isFunctionCall()) {
                                        val funcCall = item.asFunctionCall()
                                        trySend(LLMStreamEvent.ToolCallDone(
                                            LLMToolCall(
                                                callId = funcCall.callId(),
                                                name = funcCall.name(),
                                                arguments = funcCall.arguments()
                                            )
                                        ))
                                    }
                                }
                                event.isCompleted() -> {
                                    trySend(LLMStreamEvent.Completed)
                                }
                                event.isFailed() -> {
                                    val failed = event.asFailed()
                                    trySend(LLMStreamEvent.Failed("Response failed: ${failed.response()}"))
                                }
                            }
                        }
                    }
                }
                
                Log.d(TAG, "Streaming completed successfully")
                streamCompleted = true
                break // Exit retry loop on success
                
            } catch (e: Exception) {
                val message = e.message ?: ""
                val cause = e.cause?.message ?: ""
                
                // Check if rate limited
                if (message.contains("429") || cause.contains("429") || 
                    message.contains("rate limit", ignoreCase = true) ||
                    cause.contains("rate limit", ignoreCase = true)) {
                    
                    lastException = RateLimitException(
                        "Rate limited by OpenAI", 
                        extractRetryAfter(message) ?: extractRetryAfter(cause)
                    )
                    
                    if (attempt == MAX_RETRIES) {
                        Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded for rate limit in streaming")
                        break // Exit loop, will close with lastException
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
                        break // Exit loop, will close with lastException
                    }
                    
                    Log.w(TAG, "Transient error (attempt $attempt/$MAX_RETRIES): ${e.message}, retrying in ${backoffMs}ms...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                
                // Non-retryable error
                Log.e(TAG, "Streaming chat failed with non-retryable error", e)
                lastException = e
                break // Exit loop, will close with lastException
            }
        }
        
        // Close the flow with appropriate result
        if (streamCompleted) {
            close()
        } else {
            close(lastException ?: RuntimeException("Stream completed with error flag but no error details"))
        }
        
        awaitClose { 
            Log.d(TAG, "Streaming flow closed")
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

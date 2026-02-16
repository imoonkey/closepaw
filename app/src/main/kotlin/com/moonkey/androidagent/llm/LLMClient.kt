package com.moonkey.androidagent.llm

import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow

/**
 * LLMClient - Abstract base class for LLM clients.
 * 
 * Defines the interface for interacting with LLMs (both cloud and local).
 * Uses OpenAI Responses API types (ResponseInputItem, FunctionTool) as input
 * to minimize changes to callers (Turn.kt, ToolRegistry.kt).
 * 
 * Implementations:
 * - OpenAIResponseClient: Cloud-based using OpenAI Responses API
 * - LFMLLMClient: Local inference using LiquidAI Leap SDK
 * 
 * Note: We reuse OpenAI types for input but use our own LLMStreamEvent for
 * streaming output, since OpenAI's ResponseStreamEvent cannot be constructed
 * outside the SDK.
 */
abstract class LLMClient {
    
    companion object {
        const val TAG = "LLMClient"
        const val DEFAULT_MODEL = "gpt-5.2"
        
        // Rate limit configuration (shared defaults)
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 60000L
        const val BACKOFF_MULTIPLIER = 2.0
    }
    
    /**
     * Call the LLM with tool/function calling support (non-streaming).
     * 
     * @param systemPrompt System/developer instructions
     * @param inputItems Conversation history as ResponseInputItem list
     * @param tools Tool definitions for function calling
     * @param model Model to use (ignored for local models)
     * @return ResponsesResult containing text output and/or tool calls
     */
    abstract suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String = DEFAULT_MODEL
    ): ResponsesResult
    
    /**
     * Streaming version of chatWithTools.
     * 
     * Returns a Flow of LLMStreamEvent items for real-time response processing.
     * The consumer can accumulate text deltas and tool calls as they arrive.
     * 
     * @param systemPrompt System/developer instructions
     * @param inputItems Conversation history as ResponseInputItem list
     * @param tools Tool definitions for function calling
     * @param model Model to use (ignored for local models)
     * @return Flow of LLMStreamEvent
     */
    abstract fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String = DEFAULT_MODEL
    ): Flow<LLMStreamEvent>
    
    /**
     * Check if the client is ready to process requests.
     * 
     * For cloud clients, this is typically always true.
     * For local clients, this may be false until the model is loaded.
     */
    open fun isReady(): Boolean = true
    
    /**
     * Cleanup resources held by the client.
     * 
     * Called when the session is ending.
     */
    open suspend fun cleanup() {}
}

/**
 * Unified streaming events for LLM responses.
 * 
 * Both OpenAI and local LLM clients emit these events during streaming.
 * This abstraction is necessary because OpenAI's ResponseStreamEvent cannot
 * be constructed outside the SDK.
 */
sealed interface LLMStreamEvent {
    /** Response creation started, contains response ID */
    data class Created(val responseId: String) : LLMStreamEvent
    
    /** Incremental text delta */
    data class TextDelta(val delta: String) : LLMStreamEvent
    
    /** A complete tool call has been received */
    data class ToolCallDone(val toolCall: LLMToolCall) : LLMStreamEvent
    
    /** Response completed successfully */
    data object Completed : LLMStreamEvent
    
    /** Response failed */
    data class Failed(val error: String) : LLMStreamEvent
}

/**
 * Result from an LLM call (non-streaming).
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
 * A tool call from the LLM.
 */
data class LLMToolCall(
    /** The call ID - use this for tool result correlation */
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

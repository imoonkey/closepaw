package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * FallbackLLMClient - LLM client with automatic fallback behavior.
 * 
 * Tries the primary client first (typically local LLM), and falls back
 * to the secondary client (typically OpenAI) if the primary fails.
 * 
 * ## Use Cases
 * 
 * 1. **Offline-first with cloud backup**: Use local LLM when available,
 *    fall back to cloud API when local fails (model not loaded, OOM, etc.)
 * 
 * 2. **Cost optimization**: Try free local inference first, only use
 *    paid API when necessary.
 * 
 * 3. **Reliability**: Combine multiple backends for higher availability.
 * 
 * ## Fallback Behavior
 * 
 * - For non-streaming: If primary throws an exception, tries fallback
 * - For streaming: If primary emits Failed event, retries with fallback
 * - Logs fallback attempts for debugging
 * 
 * Note: Fallback only triggers on errors, not on low-quality responses.
 * Quality-based routing is out of scope for this implementation.
 */
class FallbackLLMClient(
    private val primary: LLMClient,
    private val fallback: LLMClient
) : LLMClient {
    
    companion object {
        private const val TAG = "FallbackLLMClient"
    }
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        return try {
            Log.d(TAG, "Trying primary LLM client...")
            primary.chatWithTools(systemPrompt, inputItems, tools, model)
        } catch (e: Exception) {
            Log.w(TAG, "Primary LLM failed, falling back: ${e.message}")
            try {
                Log.d(TAG, "Trying fallback LLM client...")
                fallback.chatWithTools(systemPrompt, inputItems, tools, model)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback LLM also failed", fallbackError)
                // Throw the fallback error, but include primary error context
                throw RuntimeException(
                    "Both primary and fallback LLM clients failed. " +
                    "Primary: ${e.message}, Fallback: ${fallbackError.message}",
                    fallbackError
                )
            }
        }
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<LLMStreamEvent> = flow {
        var usedFallback = false
        var primaryFailed = false
        var primaryError: LLMStreamEvent.Failed? = null
        
        Log.d(TAG, "Starting streaming with primary LLM client...")
        
        // Try to collect from primary
        try {
            primary.chatWithToolsStreaming(systemPrompt, inputItems, tools, model)
                .onEach { event ->
                    // Check if primary emitted a Failed event
                    if (event is LLMStreamEvent.Failed) {
                        primaryFailed = true
                        primaryError = event
                        Log.w(TAG, "Primary LLM emitted failure: ${event.error}")
                    }
                }
                .catch { e ->
                    // Primary threw an exception
                    primaryFailed = true
                    primaryError = LLMStreamEvent.Failed(e.message ?: "Unknown error", e)
                    Log.w(TAG, "Primary LLM threw exception: ${e.message}")
                }
                .collect { event ->
                    if (!primaryFailed) {
                        emit(event)
                    }
                }
        } catch (e: Exception) {
            // Exception during collect
            primaryFailed = true
            primaryError = LLMStreamEvent.Failed(e.message ?: "Unknown error", e)
            Log.w(TAG, "Primary LLM failed during collection: ${e.message}")
        }
        
        // If primary failed, try fallback
        if (primaryFailed && primaryError != null) {
            Log.d(TAG, "Starting streaming with fallback LLM client...")
            usedFallback = true
            
            try {
                fallback.chatWithToolsStreaming(systemPrompt, inputItems, tools, model)
                    .collect { event ->
                        emit(event)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback LLM also failed", e)
                // Emit failure with both errors
                emit(LLMStreamEvent.Failed(
                    "Both primary and fallback LLM clients failed. " +
                    "Primary: ${primaryError?.error}, Fallback: ${e.message}",
                    e
                ))
            }
        }
        
        if (usedFallback) {
            Log.i(TAG, "Completed using fallback LLM client")
        }
    }
}

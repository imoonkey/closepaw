package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.tool.ToolRegistry
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject


/**
 * Turn - Encapsulates a single ReAct iteration (LLM call + response parsing).
 * 
 * Reference: labmat's Turn class (turn.py)
 * 
 * A Turn handles:
 * 1. Building input items from history + current context
 * 2. Calling the LLM with tools via the Responses API
 * 3. Processing the structured response (text and/or tool calls)
 * 
 * Supports both streaming and non-streaming modes:
 * - `run()`: Non-streaming, returns complete TurnResult
 * - `runStreaming()`: Streaming, emits TurnStreamEvent as they arrive
 * 
 * Uses OpenAI's official tool calling interface via the Responses API.
 */
class Turn(
    private val historyManager: HistoryManager,
    private val toolRegistry: ToolRegistry,
    private val llmClient: LLMClient,
    private val allowedToolNames: Set<String>? = null
) {
    companion object {
        private const val TAG = "Turn"
        private const val COMPLETE_TASK_TOOL = "complete_task"
    }

    private val inputBuilder = TurnInputBuilder(historyManager)

    fun buildInputItems(userContext: AgentPromptBuilder.UserContext): List<ResponseInputItem> {
        return inputBuilder.build(userContext)
    }
    
    /**
     * Execute one turn of the ReAct loop (non-streaming).
     * 
     * @param systemPrompt System prompt for the agent
     * @param userContext Current context (screen state, goal, etc.)
     * @param modelName Model name to use
     * @return TurnResult with content and/or tool calls
     */
    suspend fun run(
        systemPrompt: String,
        userContext: AgentPromptBuilder.UserContext,
        modelName: String = "gpt-5.2",
        inputItemsOverride: List<ResponseInputItem>? = null
    ): TurnResult {
        // 1. Build input items from history using proper ResponseInputItem types
        val inputItems = inputItemsOverride ?: inputBuilder.build(userContext)
        
        Log.d(TAG, "Running turn with ${inputItems.size} input items, model=$modelName")
        
        // 2. Get tools from registry
        val tools = toolRegistry.generateResponsesApiTools { spec ->
            allowedToolNames?.contains(spec.name) != false
        }
        Log.d(TAG, "Using ${tools.size} tools: ${tools.map { it.name() }}")

        // 3. Convert model name to ChatModel enum
        val chatModel = modelNameToChatModel(modelName)

        // 4. Call LLM via Responses API
        val response = llmClient.chatWithTools(
            systemPrompt = systemPrompt,
            inputItems = inputItems,
            tools = tools,
            model = chatModel
        )
        
        Log.d(TAG, "LLM response: text=${response.textContent?.take(200)}, toolCalls=${response.toolCalls.size}")
        
        // 6. Process response
        return processResponse(response.textContent, response.toolCalls)
    }
    
    /**
     * Execute one turn of the ReAct loop with streaming.
     * 
     * Uses OpenAI's native streaming via ResponseStreamEvent.
     * Emits TurnStreamEvent as the response is generated:
     * - TextDelta: Incremental text from the LLM
     * - ToolCallReceived: A complete tool call has been parsed
     * - Complete: Stream finished, final TurnResult available
     * - Error: An error occurred
     * 
     * @param systemPrompt System prompt for the agent
     * @param userContext Current context (screen state, goal, etc.)
     * @param modelName Model name to use
     * @return Flow of TurnStreamEvent
     */
    fun runStreaming(
        systemPrompt: String,
        userContext: AgentPromptBuilder.UserContext,
        modelName: String = "gpt-5.2",
        inputItemsOverride: List<ResponseInputItem>? = null
    ): Flow<TurnStreamEvent> = flow {
        Log.d(TAG, "Running streaming turn with LLM streaming, model=$modelName")
        
        try {
            // 1. Build input items
            val inputItems = inputItemsOverride ?: inputBuilder.build(userContext)
            Log.d(TAG, "Streaming turn with ${inputItems.size} input items")
            
            // 2. Get tools
            val tools = toolRegistry.generateResponsesApiTools { spec ->
                allowedToolNames?.contains(spec.name) != false
            }

            // 3. Convert model name
            val chatModel = modelNameToChatModel(modelName)

            // 4. Accumulate text and tool calls locally for building final result
            val textAccumulator = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()

            // 5. Stream response using LLMStreamEvent (works with both OpenAI and local models)
            llmClient.chatWithToolsStreaming(
                systemPrompt = systemPrompt,
                inputItems = inputItems,
                tools = tools,
                model = chatModel
            ).collect { event ->
                when (event) {
                    is LLMStreamEvent.Created -> {
                        Log.d(TAG, "Response created with ID: ${event.responseId}")
                    }
                    
                    is LLMStreamEvent.TextDelta -> {
                        textAccumulator.append(event.delta)
                        emit(TurnStreamEvent.TextDelta(event.delta))
                    }
                    
                    is LLMStreamEvent.ToolCallDone -> {
                        val llmToolCall = event.toolCall
                        toolCalls.add(llmToolCall)
                        
                        Log.d(TAG, "Received tool call: ${llmToolCall.name} with id ${llmToolCall.callId}")
                        
                        // Convert to ToolCallRequest and emit
                        val toolCallRequest = convertToToolCallRequest(llmToolCall)
                        emit(TurnStreamEvent.ToolCallReceived(toolCallRequest))
                    }
                    
                    is LLMStreamEvent.Completed -> {
                        Log.d(TAG, "Response completed, building final result")
                    }
                    
                    is LLMStreamEvent.Failed -> {
                        Log.e(TAG, event.error)
                        throw RuntimeException(event.error)
                    }
                }
            }
            
            // 6. Build final result from accumulated data
            val textContent = textAccumulator.toString().takeIf { it.isNotEmpty() }
            val result = processResponse(textContent, toolCalls)
            
            Log.d(TAG, "Streaming turn complete: text=${textContent?.take(100)}..., toolCalls=${toolCalls.size}")
            emit(TurnStreamEvent.Complete(result))
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming turn failed", e)
            emit(TurnStreamEvent.Error(e))
        }
    }
    
    /**
     * Convert LLMToolCall to ToolCallRequest.
     */
    private fun convertToToolCallRequest(llmToolCall: LLMToolCall): ToolCallRequest {
        val argsJson = try {
            JSONObject(llmToolCall.arguments)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool arguments as JSON: ${llmToolCall.arguments}", e)
            JSONObject()
        }
        
        return ToolCallRequest(
            id = llmToolCall.callId,
            name = llmToolCall.name,
            arguments = argsJson
        )
    }
    
    /**
     * Convert model name string to ChatModel enum.
     * Falls back to GPT_5_2 if model name is not recognized.
     */
    private fun modelNameToChatModel(modelName: String): ChatModel {
        return when (modelName.lowercase()) {
            "gpt-5.2" -> ChatModel.GPT_5_2
            "gpt-5.2-pro" -> ChatModel.GPT_5_2_PRO
            "gpt-5.2-chat-latest" -> ChatModel.GPT_5_2_CHAT_LATEST
            "gpt-4o" -> ChatModel.GPT_4O
            "gpt-4o-mini" -> ChatModel.GPT_4O_MINI
            "gpt-4-turbo" -> ChatModel.GPT_4_TURBO
            "gpt-4" -> ChatModel.GPT_4
            "gpt-3.5-turbo" -> ChatModel.GPT_3_5_TURBO
            "o1" -> ChatModel.O1
            "o1-mini" -> ChatModel.O1_MINI
            "o1-preview" -> ChatModel.O1_PREVIEW
            else -> {
                Log.w(TAG, "Unknown model name '$modelName', falling back to GPT_5_2")
                ChatModel.GPT_5_2
            }
        }
    }
    
    /**
     * Process the LLM response into a TurnResult.
     */
    private fun processResponse(
        textContent: String?,
        llmToolCalls: List<LLMToolCall>
    ): TurnResult {
        // Convert LLM tool calls to our format
        val allToolCalls = llmToolCalls.map { call ->
            convertToToolCallRequest(call)
        }
        val toolCalls = allToolCalls.filter { call ->
            allowedToolNames?.contains(call.name) != false
        }
        if (toolCalls.size != allToolCalls.size) {
            val acceptedNames = toolCalls.map { it.name }.toSet()
            val dropped = allToolCalls.map { it.name }.filter { name -> name !in acceptedNames }
            Log.w(TAG, "Dropped disallowed tool calls: $dropped")
        }
        
        // Check for completion:
        // 1. complete_task tool was called, OR
        // 2. No tool calls and there's text content (agent is done, just without using the tool)
        val completeTaskCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val isComplete = completeTaskCall != null || (toolCalls.isEmpty() && textContent != null)
        
        Log.d(TAG, "Process result: ${toolCalls.size} tool calls, isComplete=$isComplete")
        
        return TurnResult(
            content = textContent,
            toolCalls = toolCalls,
            isComplete = isComplete,
            parseErrors = null  // No parsing errors with Responses API
        )
    }
}

/**
 * Events emitted during a streaming turn.
 */
sealed interface TurnStreamEvent {
    /** Incremental text delta from the LLM */
    data class TextDelta(val text: String) : TurnStreamEvent
    
    /** A complete tool call has been received */
    data class ToolCallReceived(val toolCall: ToolCallRequest) : TurnStreamEvent
    
    /** Stream completed, final result available */
    data class Complete(val result: TurnResult) : TurnStreamEvent
    
    /** An error occurred during the turn */
    data class Error(val error: Throwable) : TurnStreamEvent
}

/**
 * Result of a Turn execution.
 */
data class TurnResult(
    /** Text content from the LLM */
    val content: String?,
    
    /** Tool calls requested by the LLM */
    val toolCalls: List<ToolCallRequest>,
    
    /** Whether the agent considers the task complete */
    val isComplete: Boolean,
    
    /** Any errors encountered while parsing tool calls (for debugging) */
    val parseErrors: List<String>? = null
)

/**
 * A tool call request from the LLM.
 * 
 * The id is provided by OpenAI's Responses API (call_id).
 */
data class ToolCallRequest(
    /** The call ID from OpenAI - use this for tool result correlation */
    val id: String,
    val name: String,
    val arguments: JSONObject
)

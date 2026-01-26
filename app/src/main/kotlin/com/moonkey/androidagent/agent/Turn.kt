package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.tool.ToolRegistry
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.EasyInputMessage
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
    private val llmClient: LLMClient
) {
    companion object {
        private const val TAG = "Turn"
        private const val COMPLETE_TASK_TOOL = "complete_task"
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
        userContext: String,
        modelName: String = "gpt-4o"
    ): TurnResult {
        // 1. Build input items from history using proper ResponseInputItem types
        val inputItems = buildInputItems(userContext)
        
        Log.d(TAG, "Running turn with ${inputItems.size} input items, model=$modelName")
        
        // 2. Get tools from registry
        val tools = toolRegistry.generateResponsesApiTools()
        Log.d(TAG, "Using ${tools.size} tools: ${tools.map { it.name() }}")
        
        // 3. Build full system prompt with agent instructions
        val fullSystemPrompt = buildSystemPrompt(systemPrompt)
        
        // 4. Convert model name to ChatModel enum
        val chatModel = modelNameToChatModel(modelName)
        
        // 5. Call LLM via Responses API
        val response = llmClient.chatWithTools(
            systemPrompt = fullSystemPrompt,
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
        userContext: String,
        modelName: String = "gpt-4o"
    ): Flow<TurnStreamEvent> = flow {
        Log.d(TAG, "Running streaming turn with LLM streaming, model=$modelName")
        
        try {
            // 1. Build input items
            val inputItems = buildInputItems(userContext)
            Log.d(TAG, "Streaming turn with ${inputItems.size} input items")
            
            // 2. Get tools
            val tools = toolRegistry.generateResponsesApiTools()
            
            // 3. Build system prompt
            val fullSystemPrompt = buildSystemPrompt(systemPrompt)
            
            // 4. Convert model name
            val chatModel = modelNameToChatModel(modelName)
            
            // 5. Accumulate text and tool calls locally for building final result
            val textAccumulator = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()
            
            // 6. Stream response using LLMStreamEvent (works with both OpenAI and local models)
            llmClient.chatWithToolsStreaming(
                systemPrompt = fullSystemPrompt,
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
            
            // 7. Build final result from accumulated data
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
     * Falls back to GPT_4O if model name is not recognized.
     */
    private fun modelNameToChatModel(modelName: String): ChatModel {
        return when (modelName.lowercase()) {
            "gpt-4o" -> ChatModel.GPT_4O
            "gpt-4o-mini" -> ChatModel.GPT_4O_MINI
            "gpt-4-turbo" -> ChatModel.GPT_4_TURBO
            "gpt-4" -> ChatModel.GPT_4
            "gpt-3.5-turbo" -> ChatModel.GPT_3_5_TURBO
            "o1" -> ChatModel.O1
            "o1-mini" -> ChatModel.O1_MINI
            "o1-preview" -> ChatModel.O1_PREVIEW
            else -> {
                Log.w(TAG, "Unknown model name '$modelName', falling back to GPT_4O")
                ChatModel.GPT_4O
            }
        }
    }
    
    /**
     * Build system prompt with agent behavior instructions.
     * 
     * Note: Tool descriptions are provided to the model via the tools parameter,
     * not in the system prompt, which is the recommended approach.
     * 
     * Optimized for both large (GPT-4) and smaller local models (1-2B params).
     */
    private fun buildSystemPrompt(basePrompt: String): String {
        return """
            $basePrompt
            
            ## CRITICAL RULES
            
            1. EXECUTE ONE ACTION PER TURN. Call mobile_action or app_control, then STOP and wait.
            2. NEVER call complete_task together with other actions in the same turn.
            3. Only call complete_task in the next turn AFTER you see the result of your action has achived user's goal
            
            ## Element Selection
            
            - Use the "index" field to identify elements (e.g., element_index=5)
            - Only click elements with "clickable": true
            - Only type in elements with "editable": true
            
            ## ReAct Loop
            
            Each turn:
            1. OBSERVE: Read the screen state JSON
            2. THINK: Identify what action to take
            3. ACT: Call ONE tool (mobile_action, app_control)
            4. WAIT: You will receive the new screen state in the next turn
            
            ## Completion
            
            Call complete_task ONLY when:
            - You see the target screen/content after your action succeeded
            - You have verified the goal is achieved by checking the screen state
            - NEVER call complete_task before executing and verifying an action

        """.trimIndent()
    }
    
    /**
     * Build input items from history and current context using proper ResponseInputItem types.
     * 
     * Uses:
     * - EasyInputMessage for user and assistant text messages (simpler interface)
     * - ResponseInputItem.ofFunctionCall() for function call requests  
     * - ResponseInputItem.ofFunctionCallOutput() for function call results
     * 
     * Best practice: When manually managing conversation history (not using previous_response_id),
     * all messages including assistant responses must be included to maintain full context.
     * See: https://platform.openai.com/docs/guides/conversation-state
     */
    private fun buildInputItems(userContext: String): List<ResponseInputItem> {
        // Compress history if approaching token limit (to avoid OpenAI's 30K TPM limit)
        val estimatedTokens = historyManager.estimateTokenCount()
        if (estimatedTokens > 20_000) {
            Log.w(TAG, "History approaching token limit ($estimatedTokens tokens), compressing...")
            historyManager.compress(15_000)  // Compress aggressively to leave room for response
            Log.d(TAG, "After compression: ${historyManager.estimateTokenCount()} tokens")
        }
        
        val items = mutableListOf<ResponseInputItem>()
        
        // Convert history items to proper ResponseInputItem types
        historyManager.forPrompt().forEach { item ->
            when (item) {
                is ResponseItem.Message -> {
                    // Use EasyInputMessage for simpler user/assistant message handling
                    val role = when (item.role) {
                        "user" -> EasyInputMessage.Role.USER
                        "assistant" -> EasyInputMessage.Role.ASSISTANT
                        else -> null  // Skip system messages (handled via instructions parameter)
                    }
                    if (role != null) {
                        items.add(
                            ResponseInputItem.ofEasyInputMessage(
                                EasyInputMessage.builder()
                                    .role(role)
                                    .content(item.content)
                                    .build()
                            )
                        )
                    }
                }
                is ResponseItem.FunctionCall -> {
                    // Create a function call input item
                    items.add(
                        ResponseInputItem.ofFunctionCall(
                            ResponseFunctionToolCall.builder()
                                .callId(item.id)
                                .name(item.name)
                                .arguments(item.arguments.toString())
                                .build()
                        )
                    )
                }
                is ResponseItem.FunctionCallOutput -> {
                    // Create a function call output item
                    items.add(
                        ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                .callId(item.callId)
                                .output(item.content)
                                .build()
                        )
                    )
                }
                else -> {} // Skip ghost snapshots
            }
        }
        
        // Add current context as user message using EasyInputMessage
        items.add(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content(userContext)
                    .build()
            )
        )
        
        return items
    }
    
    /**
     * Process the LLM response into a TurnResult.
     */
    private fun processResponse(
        textContent: String?,
        llmToolCalls: List<LLMToolCall>
    ): TurnResult {
        // Convert LLM tool calls to our format
        val toolCalls = llmToolCalls.map { call ->
            convertToToolCallRequest(call)
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

package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.tool.ToolRegistry
import com.openai.models.ChatModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
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

    fun buildInputItems(userContext: AgentPromptBuilder.UserContext): List<ResponseInputItem> {
        val estimatedTokens = historyManager.estimateTokenCount()
        if (estimatedTokens > 20_000) {
            Log.w(TAG, "History approaching token limit ($estimatedTokens tokens), compressing...")
            historyManager.compress(15_000)
            Log.d(TAG, "After compression: ${historyManager.estimateTokenCount()} tokens")
        }

        val items = mutableListOf<ResponseInputItem>()
        historyManager.forPrompt().forEach { item ->
            when (item) {
                is ResponseItem.Message -> {
                    val role =
                        when (item.role) {
                            "user" -> EasyInputMessage.Role.USER
                            "assistant" -> EasyInputMessage.Role.ASSISTANT
                            else -> null
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
                    items.add(
                        ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                .callId(item.callId)
                                .output(item.content)
                                .build()
                        )
                    )
                }
            }
        }
        items.add(buildUserContextItem(userContext))
        return items
    }

    private data class TurnRequest(
        val inputItems: List<ResponseInputItem>,
        val tools: List<FunctionTool>,
        val model: ChatModel
    )
    
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
        val request = prepareRequest(userContext, modelName, inputItemsOverride)
        Log.d(TAG, "Running turn with ${request.inputItems.size} input items, model=$modelName")
        Log.d(TAG, "Using ${request.tools.size} tools: ${request.tools.map { it.name() }}")

        val response = llmClient.chatWithTools(
            systemPrompt = systemPrompt,
            inputItems = request.inputItems,
            tools = request.tools,
            model = request.model
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
            val request = prepareRequest(userContext, modelName, inputItemsOverride)
            Log.d(TAG, "Streaming turn with ${request.inputItems.size} input items")

            // 4. Accumulate text and tool calls locally for building final result
            val textAccumulator = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()

            // 5. Stream response using LLMStreamEvent (works with both OpenAI and local models)
            llmClient.chatWithToolsStreaming(
                systemPrompt = systemPrompt,
                inputItems = request.inputItems,
                tools = request.tools,
                model = request.model
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

    private fun prepareRequest(
        userContext: AgentPromptBuilder.UserContext,
        modelName: String,
        inputItemsOverride: List<ResponseInputItem>?
    ): TurnRequest {
        val inputItems = inputItemsOverride ?: buildInputItems(userContext)
        val tools = toolRegistry.generateResponsesApiTools { spec ->
            allowedToolNames?.contains(spec.name) != false
        }
        val model = modelNameToChatModel(modelName)
        return TurnRequest(
            inputItems = inputItems,
            tools = tools,
            model = model
        )
    }

    private fun buildUserContextItem(userContext: AgentPromptBuilder.UserContext): ResponseInputItem {
        val builder =
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)

        val image = userContext.image
        if (image == null) {
            builder.content(userContext.text)
        } else {
            val contentItems =
                listOf(
                    ResponseInputContent.ofInputText(
                        ResponseInputText.builder()
                            .text(userContext.text)
                            .build()
                    ),
                    ResponseInputContent.ofInputImage(
                        ResponseInputImage.builder()
                            .detail(ResponseInputImage.Detail.AUTO)
                            .imageUrl(image.toDataUrl())
                            .build()
                    )
                )
            builder.contentOfResponseInputMessageContentList(contentItems)
        }
        return ResponseInputItem.ofEasyInputMessage(builder.build())
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
            isComplete = isComplete
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
    val isComplete: Boolean
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

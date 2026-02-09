package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.tool.ToolRegistry
import com.openai.models.ChatModel
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Encapsulates a single ReAct iteration: LLM call → response parsing.
 *
 * Pure LLM-calling wrapper. All input construction is handled by PromptBuilder;
 * Turn only cares about sending items to the model and interpreting the response.
 */
class Turn(
        private val toolRegistry: ToolRegistry,
        private val llmClient: LLMClient,
        private val allowedToolNames: Set<String>? = null
) {
    companion object {
        private const val TAG = "Turn"
        private const val COMPLETE_TASK_TOOL = "complete_task"
    }

    private data class TurnRequest(
            val inputItems: List<ResponseInputItem>,
            val tools: List<FunctionTool>,
            val model: ChatModel
    )

    suspend fun run(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            modelName: String = "gpt-5.2"
    ): TurnResult {
        val request = prepareRequest(inputItems, modelName)
        Log.d(TAG, "Running turn with ${request.inputItems.size} input items, model=$modelName")
        Log.d(TAG, "Using ${request.tools.size} tools: ${request.tools.map { it.name() }}")

        val response =
                llmClient.chatWithTools(
                        systemPrompt = systemPrompt,
                        inputItems = request.inputItems,
                        tools = request.tools,
                        model = request.model
                )

        Log.d(
                TAG,
                "LLM response: text=${response.textContent?.take(200)}, toolCalls=${response.toolCalls.size}"
        )
        return processResponse(response.textContent, response.toolCalls)
    }

    fun runStreaming(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            modelName: String = "gpt-5.2"
    ): Flow<TurnStreamEvent> = flow {
        Log.d(TAG, "Running streaming turn with LLM streaming, model=$modelName")

        try {
            val request = prepareRequest(inputItems, modelName)
            Log.d(TAG, "Streaming turn with ${request.inputItems.size} input items")

            val textAccumulator = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()

            llmClient.chatWithToolsStreaming(
                            systemPrompt = systemPrompt,
                            inputItems = request.inputItems,
                            tools = request.tools,
                            model = request.model
                    )
                    .collect { event ->
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

                                Log.d(
                                        TAG,
                                        "Received tool call: ${llmToolCall.name} with id ${llmToolCall.callId}"
                                )

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

            val textContent = textAccumulator.toString().takeIf { it.isNotEmpty() }
            val result = processResponse(textContent, toolCalls)

            Log.d(
                    TAG,
                    "Streaming turn complete: text=${textContent?.take(100)}..., toolCalls=${toolCalls.size}"
            )
            emit(TurnStreamEvent.Complete(result))
        } catch (e: Exception) {
            Log.e(TAG, "Streaming turn failed", e)
            emit(TurnStreamEvent.Error(e))
        }
    }

    private fun convertToToolCallRequest(llmToolCall: LLMToolCall): ToolCallRequest {
        val argsJson =
                try {
                    JSONObject(llmToolCall.arguments)
                } catch (e: Exception) {
                    Log.w(
                            TAG,
                            "Failed to parse tool arguments as JSON: ${llmToolCall.arguments}",
                            e
                    )
                    JSONObject()
                }

        return ToolCallRequest(
                id = llmToolCall.callId,
                name = llmToolCall.name,
                arguments = argsJson
        )
    }

    private fun prepareRequest(
            inputItems: List<ResponseInputItem>,
            modelName: String
    ): TurnRequest {
        val tools =
                toolRegistry.generateResponsesApiTools { spec ->
                    allowedToolNames?.contains(spec.name) != false
                }
        val model = modelNameToChatModel(modelName)
        return TurnRequest(inputItems = inputItems, tools = tools, model = model)
    }

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

    private fun processResponse(textContent: String?, llmToolCalls: List<LLMToolCall>): TurnResult {
        val allToolCalls = llmToolCalls.map { convertToToolCallRequest(it) }
        val toolCalls = allToolCalls.filter { allowedToolNames?.contains(it.name) != false }

        if (toolCalls.size != allToolCalls.size) {
            val acceptedNames = toolCalls.map { it.name }.toSet()
            val dropped = allToolCalls.map { it.name }.filter { it !in acceptedNames }
            Log.w(TAG, "Dropped disallowed tool calls: $dropped")
        }

        val completeTaskCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val isComplete = completeTaskCall != null || (toolCalls.isEmpty() && textContent != null)

        Log.d(TAG, "Process result: ${toolCalls.size} tool calls, isComplete=$isComplete")

        return TurnResult(content = textContent, toolCalls = toolCalls, isComplete = isComplete)
    }
}

sealed interface TurnStreamEvent {
    data class TextDelta(val text: String) : TurnStreamEvent
    data class ToolCallReceived(val toolCall: ToolCallRequest) : TurnStreamEvent
    data class Complete(val result: TurnResult) : TurnStreamEvent
    data class Error(val error: Throwable) : TurnStreamEvent
}

data class TurnResult(
        val content: String?,
        val toolCalls: List<ToolCallRequest>,
        val isComplete: Boolean
)

data class ToolCallRequest(val id: String, val name: String, val arguments: JSONObject)

package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.tool.ToolRegistry
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import java.util.UUID
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
            val model: String
    )

    suspend fun run(
            systemPrompt: String,
            inputItems: List<ResponseInputItem>,
            model: String = "gpt-5.2"
    ): TurnResult {
        val request = prepareRequest(inputItems, model)
        Log.d(TAG, "Running turn with ${request.inputItems.size} input items, model=$model")
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
            model: String = "gpt-5.2"
    ): Flow<TurnStreamEvent> = flow {
        Log.d(TAG, "Running streaming turn with LLM streaming, model=$model")

        try {
            val request = prepareRequest(inputItems, model)
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
            model: String
    ): TurnRequest {
        val tools =
                toolRegistry.generateResponsesApiTools { spec ->
                    allowedToolNames?.contains(spec.name) != false
                }
        return TurnRequest(inputItems = inputItems, tools = tools, model = model)
    }

    private fun processResponse(textContent: String?, llmToolCalls: List<LLMToolCall>): TurnResult {
        val parsedToolCalls =
                llmToolCalls.mapIndexed { index, llmToolCall ->
                    val converted = convertToToolCallRequest(llmToolCall)
                    if (converted.id.isBlank()) {
                        val syntheticId =
                                "synthetic_${llmToolCall.name}_${index}_${UUID.randomUUID()}"
                        converted.copy(id = syntheticId)
                    } else {
                        converted
                    }
                }
        val recoveredToolCall =
                if (parsedToolCalls.isEmpty()) recoverToolCallFromText(textContent) else null
        val allToolCalls = recoveredToolCall?.let { listOf(it) } ?: parsedToolCalls
        val toolCalls = allToolCalls.filter { allowedToolNames?.contains(it.name) != false }
        val recoveredAccepted = recoveredToolCall != null && toolCalls.any { it.id == recoveredToolCall.id }
        val effectiveTextContent = if (recoveredAccepted) null else textContent

        if (toolCalls.size != allToolCalls.size) {
            val acceptedNames = toolCalls.map { it.name }.toSet()
            val dropped = allToolCalls.map { it.name }.toSet() - acceptedNames
            Log.w(TAG, "Dropped disallowed tool calls: $dropped")
        }

        val completeTaskCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val isComplete = completeTaskCall != null || (toolCalls.isEmpty() && effectiveTextContent != null)

        Log.d(TAG, "Process result: ${toolCalls.size} tool calls, isComplete=$isComplete")

        return TurnResult(content = effectiveTextContent, toolCalls = toolCalls, isComplete = isComplete)
    }

    private fun recoverToolCallFromText(textContent: String?): ToolCallRequest? {
        val candidate = textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val compact = stripMarkdownCodeFence(candidate)

        parseObjectWrappedToolCall(compact)?.let { recovered ->
            Log.w(TAG, "Recovered tool call from text payload: ${recovered.name}")
            return recovered
        }

        val inlinePattern = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)\s*(\{[\s\S]*\})$""")
        val inlineMatch = inlinePattern.matchEntire(compact) ?: return null
        val toolName = inlineMatch.groupValues[1]
        val argsRaw = inlineMatch.groupValues[2]
        return try {
            val args = JSONObject(argsRaw)
            val syntheticId = "synthetic_${toolName}_text_${UUID.randomUUID()}"
            Log.w(TAG, "Recovered inline tool call from text payload: $toolName")
            ToolCallRequest(
                    id = syntheticId,
                    name = toolName,
                    arguments = args
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseObjectWrappedToolCall(candidate: String): ToolCallRequest? {
        val payload =
                try {
                    JSONObject(candidate)
                } catch (_: Exception) {
                    return null
                }
        val toolName =
                payload.optString("name")
                        .ifBlank { payload.optString("tool_name") }
                        .ifBlank { return null }
        val argumentsValue = payload.opt("arguments") ?: payload.opt("args")
        val arguments =
                when (argumentsValue) {
                    is JSONObject -> argumentsValue
                    is String ->
                            try {
                                JSONObject(argumentsValue)
                            } catch (_: Exception) {
                                JSONObject()
                            }
                    else -> JSONObject()
                }
        return ToolCallRequest(
                id = "synthetic_${toolName}_text_${UUID.randomUUID()}",
                name = toolName,
                arguments = arguments
        )
    }

    private fun stripMarkdownCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed.removePrefix("```").removeSuffix("```").trim()
        val withoutHeader = trimmed.substring(firstNewline + 1)
        return withoutHeader.removeSuffix("```").trim()
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

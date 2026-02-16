package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * LLM client using OpenAI Chat Completions API.
 *
 * Works with any OpenAI-compatible endpoint (OpenRouter, vLLM, etc.)
 * by setting [baseUrl] and [apiKey]. Accepts the same ResponseInputItem /
 * FunctionTool types as the callers produce and converts them internally
 * to Chat Completions types via [ChatCompletionInterop].
 *
 * Non-streaming: client.chat().completions().create()
 * Streaming:     client.chat().completions().createStreaming()
 */
class ChatCompletionClient(
    apiKey: String,
    baseUrl: String? = null
) : LLMClient() {

    companion object {
        private const val TAG = "ChatCompletionClient"
    }

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { baseUrl?.let { baseUrl(it) } }
        .build()

    // ── Non-streaming ───────────────────────────────────────────────────

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult = withContext(Dispatchers.IO) {
        CloudLlmRetry.executeWithRetry(
                tag = TAG,
                operationName = "chat-completions chatWithTools"
        ) {
            executeChatWithTools(systemPrompt, inputItems, tools, model)
        }
    }

    private fun executeChatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult {
        Log.d(TAG, "Calling Chat Completions API with ${inputItems.size} input items, ${tools.size} tools")
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        try {
            val params = buildParams(systemPrompt, inputItems, tools, model)
            val response = client.chat().completions().create(params)

            val choice = response.choices().firstOrNull()
                ?: return ResponsesResult(textContent = null, toolCalls = emptyList(), responseId = response.id())

            val message = choice.message()
            val textContent = message.content().orElse(null)
            val toolCalls = message.toolCalls().orElse(emptyList())
                .filter { it.isFunction() }
                .map { tc ->
                    val func = tc.asFunction()
                    LLMToolCall(
                        callId = func.id(),
                        name = func.function().name(),
                        arguments = func.function().arguments()
                    )
                }

            val result = ResponsesResult(
                textContent = textContent,
                toolCalls = toolCalls,
                responseId = response.id()
            )
            Log.d(TAG, "Chat API result: ${result.textContent?.take(200)}, ${result.toolCalls.size} tool calls")
            LlmLogger.logOutput(TAG, result)
            return result
        } catch (e: Exception) {
            throw OpenAIErrorClassifier.classify(e)
        }
    }

    // ── Streaming ───────────────────────────────────────────────────────

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = callbackFlow {
        Log.d(TAG, "Starting streaming Chat Completions with ${inputItems.size} input items")
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        var lastException: Exception? = null
        var backoffMs = INITIAL_BACKOFF_MS
        var streamCompleted = false
        var failureEmitted = false

        for (attempt in 1..MAX_RETRIES) {
            var emittedEvent = false
            val textAccumulator = StringBuilder()
            // Map: tool call index → (callId, name, argsBuilder)
            val toolCallBuilders = mutableMapOf<Long, Triple<String, String, StringBuilder>>()
            val completedToolCalls = mutableListOf<LLMToolCall>()
            var responseId: String? = null

            fun emit(event: LLMStreamEvent) {
                if (event is LLMStreamEvent.Failed) failureEmitted = true
                emittedEvent = true
                trySend(event)
            }

            try {
                val params = buildParams(systemPrompt, inputItems, tools, model)
                Log.d(TAG, "Making streaming Chat API call (attempt $attempt)")

                withContext(Dispatchers.IO) {
                    client.chat().completions().createStreaming(params).use { stream ->
                        stream.stream().forEach { chunk ->
                            if (responseId == null) {
                                responseId = chunk.id()
                                emit(LLMStreamEvent.Created(chunk.id()))
                            }

                            for (choice in chunk.choices()) {
                                val delta = choice.delta()

                                // Text content delta
                                delta.content().ifPresent { text ->
                                    if (text.isNotEmpty()) {
                                        textAccumulator.append(text)
                                        emit(LLMStreamEvent.TextDelta(text))
                                    }
                                }

                                // Tool call deltas (streamed incrementally)
                                delta.toolCalls().ifPresent { calls ->
                                    for (tcDelta in calls) {
                                        val idx = tcDelta.index()

                                        if (!toolCallBuilders.containsKey(idx)) {
                                            toolCallBuilders[idx] = Triple(
                                                tcDelta.id().orElse("call_$idx"),
                                                tcDelta.function().orElse(null)?.name()?.orElse("") ?: "",
                                                StringBuilder()
                                            )
                                        } else {
                                            // Update id/name if provided in a later delta
                                            tcDelta.id().ifPresent { id ->
                                                val (curId, name, args) = toolCallBuilders[idx]!!
                                                if (curId.startsWith("call_")) {
                                                    toolCallBuilders[idx] = Triple(id, name, args)
                                                }
                                            }
                                            tcDelta.function().orElse(null)?.name()?.ifPresent { name ->
                                                val (curId, curName, args) = toolCallBuilders[idx]!!
                                                if (curName.isEmpty()) {
                                                    toolCallBuilders[idx] = Triple(curId, name, args)
                                                }
                                            }
                                        }

                                        tcDelta.function().ifPresent { func ->
                                            func.arguments().ifPresent { argFragment ->
                                                toolCallBuilders[idx]?.let { (_, _, argsBuilder) ->
                                                    argsBuilder.append(argFragment)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Emit completed tool calls when finish reason received
                                choice.finishReason().ifPresent { _ ->
                                    for ((_, builder) in toolCallBuilders) {
                                        val (callId, name, args) = builder
                                        val toolCall = LLMToolCall(
                                            callId = callId,
                                            name = name,
                                            arguments = args.toString()
                                        )
                                        completedToolCalls.add(toolCall)
                                        emit(LLMStreamEvent.ToolCallDone(toolCall))
                                    }
                                    toolCallBuilders.clear()
                                }
                            }
                        }
                    }
                }

                // Stream ended — emit completion
                LlmLogger.logOutput(
                    TAG,
                    ResponsesResult(
                        textContent = textAccumulator.toString().takeIf { it.isNotEmpty() },
                        toolCalls = completedToolCalls,
                        responseId = responseId ?: "unknown"
                    )
                )
                emit(LLMStreamEvent.Completed)
                streamCompleted = true
                break

            } catch (e: Exception) {
                val classified = OpenAIErrorClassifier.classify(e)
                val retryable = classified is RateLimitException || classified is TransientException
                lastException = classified

                if (retryable && emittedEvent) {
                    Log.w(TAG, "Stream error after output; skipping retry: ${classified.message}")
                    emit(LLMStreamEvent.Failed("Stream interrupted: ${classified.message}"))
                    break
                }

                if (retryable && attempt < MAX_RETRIES) {
                    val waitMs = when (classified) {
                        is RateLimitException -> classified.retryAfterMs ?: backoffMs
                        else -> backoffMs
                    }
                    Log.w(TAG, "Retryable stream error (attempt $attempt/$MAX_RETRIES), waiting ${waitMs}ms")
                    delay(waitMs)
                    backoffMs = CloudLlmRetry.advanceBackoff(backoffMs)
                    continue
                }

                Log.e(TAG, "Streaming failed with non-retryable error", classified)
                break
            }
        }

        if (streamCompleted) {
            close()
        } else {
            val error = lastException ?: RuntimeException("Stream ended unexpectedly")
            if (!failureEmitted) trySend(LLMStreamEvent.Failed(error.message ?: "Unknown error"))
            close(error)
        }

        awaitClose { Log.d(TAG, "Streaming flow closed") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildParams(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ChatCompletionCreateParams {
        val messages = buildList {
            add(ChatCompletionInterop.systemMessage(systemPrompt))
            addAll(ChatCompletionInterop.convertInputItems(inputItems))
        }
        val chatTools = ChatCompletionInterop.convertTools(tools)

        return ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .messages(messages)
            .tools(chatTools)
            .build()
    }

    override suspend fun cleanup() {
        Log.d(TAG, "Cleanup requested (no-op for cloud client)")
    }
}

package ai.closepaw.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAIResponseClient - LLM client using OpenAI Responses API.
 *
 * Features:
 * - Native function/tool calling via Responses API
 * - Automatic retry with exponential backoff on rate limits (429)
 * - Proper ResponseInputItem types for conversation history
 * - Native streaming support converted to LLMStreamEvent
 *
 * This is the cloud-based implementation that connects to OpenAI's API.
 */
class OpenAIResponseClient(
    apiKey: String,
    baseUrl: String? = null
) : LLMClient() {

    companion object {
        private const val TAG = "OpenAIResponseClient"
    }

    private val client: OpenAIClient

    init {
        InsecureSslConfig.validateBaseUrl(baseUrl)
        Log.d(TAG, "Creating OpenAIResponseClient")
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .apply { baseUrl?.let { baseUrl(it) } }
            .apply {
                InsecureSslConfig.sslSocketFactory?.let { sslSocketFactory(it) }
                InsecureSslConfig.trustManager?.let { trustManager(it) }
            }
            .build()
        Log.i(TAG, "OpenAIResponseClient created successfully")
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
        model: String,
        maxOutputTokens: Long?,
    ): ResponsesResult {
        return withContext(Dispatchers.IO) {
            CloudLlmRetry.executeWithRetry(
                    tag = TAG,
                    operationName = "responses chatWithTools"
            ) {
                executeChatWithTools(systemPrompt, inputItems, tools, model, maxOutputTokens)
            }
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
        model: String
    ): Flow<LLMStreamEvent> = callbackFlow {
        Log.d(TAG, "Starting native streaming chat with ${inputItems.size} input items")
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        val activeStream = AtomicReference<AutoCloseable?>(null)

        val job = launch {
            val retryResult =
                streamWithRetry(
                    tag = TAG,
                    emitToFlow = { event -> trySend(event) }
                ) { attempt, emitter ->
                    var sawCompleted = false
                    var responseId: String? = null
                    val verbose = LlmLogger.isVerboseEnabled
                    val textAccumulator = if (verbose) StringBuilder() else null
                    val toolCalls = if (verbose) mutableListOf<LLMToolCall>() else null

                    val params = buildResponseParams(systemPrompt, inputItems, tools, model)
                    Log.d(TAG, "Making streaming Responses API call to OpenAI (attempt $attempt)...")

                    withContext(Dispatchers.IO) {
                        val streamResponse = client.responses().createStreaming(params)
                        activeStream.set(streamResponse)
                        try {
                            streamResponse.use { sr ->
                                sr.stream().forEach { event ->
                            when {
                                event.isCreated() -> {
                                    val created = event.asCreated()
                                    responseId = created.response().id()
                                    emitter.emit(LLMStreamEvent.Created(created.response().id()))
                                }
                                event.isOutputTextDelta() -> {
                                    val textDelta = event.asOutputTextDelta()
                                    textAccumulator?.append(textDelta.delta())
                                    emitter.emit(LLMStreamEvent.TextDelta(textDelta.delta()))
                                }
                                event.isOutputItemDone() -> {
                                    val itemDone = event.asOutputItemDone()
                                    val item = itemDone.item()
                                    if (item.isFunctionCall()) {
                                        val funcCall = item.asFunctionCall()
                                        val toolCall =
                                            LLMToolCall(
                                                callId = funcCall.callId(),
                                                name = funcCall.name(),
                                                arguments = funcCall.arguments()
                                            )
                                        toolCalls?.add(toolCall)
                                        emitter.emit(LLMStreamEvent.ToolCallDone(toolCall))
                                    }
                                }
                                event.isCompleted() -> {
                                    sawCompleted = true
                                    if (verbose && textAccumulator != null && toolCalls != null) {
                                        LlmLogger.logOutput(
                                            TAG,
                                            ResponsesResult(
                                                textContent = textAccumulator.toString().takeIf { it.isNotEmpty() },
                                                toolCalls = toolCalls,
                                                responseId = responseId ?: "unknown"
                                            )
                                        )
                                    }
                                    emitter.emit(LLMStreamEvent.Completed)
                                }
                                event.isFailed() -> {
                                    val failed = event.asFailed()
                                    emitter.emit(LLMStreamEvent.Failed("Response failed: ${failed.response()}"))
                                    throw RuntimeException("Response failed: ${failed.response()}")
                                }
                            }
                        }
                            }
                        } finally {
                            activeStream.set(null)
                        }
                    }

                    if (!sawCompleted) {
                        throw TransientException("Stream ended without completion event")
                    }

                    Log.d(TAG, "Streaming completed successfully")
                }

            retryResult.closeFlow(
                emitToFlow = { trySend(it) },
                closeFlow = { close() }
            )
        }

        awaitClose {
            activeStream.getAndSet(null)?.runCatching { close() }
            job.cancel()
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
        model: String,
        maxOutputTokens: Long?,
    ): ResponsesResult {
        Log.d(TAG, "Calling Responses API with ${inputItems.size} input items, ${tools.size} tools")
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        try {
            val params = buildResponseParams(systemPrompt, inputItems, tools, model, maxOutputTokens)

            Log.d(TAG, "Making Responses API call to OpenAI...")

            val response = client.responses().create(params)

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
            LlmLogger.logOutput(TAG, result)
            return result

        } catch (e: Exception) {
            throw OpenAIErrorClassifier.classify(e)
        }
    }

    private fun buildResponseParams(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String,
        maxOutputTokens: Long? = null,
    ): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(ChatModel.of(model))
            .instructions(systemPrompt)
            .input(ResponseCreateParams.Input.ofResponse(inputItems))

        maxOutputTokens?.let { builder.maxOutputTokens(it) }

        tools.forEach { tool ->
            builder.addTool(tool)
        }

        return builder.build()
    }

    override suspend fun cleanup() {
        // No-op for cloud client, but kept suspend to match interface.
        Log.d(TAG, "Cleanup requested (no-op for OpenAI client)")
    }
}

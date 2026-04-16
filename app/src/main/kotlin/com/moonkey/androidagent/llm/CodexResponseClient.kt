package com.moonkey.androidagent.llm

import android.util.Log
import com.moonkey.androidagent.auth.OAuthCodexValidator
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * LLM client that talks to ChatGPT's Codex backend via OAuth access token.
 *
 * Uses raw OkHttp + [CodexRequestBuilder] / [CodexSseParser] to stream
 * responses from `chatgpt.com/backend-api/codex/responses`.
 *
 * The Codex backend always requires `stream: true`, so even the non-streaming
 * [chatWithTools] sends a streaming request and collects events into a result.
 */
class CodexResponseClient(
    accessToken: String
) : LLMClient() {

    companion object {
        private const val TAG = "CodexResponseClient"
        private const val CODEX_URL = "https://chatgpt.com/backend-api/codex/responses"
    }

    private val accessToken: String = accessToken
    private val accountId: String = OAuthCodexValidator.extractAccountId(accessToken)
        ?: throw IllegalStateException(
            "OAuth token does not contain a ChatGPT account ID. Please sign in again."
        )
    private val httpClient: OkHttpClient = buildHttpClient()

    init {
        Log.d(TAG, "CodexResponseClient created (account=...${accountId.takeLast(4)})")
    }

    // ── Non-streaming ────────────────────────────────────────────────────

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult = withContext(Dispatchers.IO) {
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        CloudLlmRetry.executeWithRetry(tag = TAG, operationName = "codex chatWithTools") {
            val body = CodexRequestBuilder.buildRequestBody(systemPrompt, inputItems, tools, model)
            val request = buildRequest(body)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) handleErrorResponse(response)

                val stream = response.body.byteStream()
                val textContent = StringBuilder()
                val toolCalls = mutableListOf<LLMToolCall>()
                var responseId = "unknown"
                var sawCompletion = false
                val accumulator = CodexSseParser.ToolCallAccumulator()

                for (event in CodexSseParser.parse(stream)) {
                    val outputIndex = event.json.optInt("output_index", 0)
                    when (event.type) {
                        "response.created" -> {
                            responseId = event.json.optJSONObject("response")
                                ?.optString("id", "unknown") ?: "unknown"
                        }
                        "response.output_text.delta" -> {
                            textContent.append(event.json.optString("delta", ""))
                        }
                        "response.output_item.added" -> {
                            val item = event.json.optJSONObject("item")
                            if (item != null) accumulator.onItemAdded(outputIndex, item)
                        }
                        "response.function_call_arguments.delta" -> {
                            accumulator.onArgumentsDelta(outputIndex, event.json.optString("delta", ""))
                        }
                        "response.output_item.done" -> {
                            val item = event.json.optJSONObject("item")
                            if (item?.optString("type") == "function_call") {
                                accumulator.onItemDone(outputIndex, item)?.let { toolCalls.add(it) }
                            }
                        }
                        "response.done", "response.completed" -> {
                            sawCompletion = true
                        }
                        "response.incomplete" -> {
                            val reason = event.json.optJSONObject("response")
                                ?.optString("incomplete_reason", "unknown") ?: "unknown"
                            throw RuntimeException("Codex response incomplete: $reason")
                        }
                        "response.failed" -> {
                            val msg = event.json.optJSONObject("response")
                                ?.optJSONObject("error")
                                ?.optString("message", "Unknown error") ?: "Unknown error"
                            throw RuntimeException("Codex response failed: $msg")
                        }
                        "error" -> {
                            val msg = event.json.optString("message", "")
                                .ifEmpty { event.json.optString("code", "Unknown error") }
                            throw RuntimeException("Codex error: $msg")
                        }
                    }
                }

                if (!sawCompletion) {
                    throw TransientException("Stream ended without completion event", null)
                }

                val result = ResponsesResult(
                    textContent = textContent.toString().takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls,
                    responseId = responseId
                )
                LlmLogger.logOutput(TAG, result)
                result
            }
        }
    }

    // ── Streaming ────────────────────────────────────────────────────────

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = callbackFlow {
        Log.d(TAG, "Starting Codex streaming chat with ${inputItems.size} input items")
        LlmLogger.logInput(TAG, systemPrompt, inputItems, tools)

        val activeCall = AtomicReference<okhttp3.Call?>(null)

        val job = launch {
            val retryResult = streamWithRetry(
                tag = TAG,
                emitToFlow = { event -> trySend(event) }
            ) { attempt, emitter ->
                val body = CodexRequestBuilder.buildRequestBody(systemPrompt, inputItems, tools, model)
                val request = buildRequest(body)

                withContext(Dispatchers.IO) {
                    val call = httpClient.newCall(request)
                    activeCall.set(call)
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) handleErrorResponse(response)

                            val stream = response.body.byteStream()
                            val accumulator = CodexSseParser.ToolCallAccumulator()
                            var sawCompletion = false
                            var responseId: String? = null
                            val textAccumulator = StringBuilder()
                            val toolCalls = mutableListOf<LLMToolCall>()

                            for (sseEvent in CodexSseParser.parse(stream)) {
                                val streamEvent = CodexSseParser.mapToStreamEvent(sseEvent, accumulator)
                                if (streamEvent != null) {
                                    when (streamEvent) {
                                        is LLMStreamEvent.Created -> responseId = streamEvent.responseId
                                        is LLMStreamEvent.TextDelta -> textAccumulator.append(streamEvent.delta)
                                        is LLMStreamEvent.ToolCallDone -> toolCalls.add(streamEvent.toolCall)
                                        is LLMStreamEvent.Completed -> {
                                            sawCompletion = true
                                            LlmLogger.logOutput(TAG, ResponsesResult(
                                                textContent = textAccumulator.toString().takeIf { it.isNotEmpty() },
                                                toolCalls = toolCalls,
                                                responseId = responseId ?: "unknown"
                                            ))
                                        }
                                        is LLMStreamEvent.Failed -> {
                                            emitter.emit(streamEvent)
                                            sawCompletion = true
                                            break
                                        }
                                    }
                                    emitter.emit(streamEvent)
                                }
                            }

                            if (!sawCompletion) throw TransientException("Stream ended without completion event")
                        }
                    } finally {
                        activeCall.set(null)
                    }
                }

                Log.d(TAG, "Codex streaming completed successfully")
            }

            retryResult.closeFlow(
                emitToFlow = { trySend(it) },
                closeFlow = { close() }
            )
        }

        awaitClose {
            activeCall.getAndSet(null)?.cancel()
            job.cancel()
            Log.d(TAG, "Codex streaming flow closed")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun cleanup() {
        Log.d(TAG, "Cleanup: evicting connections and shutting down dispatcher")
        withContext(Dispatchers.IO) {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** MediaType without charset — ChatGPT backend rejects `charset=utf-8`. */
    private fun buildRequest(body: String): Request =
        Request.Builder()
            .url(CODEX_URL)
            .post(body.toRequestBody(null)) // no media type on body
            .header("Content-Type", "application/json") // set header directly
            .header("Authorization", "Bearer $accessToken")
            .header("chatgpt-account-id", accountId)
            .header("originator", "pi")
            .header("OpenAI-Beta", "responses=experimental")
            .header("Accept", "text/event-stream")
            .build()

    private fun buildHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                val sf = InsecureSslConfig.sslSocketFactory
                val tm = InsecureSslConfig.trustManager
                if (sf != null && tm != null) {
                    sslSocketFactory(sf, tm)
                }
            }
            .build()

    private fun handleErrorResponse(response: okhttp3.Response): Nothing {
        val body = response.body.string()
        val status = response.code
        val parsed = try { JSONObject(body) } catch (_: Exception) { null }
        val error = parsed?.optJSONObject("error")
        val code = error?.optString("code", "") ?: ""
        val message = error?.optString("message", "") ?: body

        when {
            status == 429 || code.contains("rate_limit") || code.contains("usage_limit") -> {
                val planType = error?.optString("plan_type", "")
                val resetsAt = error?.optLong("resets_at", 0) ?: 0
                val mins = if (resetsAt > 0) {
                    maxOf(0, (resetsAt * 1000 - System.currentTimeMillis()) / 60000)
                } else null
                val friendly = buildString {
                    append("ChatGPT usage limit reached")
                    if (planType?.isNotEmpty() == true) append(" ($planType plan)")
                    append(".")
                    if (mins != null) append(" Try again in ~$mins min.")
                }
                throw RateLimitException(friendly)
            }
            status == 401 || status == 403 ->
                throw IllegalStateException("Token rejected: $message")
            status in 500..599 ->
                throw TransientException("Server error: HTTP $status", null)
            else ->
                throw RuntimeException("Codex API error: HTTP $status $message")
        }
    }
}

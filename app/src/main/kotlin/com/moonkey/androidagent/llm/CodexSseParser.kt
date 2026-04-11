package com.moonkey.androidagent.llm

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import org.json.JSONObject

/**
 * Parses Server-Sent Events from a raw byte stream (OkHttp response body)
 * and maps Codex-specific event types to [LLMStreamEvent].
 */
object CodexSseParser {

    private const val TAG = "CodexSseParser"

    data class SseEvent(val type: String, val json: JSONObject)

    /**
     * Parse SSE events from an OkHttp response body stream.
     * Yields parsed JSON events, skipping [DONE] markers.
     */
    fun parse(source: InputStream): Sequence<SseEvent> = sequence {
        val reader = BufferedReader(source.reader(Charsets.UTF_8))
        val dataBuilder = StringBuilder()

        while (true) {
            val line = reader.readLine() ?: break

            when {
                line.startsWith("data:") -> {
                    if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                    dataBuilder.append(line.removePrefix("data:").trimStart())
                }
                line.isBlank() -> {
                    val data = dataBuilder.toString().trim()
                    dataBuilder.clear()
                    if (data.isEmpty() || data == "[DONE]") continue
                    try {
                        val json = JSONObject(data)
                        val type = json.optString("type", "")
                        if (type.isNotEmpty()) yield(SseEvent(type, json))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE data: ${data.take(200)}", e)
                    }
                }
                // Ignore "event:", "id:", "retry:" and other non-data lines
            }
        }

        // Flush remaining data (stream ended without trailing blank line)
        val remaining = dataBuilder.toString().trim()
        if (remaining.isNotEmpty() && remaining != "[DONE]") {
            try {
                val json = JSONObject(remaining)
                val type = json.optString("type", "")
                if (type.isNotEmpty()) yield(SseEvent(type, json))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse trailing SSE data: ${remaining.take(200)}", e)
            }
        }
    }

    /**
     * Map a Codex SSE event to an [LLMStreamEvent].
     * Returns null for events we don't need to emit.
     */
    fun mapToStreamEvent(
        event: SseEvent,
        accumulator: ToolCallAccumulator
    ): LLMStreamEvent? = when (event.type) {
        "response.created" -> {
            val responseId = event.json.getJSONObject("response").getString("id")
            LLMStreamEvent.Created(responseId)
        }
        "response.output_text.delta" -> {
            LLMStreamEvent.TextDelta(event.json.getString("delta"))
        }
        "response.output_item.added" -> {
            val outputIndex = event.json.optInt("output_index", 0)
            accumulator.onItemAdded(outputIndex, event.json.getJSONObject("item"))
            null
        }
        "response.function_call_arguments.delta" -> {
            val outputIndex = event.json.optInt("output_index", 0)
            accumulator.onArgumentsDelta(outputIndex, event.json.getString("delta"))
            null
        }
        "response.output_item.done" -> {
            val outputIndex = event.json.optInt("output_index", 0)
            val item = event.json.getJSONObject("item")
            if (item.optString("type") == "function_call") {
                accumulator.onItemDone(outputIndex, item)?.let { LLMStreamEvent.ToolCallDone(it) }
            } else null
        }
        "response.done", "response.completed" -> {
            LLMStreamEvent.Completed
        }
        "response.incomplete" -> {
            val reason = event.json
                .optJSONObject("response")
                ?.optString("incomplete_reason", "unknown")
                ?: "unknown"
            LLMStreamEvent.Failed("Response incomplete: $reason")
        }
        "response.failed" -> {
            val message = event.json
                .optJSONObject("response")
                ?.optJSONObject("error")
                ?.optString("message", "Unknown error")
                ?: "Unknown error"
            LLMStreamEvent.Failed(message)
        }
        "error" -> {
            val message = event.json.optString("message", "")
                .ifEmpty { event.json.optString("code", "Unknown error") }
            LLMStreamEvent.Failed(message)
        }
        else -> null
    }

    /**
     * Accumulates function_call_arguments.delta events into complete tool calls.
     * Supports parallel tool calls by tracking state per output_index.
     */
    class ToolCallAccumulator {
        private data class PendingCall(
            val callId: String,
            val name: String,
            val args: StringBuilder = StringBuilder()
        )

        private val pending = mutableMapOf<Int, PendingCall>()

        fun onItemAdded(outputIndex: Int, item: JSONObject) {
            if (item.optString("type") == "function_call") {
                pending[outputIndex] = PendingCall(
                    callId = item.optString("call_id", ""),
                    name = item.optString("name", "")
                )
            }
        }

        fun onArgumentsDelta(outputIndex: Int, delta: String) {
            pending[outputIndex]?.args?.append(delta)
        }

        fun onItemDone(outputIndex: Int, item: JSONObject): LLMToolCall? {
            if (item.optString("type") != "function_call") return null
            val call = pending.remove(outputIndex)
            val id = call?.callId ?: item.optString("call_id", "")
            val fn = call?.name ?: item.optString("name", "")
            val args = call?.args?.toString()?.ifEmpty { null }
                ?: item.optString("arguments", "{}")
            return LLMToolCall(callId = id, name = fn, arguments = args)
        }
    }
}

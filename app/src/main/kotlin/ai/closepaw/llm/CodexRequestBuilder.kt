package ai.closepaw.llm

import android.util.Log
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes OpenAI SDK types to JSON for the ChatGPT Codex endpoint
 * (`chatgpt.com/backend-api/codex/responses`).
 *
 * Codex requires specific wire formats that differ from the standard Responses API:
 * - `stream` must be `true`
 * - `instructions` must be present
 * - `max_output_tokens` must NOT be present
 * - Message content must be wrapped arrays: user → `input_text`, assistant → `output_text`
 */
internal object CodexRequestBuilder {

    private const val TAG = "CodexRequestBuilder"

    fun buildRequestBody(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("store", false)
            put("instructions", systemPrompt)
            put("input", convertInputItems(inputItems))
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("tools", convertTools(tools))
        }
        return body.toString()
    }

    // ── Internal (visible for testing) ──────────────────────────────────

    internal fun convertInputItems(items: List<ResponseInputItem>): JSONArray {
        val result = JSONArray()
        for (item in items) {
            when {
                item.isEasyInputMessage() -> {
                    val msg = item.asEasyInputMessage()
                    result.put(convertMessage(msg.role().toString().lowercase(), msg.content()))
                }
                item.isFunctionCall() -> {
                    val fc = item.asFunctionCall()
                    result.put(JSONObject().apply {
                        put("type", "function_call")
                        put("call_id", fc.callId())
                        put("name", fc.name())
                        put("arguments", fc.arguments())
                    })
                }
                item.isFunctionCallOutput() -> {
                    val fco = item.asFunctionCallOutput()
                    val outputStr = if (fco.output().isString()) {
                        fco.output().asString()
                    } else {
                        fco.output().toString()
                    }
                    result.put(JSONObject().apply {
                        put("type", "function_call_output")
                        put("call_id", fco.callId())
                        put("output", outputStr)
                    })
                }
                else -> {
                    Log.w(TAG, "Skipping unknown input item type: ${item.javaClass.simpleName}")
                }
            }
        }
        return result
    }

    internal fun convertTools(tools: List<FunctionTool>): JSONArray {
        val result = JSONArray()
        for (tool in tools) {
            result.put(JSONObject().apply {
                put("type", "function")
                put("name", tool.name())
                put("description", tool.description().orElse(""))
                put("parameters", ToolParameterExtractor.extract(tool) ?: JSONObject())
            })
        }
        return result
    }

    // ── Private ─────────────────────────────────────────────────────────

    private fun convertMessage(
        role: String,
        content: com.openai.models.responses.EasyInputMessage.Content
    ): JSONObject {
        val obj = JSONObject().apply { put("role", role) }
        // Codex API: user messages use "input_text", assistant messages use "output_text"
        val textType = if (role == "assistant") "output_text" else "input_text"

        if (content.isTextInput()) {
            obj.put("content", JSONArray().put(
                JSONObject().apply {
                    put("type", textType)
                    put("text", content.asTextInput())
                }
            ))
            return obj
        }

        if (content.isResponseInputMessageContentList()) {
            val parts = JSONArray()
            for (part in content.asResponseInputMessageContentList()) {
                when {
                    part.isInputText() -> parts.put(JSONObject().apply {
                        put("type", textType)
                        put("text", part.asInputText().text())
                    })
                    part.isInputImage() -> {
                        val url = part.asInputImage().imageUrl().orElse(null)
                        if (url == null) {
                            Log.w(TAG, "Skipping image without URL")
                            continue
                        }
                        parts.put(JSONObject().apply {
                            put("type", "input_image")
                            put("image_url", url)
                            put("detail", "auto")
                        })
                    }
                    else -> Log.w(TAG, "Skipping unknown content part: ${part.javaClass.simpleName}")
                }
            }
            obj.put("content", parts)
            return obj
        }

        // Fallback
        obj.put("content", JSONArray().put(
            JSONObject().apply {
                put("type", textType)
                put("text", content.toString())
            }
        ))
        return obj
    }
}

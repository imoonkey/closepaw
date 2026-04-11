package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts tool parameter schemas from [FunctionTool], handling the SDK's
 * known / unknown / raw-string representations.
 */
internal object ToolParameterExtractor {

    private const val TAG = "ToolParamExtractor"

    fun extract(tool: FunctionTool): JSONObject? {
        tool._parameters().asKnown().orElse(null)?.let {
            return jsonValueMapToJsonObject(it._additionalProperties())
        }

        val raw = tool._parameters().asUnknown().orElse(null) ?: run {
            Log.w(TAG, "Tool parameters missing for ${tool.name()}")
            return null
        }

        raw.asObject().orElse(null)?.let { return jsonValueMapToJsonObject(it) }

        val rawJson = raw.toString().trim()
        if (rawJson.isBlank()) {
            Log.w(TAG, "Tool parameters present but empty for ${tool.name()}")
            return null
        }
        if (rawJson.startsWith("{")) {
            try {
                return JSONObject(rawJson)
            } catch (e: Exception) {
                val snippet = if (rawJson.length > 200) rawJson.take(200) + "..." else rawJson
                Log.w(TAG, "Failed to parse parameters for ${tool.name()}. Raw: $snippet", e)
            }
        } else {
            val snippet = if (rawJson.length > 200) rawJson.take(200) + "..." else rawJson
            Log.w(TAG, "Unexpected parameter format for ${tool.name()}. Raw: $snippet")
        }
        return null
    }

    private fun jsonValueMapToJsonObject(map: Map<String, JsonValue>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, jsonValueToAny(value)) }
        return obj
    }

    private fun jsonValueToAny(value: JsonValue): Any? {
        if (value.isNull()) return JSONObject.NULL
        value.asString().orElse(null)?.let { return it }
        value.asBoolean().orElse(null)?.let { return it }
        value.asNumber().orElse(null)?.let { return it }
        value.asArray().orElse(null)?.let { array ->
            return JSONArray().apply { array.forEach { put(jsonValueToAny(it)) } }
        }
        value.asObject().orElse(null)?.let { return jsonValueMapToJsonObject(it) }
        val raw = value.toString()
        if (raw.startsWith("{")) return try { JSONObject(raw) } catch (_: Exception) { raw }
        if (raw.startsWith("[")) return try { JSONArray(raw) } catch (_: Exception) { raw }
        return raw
    }
}

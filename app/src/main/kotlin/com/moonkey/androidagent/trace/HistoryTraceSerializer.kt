package com.moonkey.androidagent.trace

import com.moonkey.androidagent.history.ResponseItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object HistoryTraceSerializer {
    fun toJson(items: List<ResponseItem>): JsonArray {
        return JsonArray(items.map { toJsonObject(it) })
    }

    private fun toJsonObject(item: ResponseItem): JsonObject {
        return when (item) {
            is ResponseItem.Message ->
                buildJsonObject {
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive(item.role))
                    put("content", JsonPrimitive(item.content))
                    item.name?.let { put("name", JsonPrimitive(it)) }
                    if (item.isScreenObservation) {
                        put("is_screen_observation", JsonPrimitive(true))
                    }
                }

            is ResponseItem.FunctionCall ->
                buildJsonObject {
                    put("type", JsonPrimitive("function_call"))
                    put("id", JsonPrimitive(item.id))
                    put("name", JsonPrimitive(item.name))
                    put("arguments_json", JsonPrimitive(item.arguments.toString()))
                }

            is ResponseItem.FunctionCallOutput ->
                buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(item.callId))
                    put("success", JsonPrimitive(item.success))
                    put("truncated", JsonPrimitive(item.truncated))
                    put("content", JsonPrimitive(item.content))
                }
        }
    }
}


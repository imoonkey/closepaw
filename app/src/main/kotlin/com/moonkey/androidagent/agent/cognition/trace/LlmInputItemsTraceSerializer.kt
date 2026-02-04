package com.moonkey.androidagent.agent.cognition.trace

import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LlmInputItemsTraceSerializer {
    fun toJson(inputItems: List<ResponseInputItem>): JsonArray {
        return buildJsonArray {
            inputItems.forEachIndexed { index, item ->
                add(serializeItem(index, item))
            }
        }
    }

    private fun serializeItem(index: Int, item: ResponseInputItem): JsonElement {
        return when {
            item.isEasyInputMessage() -> {
                val msg = item.asEasyInputMessage()
                val content = extractMessageContent(msg.content())
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive(msg.role().toString().lowercase()))
                    put("content", JsonPrimitive(content))
                }
            }

            item.isFunctionCall() -> {
                val call = item.asFunctionCall()
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("type", JsonPrimitive("function_call"))
                    put("call_id", JsonPrimitive(call.callId()))
                    put("name", JsonPrimitive(call.name()))
                    put("arguments_json", JsonPrimitive(call.arguments()))
                }
            }

            item.isFunctionCallOutput() -> {
                val output = item.asFunctionCallOutput()
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(output.callId()))
                    put("output", JsonPrimitive(output.output().toString()))
                }
            }

            else -> {
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("type", JsonPrimitive("unknown"))
                    put("raw", JsonPrimitive(item.toString()))
                }
            }
        }
    }

    private fun extractMessageContent(content: Any): String {
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString(" ") { part -> part?.toString().orEmpty() }.trim()
            else -> content.toString()
        }
    }
}

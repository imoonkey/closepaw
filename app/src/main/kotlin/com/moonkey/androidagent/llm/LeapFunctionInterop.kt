package com.moonkey.androidagent.llm

import android.util.Log
import ai.liquid.leap.function.LeapFunction
import ai.liquid.leap.function.LeapFunctionParameter
import ai.liquid.leap.function.LeapFunctionParameterType
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import org.json.JSONArray
import org.json.JSONObject

internal object LeapToolSchemaAdapter {
    private const val TAG = "LeapToolSchemaAdapter"

    fun toLeapFunction(tool: FunctionTool): LeapFunction {
        val description = tool.description().orElse("")
        val schema = parseToolParameters(tool)
        val parameters = schema?.let { buildLeapParameters(it) } ?: emptyList()
        return LeapFunction(tool.name(), description, parameters)
    }

    private fun parseToolParameters(tool: FunctionTool): JSONObject? {
        val knownParams = tool._parameters().asKnown().orElse(null)
        if (knownParams != null) {
            return jsonValueMapToJsonObject(knownParams._additionalProperties())
        }

        val rawField = tool._parameters().asUnknown().orElse(null) ?: run {
            Log.w(TAG, "Tool parameters missing for ${tool.name()}")
            return null
        }

        val objectMap = rawField.asObject().orElse(null)
        if (objectMap != null) {
            return jsonValueMapToJsonObject(objectMap)
        }

        val rawJson = rawField.toString()
        if (rawJson.startsWith("{")) {
            try {
                return JSONObject(rawJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse parameters JSON for ${tool.name()}", e)
            }
        }
        Log.w(TAG, "Tool parameters missing for ${tool.name()}")
        return null
    }

    private fun buildLeapParameters(schema: JSONObject): List<LeapFunctionParameter> {
        val schemaType = schema.optString("type", "object")
        if (schemaType != "object") {
            return emptyList()
        }
        val properties = when (val rawProps = schema.opt("properties")) {
            is JSONObject -> rawProps
            is Map<*, *> -> mapToJsonObject(rawProps)
            else -> null
        } ?: return emptyList()
        val required = when (val rawRequired = schema.opt("required")) {
            is JSONArray -> rawRequired.toStringSet()
            is Iterable<*> -> rawRequired.mapNotNull { it?.toString() }.toSet()
            else -> emptySet()
        }
        val parameters = mutableListOf<LeapFunctionParameter>()

        properties.keys().forEach { name ->
            val propSchema = properties.optJSONObject(name) ?: JSONObject()
            val description = propSchema.optString("description", "")
            val type = parseParameterType(propSchema)
            val optional = name !in required
            parameters.add(
                LeapFunctionParameter(
                    name = name,
                    type = type,
                    description = description,
                    optional = optional
                )
            )
        }
        return parameters
    }

    private fun parseParameterType(schema: JSONObject): LeapFunctionParameterType {
        val description = schema.optString("description").takeIf { it.isNotBlank() }
        val rawType = schema.opt("type")
        val type = when (rawType) {
            is JSONArray -> rawType.toStringList().firstOrNull { it != "null" } ?: "string"
            is String -> rawType
            else -> "string"
        }

        return when (type) {
            "string" -> LeapFunctionParameterType.LeapStr(
                enumValues = schema.optJSONArray("enum")?.toStringList(),
                description = description
            )
            "integer" -> LeapFunctionParameterType.LeapInt(
                enumValues = schema.optJSONArray("enum")?.toIntList(),
                description = description
            )
            "number" -> LeapFunctionParameterType.LeapNum(
                enumValues = schema.optJSONArray("enum")?.toNumberList(),
                description = description
            )
            "boolean" -> LeapFunctionParameterType.LeapBool(description = description)
            "array" -> {
                val itemSchema = schema.optJSONObject("items")
                val itemType = if (itemSchema != null) {
                    parseParameterType(itemSchema)
                } else {
                    LeapFunctionParameterType.LeapStr()
                }
                LeapFunctionParameterType.LeapArr(itemType = itemType, description = description)
            }
            "object" -> {
                val properties = schema.optJSONObject("properties")
                val required = schema.optJSONArray("required")?.toStringList() ?: emptyList()
                val propertyTypes = mutableMapOf<String, LeapFunctionParameterType>()
                properties?.keys()?.forEach { key ->
                    val propSchema = properties.optJSONObject(key) ?: JSONObject()
                    propertyTypes[key] = parseParameterType(propSchema)
                }
                LeapFunctionParameterType.LeapObj(
                    properties = propertyTypes,
                    required = required,
                    description = description
                )
            }
            else -> LeapFunctionParameterType.LeapStr(
                enumValues = schema.optJSONArray("enum")?.toStringList(),
                description = description
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { idx ->
            optString(idx, null)
        }
    }

    private fun JSONArray.toIntList(): List<Int> {
        return (0 until length()).mapNotNull { idx ->
            when (val value = get(idx)) {
                is Int -> value
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
    }

    private fun JSONArray.toNumberList(): List<Number> {
        return (0 until length()).mapNotNull { idx ->
            when (val value = get(idx)) {
                is Number -> value
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    private fun JSONArray.toStringSet(): Set<String> = toStringList().toSet()

    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val jsonObject = JSONObject()
        map.forEach { (key, value) ->
            if (key != null) {
                jsonObject.put(key.toString(), value)
            }
        }
        return jsonObject
    }

    private fun jsonValueMapToJsonObject(map: Map<String, JsonValue>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) ->
            obj.put(key, jsonValueToAny(value))
        }
        return obj
    }

    private fun jsonValueToAny(value: JsonValue): Any? {
        if (value.isNull()) return null
        value.asString().orElse(null)?.let { return it }
        value.asBoolean().orElse(null)?.let { return it }
        value.asNumber().orElse(null)?.let { return it }
        value.asArray().orElse(null)?.let { array ->
            val jsonArray = JSONArray()
            array.forEach { jsonArray.put(jsonValueToAny(it)) }
            return jsonArray
        }
        value.asObject().orElse(null)?.let { obj ->
            return jsonValueMapToJsonObject(obj)
        }
        val raw = value.toString()
        if (raw.startsWith("{")) {
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                raw
            }
        }
        if (raw.startsWith("[")) {
            return try {
                JSONArray(raw)
            } catch (_: Exception) {
                raw
            }
        }
        return raw
    }
}

internal object LeapJsonAdapter {
    private const val TAG = "LeapJsonAdapter"

    fun toJsonString(arguments: Map<String, Any?>): String {
        return try {
            val jsonObj = JSONObject()
            arguments.forEach { (key, value) ->
                jsonObj.put(key, toJsonCompatible(value))
            }
            jsonObj.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert arguments to JSON", e)
            "{}"
        }
    }

    fun parseJsonArguments(arguments: String): Map<String, Any?>? {
        return try {
            jsonObjectToMap(JSONObject(arguments))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse function call arguments", e)
            null
        }
    }

    private fun toJsonCompatible(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is Map<*, *> -> mapToJsonObject(value)
            is List<*> -> listToJsonArray(value)
            else -> value
        }
    }

    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) ->
            obj.put(key?.toString() ?: "null", toJsonCompatible(value))
        }
        return obj
    }

    private fun listToJsonArray(list: List<*>): JSONArray {
        val array = JSONArray()
        list.forEach { value ->
            array.put(toJsonCompatible(value))
        }
        return array
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key ->
            map[key] = when (val value = obj.get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> {
        return (0 until array.length()).map { idx ->
            when (val value = array.get(idx)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
    }
}

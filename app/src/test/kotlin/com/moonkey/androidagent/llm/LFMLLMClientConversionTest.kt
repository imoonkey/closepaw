package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Unit tests for LFMLLMClient type conversion functions.
 * 
 * These tests validate the conversion logic between:
 * - OpenAI FunctionTool parameter schemas -> Leap SDK LeapFunctionParameter
 * - OpenAI ResponseInputItem -> Leap SDK ChatMessage
 * - Leap SDK function arguments -> JSON string
 */
class LFMLLMClientConversionTest {
    
    // ========== Schema to LeapFunctionParameter Conversion Tests ==========
    
    @Test
    fun `parseJsonSchemaType - string type`() {
        val schema = JSONObject().apply {
            put("type", "string")
            put("description", "Test string param")
        }
        
        val result = TypeConversionHelper.parseJsonSchemaType(schema)
        
        assertThat(result.type).isEqualTo("string")
        assertThat(result.description).isEqualTo("Test string param")
    }
    
    @Test
    fun `parseJsonSchemaType - integer type`() {
        val schema = JSONObject().apply {
            put("type", "integer")
            put("description", "Element index")
        }
        
        val result = TypeConversionHelper.parseJsonSchemaType(schema)
        
        assertThat(result.type).isEqualTo("integer")
        assertThat(result.description).isEqualTo("Element index")
    }
    
    @Test
    fun `parseJsonSchemaType - array type with items`() {
        val schema = JSONObject().apply {
            put("type", "array")
            put("description", "Start coordinates")
            put("items", JSONObject().put("type", "integer"))
        }
        
        val result = TypeConversionHelper.parseJsonSchemaType(schema)
        
        assertThat(result.type).isEqualTo("array")
        assertThat(result.itemType).isEqualTo("integer")
        assertThat(result.description).isEqualTo("Start coordinates")
    }
    
    @Test
    fun `parseJsonSchemaType - string with enum`() {
        val schema = JSONObject().apply {
            put("type", "string")
            put("description", "System button")
            put("enum", JSONArray(listOf("back", "home", "enter")))
        }
        
        val result = TypeConversionHelper.parseJsonSchemaType(schema)
        
        assertThat(result.type).isEqualTo("string")
        assertThat(result.enumValues).containsExactly("back", "home", "enter")
    }
    
    @Test
    fun `extractParameters - mobile_action schema`() {
        // Simplified mobile_action parameter schema
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "The action to perform")
                    put("enum", JSONArray(listOf("click", "type", "swipe")))
                })
                put("element_index", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Element index for click")
                })
                put("text", JSONObject().apply {
                    put("type", "string")
                    put("description", "Text to input")
                })
            })
            put("required", JSONArray(listOf("action")))
        }
        
        val params = TypeConversionHelper.extractParametersFromSchema(schema)
        
        assertThat(params).hasSize(3)
        assertThat(params.map { it.name }).containsExactly("action", "element_index", "text")
        
        val actionParam = params.find { it.name == "action" }!!
        assertThat(actionParam.type).isEqualTo("string")
        assertThat(actionParam.enumValues).containsExactly("click", "type", "swipe")
        assertThat(actionParam.required).isTrue()
        
        val elementIndexParam = params.find { it.name == "element_index" }!!
        assertThat(elementIndexParam.type).isEqualTo("integer")
        assertThat(elementIndexParam.required).isFalse()
    }
    
    @Test
    fun `extractParameters - complete_task schema`() {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("status", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("success", "failure")))
                    put("description", "Whether the task succeeded or failed")
                })
                put("answer", JSONObject().apply {
                    put("type", "string")
                    put("description", "The answer to return")
                })
            })
            put("required", JSONArray(listOf("status", "answer")))
        }
        
        val params = TypeConversionHelper.extractParametersFromSchema(schema)
        
        assertThat(params).hasSize(2)
        
        val statusParam = params.find { it.name == "status" }!!
        assertThat(statusParam.required).isTrue()
        assertThat(statusParam.enumValues).containsExactly("success", "failure")
        
        val answerParam = params.find { it.name == "answer" }!!
        assertThat(answerParam.required).isTrue()
    }
    
    // ========== Arguments to JSON Conversion Tests ==========
    
    @Test
    fun `convertArgumentsToJson - simple map`() {
        val args = mapOf(
            "action" to "click",
            "element_index" to 5
        )
        
        val json = TypeConversionHelper.convertArgumentsToJson(args)
        val parsed = JSONObject(json)
        
        assertThat(parsed.getString("action")).isEqualTo("click")
        assertThat(parsed.getInt("element_index")).isEqualTo(5)
    }
    
    @Test
    fun `convertArgumentsToJson - nested values`() {
        val args = mapOf(
            "start" to listOf(100, 200),
            "end" to listOf(100, 800)
        )
        
        val json = TypeConversionHelper.convertArgumentsToJson(args)
        val parsed = JSONObject(json)
        
        val start = parsed.getJSONArray("start")
        assertThat(start.getInt(0)).isEqualTo(100)
        assertThat(start.getInt(1)).isEqualTo(200)
    }
    
    @Test
    fun `convertArgumentsToJson - null values`() {
        val args = mapOf<String, Any?>(
            "action" to "click",
            "optional" to null
        )
        
        val json = TypeConversionHelper.convertArgumentsToJson(args)
        val parsed = JSONObject(json)
        
        assertThat(parsed.getString("action")).isEqualTo("click")
        assertThat(parsed.isNull("optional")).isTrue()
    }
    
    @Test
    fun `convertArgumentsToJson - empty map`() {
        val args = emptyMap<String, Any?>()
        
        val json = TypeConversionHelper.convertArgumentsToJson(args)
        
        assertThat(json).isEqualTo("{}")
    }
}

/**
 * Helper object for type conversion testing.
 * 
 * This mirrors the conversion logic that will be in LFMLLMClient,
 * extracted for testability.
 */
object TypeConversionHelper {
    
    /**
     * Parsed parameter information from JSON Schema.
     */
    data class ParsedParameter(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean = false,
        val enumValues: List<String>? = null,
        val itemType: String? = null
    )
    
    /**
     * Parsed type information from a single property schema.
     */
    data class ParsedType(
        val type: String,
        val description: String,
        val enumValues: List<String>? = null,
        val itemType: String? = null
    )
    
    /**
     * Parse a single JSON Schema property into type information.
     */
    fun parseJsonSchemaType(schema: JSONObject): ParsedType {
        val type = schema.optString("type", "string")
        val description = schema.optString("description", "")
        
        // Check for enum
        val enumArray = schema.optJSONArray("enum")
        val enumValues = if (enumArray != null) {
            (0 until enumArray.length()).map { enumArray.getString(it) }
        } else null
        
        // Check for array items
        val itemType = if (type == "array") {
            schema.optJSONObject("items")?.optString("type", "string")
        } else null
        
        return ParsedType(type, description, enumValues, itemType)
    }
    
    /**
     * Extract all parameters from a JSON Schema object.
     */
    fun extractParametersFromSchema(schema: JSONObject): List<ParsedParameter> {
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        val requiredArray = schema.optJSONArray("required")
        val requiredSet = if (requiredArray != null) {
            (0 until requiredArray.length()).map { requiredArray.getString(it) }.toSet()
        } else emptySet()
        
        return properties.keys().asSequence().map { name ->
            val propSchema = properties.getJSONObject(name)
            val parsed = parseJsonSchemaType(propSchema)
            ParsedParameter(
                name = name,
                type = parsed.type,
                description = parsed.description,
                required = name in requiredSet,
                enumValues = parsed.enumValues,
                itemType = parsed.itemType
            )
        }.toList()
    }
    
    /**
     * Convert Leap SDK function arguments map to JSON string.
     */
    fun convertArgumentsToJson(arguments: Map<String, Any?>): String {
        return try {
            val jsonObj = JSONObject()
            arguments.forEach { (key, value) ->
                when (value) {
                    null -> jsonObj.put(key, JSONObject.NULL)
                    is List<*> -> jsonObj.put(key, JSONArray(value))
                    is Map<*, *> -> jsonObj.put(key, JSONObject(value as Map<String, Any?>))
                    else -> jsonObj.put(key, value)
                }
            }
            jsonObj.toString()
        } catch (e: Exception) {
            "{}"
        }
    }
}

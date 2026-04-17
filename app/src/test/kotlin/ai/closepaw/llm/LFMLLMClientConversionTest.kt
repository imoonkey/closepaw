package ai.closepaw.llm

import ai.liquid.leap.function.LeapFunctionParameterType
import com.google.common.truth.Truth.assertThat
import com.openai.models.responses.FunctionTool
import ai.closepaw.tool.jsonObjectToJsonValueMap
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
            put("type", "object")
            put("properties", JSONObject().apply {
                put("value", JSONObject().apply {
                    put("type", "string")
                    put("description", "Test string param")
                })
            })
            put("required", JSONArray(listOf("value")))
        }

        val param = parseSingleParam(schema, "value")

        assertThat(param.type).isInstanceOf(LeapFunctionParameterType.LeapStr::class.java)
        assertThat(param.description).isEqualTo("Test string param")
    }
    
    @Test
    fun `parseJsonSchemaType - integer type`() {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("element_index", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Element index")
                })
            })
            put("required", JSONArray(listOf("element_index")))
        }

        val param = parseSingleParam(schema, "element_index")

        assertThat(param.type).isInstanceOf(LeapFunctionParameterType.LeapInt::class.java)
        assertThat(param.description).isEqualTo("Element index")
    }
    
    @Test
    fun `parseJsonSchemaType - array type with items`() {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("start", JSONObject().apply {
                    put("type", "array")
                    put("description", "Start coordinates")
                    put("items", JSONObject().put("type", "integer"))
                })
            })
            put("required", JSONArray(listOf("start")))
        }

        val param = parseSingleParam(schema, "start")
        val type = param.type as LeapFunctionParameterType.LeapArr

        assertThat(type.itemType).isInstanceOf(LeapFunctionParameterType.LeapInt::class.java)
        assertThat(param.description).isEqualTo("Start coordinates")
    }
    
    @Test
    fun `parseJsonSchemaType - string with enum`() {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("button", JSONObject().apply {
                    put("type", "string")
                    put("description", "System button")
                    put("enum", JSONArray(listOf("back", "home", "enter")))
                })
            })
            put("required", JSONArray(listOf("button")))
        }

        val param = parseSingleParam(schema, "button")
        val type = param.type as LeapFunctionParameterType.LeapStr

        assertThat(type.enumValues).containsExactly("back", "home", "enter")
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
        
        val params = parseParams(schema)

        assertThat(params).hasSize(3)
        assertThat(params.map { it.name }).containsExactly("action", "element_index", "text")

        val actionParam = params.find { it.name == "action" }!!
        assertThat(actionParam.type).isInstanceOf(LeapFunctionParameterType.LeapStr::class.java)
        val actionType = actionParam.type as LeapFunctionParameterType.LeapStr
        assertThat(actionType.enumValues).containsExactly("click", "type", "swipe")
        assertThat(actionParam.optional).isFalse()

        val elementIndexParam = params.find { it.name == "element_index" }!!
        assertThat(elementIndexParam.type).isInstanceOf(LeapFunctionParameterType.LeapInt::class.java)
        assertThat(elementIndexParam.optional).isTrue()
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
        
        val params = parseParams(schema)

        assertThat(params).hasSize(2)

        val statusParam = params.find { it.name == "status" }!!
        assertThat(statusParam.optional).isFalse()
        val statusType = statusParam.type as LeapFunctionParameterType.LeapStr
        assertThat(statusType.enumValues).containsExactly("success", "failure")

        val answerParam = params.find { it.name == "answer" }!!
        assertThat(answerParam.optional).isFalse()
    }
    
    // ========== Arguments to JSON Conversion Tests ==========
    
    @Test
    fun `convertArgumentsToJson - simple map`() {
        val args = mapOf(
            "action" to "click",
            "element_index" to 5
        )
        
        val json = LeapJsonAdapter.toJsonString(args)
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
        
        val json = LeapJsonAdapter.toJsonString(args)
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
        
        val json = LeapJsonAdapter.toJsonString(args)
        val parsed = JSONObject(json)
        
        assertThat(parsed.getString("action")).isEqualTo("click")
        assertThat(parsed.isNull("optional")).isTrue()
    }
    
    @Test
    fun `convertArgumentsToJson - empty map`() {
        val args = emptyMap<String, Any?>()
        
        val json = LeapJsonAdapter.toJsonString(args)
        
        assertThat(json).isEqualTo("{}")
    }
}

private fun buildFunctionTool(schema: JSONObject): FunctionTool {
    val parameters = FunctionTool.Parameters.builder()
        .putAllAdditionalProperties(jsonObjectToJsonValueMap(schema))
        .build()
    return FunctionTool.builder()
        .name("test_tool")
        .description("Test tool")
        .parameters(parameters)
        .strict(false)
        .build()
}

private fun parseSingleParam(schema: JSONObject, name: String) =
    parseParams(schema).single { it.name == name }

private fun parseParams(schema: JSONObject) =
    LeapToolSchemaAdapter.toLeapFunction(buildFunctionTool(schema)).parameters

private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    obj.keys().forEach { key ->
        map[key] = jsonElementToValue(obj.get(key))
    }
    return map
}

private fun jsonElementToValue(value: Any?): Any? {
    return when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}

private fun jsonArrayToList(array: JSONArray): List<Any?> {
    return (0 until array.length()).map { idx ->
        jsonElementToValue(array.get(idx))
    }
}

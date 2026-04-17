package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.jsonObjectToJsonValueMap
import com.openai.core.JsonField
import com.openai.core.JsonMissing
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Verifies ToolParameterExtractor handles the three representations the OpenAI
 * SDK exposes for a FunctionTool's parameters: known, unknown-object, missing.
 */
class ToolParameterExtractorTest {

    @Test
    fun `known schema - extracts additionalProperties as JSONObject`() {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("click", "type")))
                })
            })
            put("required", JSONArray(listOf("action")))
        }
        val parameters = FunctionTool.Parameters.builder()
            .putAllAdditionalProperties(jsonObjectToJsonValueMap(schema))
            .build()
        val tool = FunctionTool.builder()
            .name("known_tool")
            .description("test")
            .parameters(parameters)
            .strict(false)
            .build()

        val result = ToolParameterExtractor.extract(tool)

        assertThat(result).isNotNull()
        assertThat(result!!.getString("type")).isEqualTo("object")
        val properties = result.getJSONObject("properties")
        val action = properties.getJSONObject("action")
        assertThat(action.getString("type")).isEqualTo("string")
        val enumValues = action.getJSONArray("enum")
        assertThat(enumValues.length()).isEqualTo(2)
        assertThat(enumValues.getString(0)).isEqualTo("click")
        assertThat(result.getJSONArray("required").getString(0)).isEqualTo("action")
    }

    @Test
    fun `raw JsonObject schema - passes through via asObject path`() {
        val schemaMap = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "text" to mapOf(
                    "type" to "string",
                    "description" to "Input text"
                )
            ),
            "required" to listOf("text")
        )
        @Suppress("UNCHECKED_CAST")
        val rawField = JsonValue.from(schemaMap) as JsonField<FunctionTool.Parameters>
        val tool = FunctionTool.builder()
            .name("raw_tool")
            .parameters(rawField)
            .strict(false)
            .build()

        val result = ToolParameterExtractor.extract(tool)

        assertThat(result).isNotNull()
        assertThat(result!!.getString("type")).isEqualTo("object")
        val text = result.getJSONObject("properties").getJSONObject("text")
        assertThat(text.getString("type")).isEqualTo("string")
        assertThat(text.getString("description")).isEqualTo("Input text")
    }

    @Test
    fun `missing parameters - returns null`() {
        @Suppress("UNCHECKED_CAST")
        val missing = JsonMissing.of() as JsonField<FunctionTool.Parameters>
        val tool = FunctionTool.builder()
            .name("missing_tool")
            .parameters(missing)
            .strict(false)
            .build()

        val result = ToolParameterExtractor.extract(tool)

        assertThat(result).isNull()
    }
}

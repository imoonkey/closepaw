package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.tool.jsonObjectToJsonValueMap
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject as OrgJsonObject
import org.junit.Test

class CodexRequestBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── buildRequestBody ──────────────────────────────────────────────────

    @Test
    fun `buildRequestBody sets required Codex fields`() {
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt = "You are a test agent.",
            inputItems = listOf(userMessage("hello")),
            tools = emptyList(),
            model = "gpt-5-codex"
        )
        val obj = parse(body)

        assertThat(obj["model"]!!.jsonPrimitive.content).isEqualTo("gpt-5-codex")
        assertThat(obj["stream"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(obj["store"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(obj["instructions"]!!.jsonPrimitive.content).isEqualTo("You are a test agent.")
        assertThat(obj["tool_choice"]!!.jsonPrimitive.content).isEqualTo("auto")
        assertThat(obj["parallel_tool_calls"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(obj["input"]).isInstanceOf(JsonArray::class.java)
        assertThat(obj["tools"]).isInstanceOf(JsonArray::class.java)
        // Codex endpoint does NOT accept max_output_tokens
        assertThat(obj.containsKey("max_output_tokens")).isFalse()
    }

    @Test
    fun `buildRequestBody with empty inputs and tools does not crash`() {
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt = "",
            inputItems = emptyList(),
            tools = emptyList(),
            model = "m"
        )
        val obj = parse(body)
        assertThat(obj["input"]!!.jsonArray).isEmpty()
        assertThat(obj["tools"]!!.jsonArray).isEmpty()
        assertThat(obj["instructions"]!!.jsonPrimitive.content).isEqualTo("")
    }

    // ── convertInputItems: user vs assistant ──────────────────────────────

    @Test
    fun `user message uses input_text content type`() {
        val arr = convertItems(userMessage("hi there"))
        val msg = arr.first().jsonObject

        assertThat(msg["role"]!!.jsonPrimitive.content).isEqualTo("user")
        val parts = msg["content"]!!.jsonArray
        assertThat(parts).hasSize(1)
        val part = parts[0].jsonObject
        assertThat(part["type"]!!.jsonPrimitive.content).isEqualTo("input_text")
        assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("hi there")
    }

    @Test
    fun `assistant message uses output_text content type`() {
        val arr = convertItems(assistantMessage("done"))
        val msg = arr.first().jsonObject

        assertThat(msg["role"]!!.jsonPrimitive.content).isEqualTo("assistant")
        val part = msg["content"]!!.jsonArray[0].jsonObject
        assertThat(part["type"]!!.jsonPrimitive.content).isEqualTo("output_text")
        assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("done")
    }

    // ── function_call / function_call_output ──────────────────────────────

    @Test
    fun `function_call item serializes name call_id and arguments`() {
        val item = ResponseInputItem.ofFunctionCall(
            ResponseFunctionToolCall.builder()
                .callId("call-42")
                .name("click")
                .arguments("""{"x":100,"y":200}""")
                .build()
        )
        val obj = convertItems(item).first().jsonObject

        assertThat(obj["type"]!!.jsonPrimitive.content).isEqualTo("function_call")
        assertThat(obj["call_id"]!!.jsonPrimitive.content).isEqualTo("call-42")
        assertThat(obj["name"]!!.jsonPrimitive.content).isEqualTo("click")
        assertThat(obj["arguments"]!!.jsonPrimitive.content).isEqualTo("""{"x":100,"y":200}""")
    }

    @Test
    fun `function_call_output item serializes call_id and output string`() {
        val item = ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId("call-42")
                .output("tool result text")
                .build()
        )
        val obj = convertItems(item).first().jsonObject

        assertThat(obj["type"]!!.jsonPrimitive.content).isEqualTo("function_call_output")
        assertThat(obj["call_id"]!!.jsonPrimitive.content).isEqualTo("call-42")
        assertThat(obj["output"]!!.jsonPrimitive.content).isEqualTo("tool result text")
    }

    // ── System prompt placement ───────────────────────────────────────────

    @Test
    fun `system prompt goes to instructions field not into input items`() {
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt = "SYS-PROMPT",
            inputItems = listOf(userMessage("u")),
            tools = emptyList(),
            model = "m"
        )
        val obj = parse(body)

        assertThat(obj["instructions"]!!.jsonPrimitive.content).isEqualTo("SYS-PROMPT")
        val input = obj["input"]!!.jsonArray
        // Only the user message — no system role entry should be injected here.
        assertThat(input).hasSize(1)
        assertThat(input[0].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
    }

    // ── convertTools ──────────────────────────────────────────────────────

    @Test
    fun `convertTools produces valid JSON Schema for each tool`() {
        val schemaA = OrgJsonObject().apply {
            put("type", "object")
            put("properties", OrgJsonObject().apply {
                put("x", OrgJsonObject().put("type", "integer"))
            })
        }
        val schemaB = OrgJsonObject().apply {
            put("type", "object")
            put("properties", OrgJsonObject().apply {
                put("text", OrgJsonObject().put("type", "string"))
            })
        }

        val tools = listOf(
            buildTool("click", "click element", schemaA),
            buildTool("type_text", "type text", schemaB)
        )
        val arr = CodexRequestBuilder.convertTools(tools)
        val parsed = json.parseToJsonElement(arr.toString()).jsonArray

        assertThat(parsed).hasSize(2)

        val first = parsed[0].jsonObject
        assertThat(first["type"]!!.jsonPrimitive.content).isEqualTo("function")
        assertThat(first["name"]!!.jsonPrimitive.content).isEqualTo("click")
        assertThat(first["description"]!!.jsonPrimitive.content).isEqualTo("click element")
        val paramsA = first["parameters"]!!.jsonObject
        assertThat(paramsA["type"]!!.jsonPrimitive.content).isEqualTo("object")
        assertThat(paramsA["properties"]!!.jsonObject["x"]!!.jsonObject["type"]!!
            .jsonPrimitive.content).isEqualTo("integer")

        val second = parsed[1].jsonObject
        assertThat(second["name"]!!.jsonPrimitive.content).isEqualTo("type_text")
        val paramsB = second["parameters"]!!.jsonObject
        assertThat(paramsB["properties"]!!.jsonObject["text"]!!.jsonObject["type"]!!
            .jsonPrimitive.content).isEqualTo("string")
    }

    @Test
    fun `convertTools with missing description emits empty string`() {
        val tool = FunctionTool.builder()
            .name("no_desc")
            .parameters(
                FunctionTool.Parameters.builder()
                    .putAllAdditionalProperties(jsonObjectToJsonValueMap(OrgJsonObject().apply {
                        put("type", "object")
                    }))
                    .build()
            )
            .strict(false)
            .build()

        val arr = CodexRequestBuilder.convertTools(listOf(tool))
        val obj = json.parseToJsonElement(arr.toString()).jsonArray[0].jsonObject

        assertThat(obj["name"]!!.jsonPrimitive.content).isEqualTo("no_desc")
        // description is optional upstream; builder defaults it to ""
        assertThat(obj["description"]!!.jsonPrimitive.contentOrNull).isEqualTo("")
        assertThat(obj["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("object")
    }

    @Test
    fun `convertTools empty list produces empty array`() {
        val arr = CodexRequestBuilder.convertTools(emptyList())
        assertThat(arr.length()).isEqualTo(0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun parse(body: String): JsonObject =
        json.parseToJsonElement(body).jsonObject

    private fun convertItems(vararg items: ResponseInputItem): JsonArray {
        val arr = CodexRequestBuilder.convertInputItems(items.toList())
        return json.parseToJsonElement(arr.toString()).jsonArray
    }

    private fun userMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build()
        )

    private fun assistantMessage(text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.ASSISTANT)
                .content(text)
                .build()
        )

    private fun buildTool(name: String, description: String, schema: OrgJsonObject): FunctionTool {
        val parameters = FunctionTool.Parameters.builder()
            .putAllAdditionalProperties(jsonObjectToJsonValueMap(schema))
            .build()
        return FunctionTool.builder()
            .name(name)
            .description(description)
            .parameters(parameters)
            .strict(false)
            .build()
    }
}

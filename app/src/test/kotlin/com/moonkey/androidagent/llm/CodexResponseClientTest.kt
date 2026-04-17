package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.auth.OAuthCodexValidator
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Direct unit tests for [CodexResponseClient].
 *
 * Covers the pure logic paths that don't require real HTTP:
 * - Constructor validation of the OAuth access token
 * - Codex-specific request construction (headers + body shape)
 * - Error-response classification (handleErrorResponse)
 * - Streaming event mapping used by chatWithToolsStreaming
 */
class CodexResponseClientTest {

    private val token = "header.payload.sig"
    private val accountId = "acct-test-1234"

    @Before
    fun setUp() {
        mockkObject(OAuthCodexValidator)
        every { OAuthCodexValidator.extractAccountId(token) } returns accountId
        every { OAuthCodexValidator.extractAccountId("invalid") } returns null
    }

    @After
    fun tearDown() {
        unmockkObject(OAuthCodexValidator)
    }

    // ── Construction ──────────────────────────────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun `constructor throws when token has no account id`() {
        CodexResponseClient("invalid")
    }

    @Test
    fun `constructor succeeds with valid JWT containing account id`() {
        val client = CodexResponseClient(token)
        assertThat(client.isReady()).isTrue()
    }

    // ── Request construction ──────────────────────────────────────────────

    @Test
    fun `buildRequest adds all required Codex headers`() {
        val client = CodexResponseClient(token)
        val request = invokeBuildRequest(client, """{"foo":"bar"}""")

        assertThat(request.url.toString()).isEqualTo(
            "https://chatgpt.com/backend-api/codex/responses"
        )
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.header("Authorization")).isEqualTo("Bearer $token")
        assertThat(request.header("chatgpt-account-id")).isEqualTo(accountId)
        assertThat(request.header("originator")).isEqualTo("pi")
        assertThat(request.header("OpenAI-Beta")).isEqualTo("responses=experimental")
        assertThat(request.header("Accept")).isEqualTo("text/event-stream")
        assertThat(request.header("Content-Type")).isEqualTo("application/json")
        // ChatGPT backend rejects charset=utf-8 on the body's media type
        assertThat(request.body?.contentType()).isNull()
    }

    @Test
    fun `request body matches Codex wire format`() {
        // CodexResponseClient delegates body serialization to CodexRequestBuilder,
        // so asserting on its output is asserting what the client actually sends.
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt = "be helpful",
            inputItems = emptyList<ResponseInputItem>(),
            tools = emptyList<FunctionTool>(),
            model = "gpt-5.4"
        )
        val json = JSONObject(body)

        assertThat(json.getString("model")).isEqualTo("gpt-5.4")
        assertThat(json.getBoolean("stream")).isTrue()
        assertThat(json.getBoolean("store")).isFalse()
        assertThat(json.getString("instructions")).isEqualTo("be helpful")
        assertThat(json.getString("tool_choice")).isEqualTo("auto")
        assertThat(json.getBoolean("parallel_tool_calls")).isTrue()
        assertThat(json.has("max_output_tokens")).isFalse()
    }

    // ── Error response classification ─────────────────────────────────────

    @Test(expected = RateLimitException::class)
    fun `429 response maps to RateLimitException`() {
        val client = CodexResponseClient(token)
        val response = mockResponse(429, """{"error":{"code":"rate_limit_exceeded","message":"slow down"}}""")
        invokeHandleErrorResponse(client, response)
    }

    @Test(expected = RateLimitException::class)
    fun `usage_limit error code maps to RateLimitException regardless of status`() {
        val client = CodexResponseClient(token)
        val response = mockResponse(
            200,
            """{"error":{"code":"usage_limit_reached","message":"plan exceeded","plan_type":"plus"}}"""
        )
        invokeHandleErrorResponse(client, response)
    }

    @Test
    fun `401 maps to IllegalStateException mentioning token`() {
        val client = CodexResponseClient(token)
        val response = mockResponse(401, """{"error":{"message":"bad token"}}""")
        val ex = runCatching { invokeHandleErrorResponse(client, response) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        assertThat(ex!!.message).contains("Token rejected")
    }

    @Test(expected = TransientException::class)
    fun `5xx maps to TransientException`() {
        val client = CodexResponseClient(token)
        val response = mockResponse(503, "service unavailable")
        invokeHandleErrorResponse(client, response)
    }

    @Test
    fun `other 4xx maps to plain RuntimeException (not retryable)`() {
        val client = CodexResponseClient(token)
        val response = mockResponse(400, """{"error":{"message":"bad request"}}""")
        val ex = runCatching { invokeHandleErrorResponse(client, response) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex).isNotInstanceOf(TransientException::class.java)
        assertThat(ex).isNotInstanceOf(RateLimitException::class.java)
    }

    // ── Streaming finish / terminal events ────────────────────────────────
    //
    // CodexResponseClient.chatWithToolsStreaming delegates event → LLMStreamEvent
    // mapping to CodexSseParser.mapToStreamEvent. These tests pin the finish
    // semantics the client relies on: done/completed → Completed, failed/error
    // → Failed (classified error, never a raw exception bubbled to the flow).

    @Test
    fun `response_completed event maps to Completed`() {
        val ev = CodexSseParser.SseEvent(
            "response.completed",
            JSONObject("""{"type":"response.completed"}""")
        )
        val result = CodexSseParser.mapToStreamEvent(ev, CodexSseParser.ToolCallAccumulator())
        assertThat(result).isEqualTo(LLMStreamEvent.Completed)
    }

    @Test
    fun `response_done event maps to Completed`() {
        val ev = CodexSseParser.SseEvent(
            "response.done",
            JSONObject("""{"type":"response.done"}""")
        )
        val result = CodexSseParser.mapToStreamEvent(ev, CodexSseParser.ToolCallAccumulator())
        assertThat(result).isEqualTo(LLMStreamEvent.Completed)
    }

    @Test
    fun `response_failed event maps to Failed with error message`() {
        val ev = CodexSseParser.SseEvent(
            "response.failed",
            JSONObject(
                """{"type":"response.failed","response":{"error":{"message":"boom"}}}"""
            )
        )
        val result = CodexSseParser.mapToStreamEvent(ev, CodexSseParser.ToolCallAccumulator())
        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("boom")
    }

    @Test
    fun `raw error event maps to Failed (classified, not raw exception)`() {
        val ev = CodexSseParser.SseEvent(
            "error",
            JSONObject("""{"type":"error","message":"server kaput"}""")
        )
        val result = CodexSseParser.mapToStreamEvent(ev, CodexSseParser.ToolCallAccumulator())
        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("server kaput")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun invokeBuildRequest(client: CodexResponseClient, body: String): Request {
        val m = CodexResponseClient::class.java
            .getDeclaredMethod("buildRequest", String::class.java)
        m.isAccessible = true
        return m.invoke(client, body) as Request
    }

    private fun invokeHandleErrorResponse(client: CodexResponseClient, response: Response) {
        val m = CodexResponseClient::class.java
            .getDeclaredMethod("handleErrorResponse", Response::class.java)
        m.isAccessible = true
        try {
            m.invoke(client, response)
        } catch (ite: java.lang.reflect.InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }

    private fun mockResponse(code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url("https://chatgpt.com/backend-api/codex/responses").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}

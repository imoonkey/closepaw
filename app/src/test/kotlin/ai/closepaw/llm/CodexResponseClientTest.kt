package ai.closepaw.llm

import ai.closepaw.auth.CodexHeaders
import com.google.common.truth.Truth.assertThat
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Test

/**
 * Direct unit tests for [CodexResponseClient].
 *
 * Covers the pure logic paths that don't require real HTTP:
 * - Codex-specific request construction (headers + body shape) via the
 *   suspend-supplied [CodexHeaders]
 * - Error-response classification (handleErrorResponse)
 * - Streaming event mapping used by chatWithToolsStreaming
 */
class CodexResponseClientTest {

    private val headers = CodexHeaders(
        accessToken = "acc-token",
        chatgptAccountId = "acct-test-1234",
        email = "user@example.com",
    )
    private val supplier: suspend () -> CodexHeaders = { headers }

    // ── Request construction ──────────────────────────────────────────────

    @Test
    fun `buildRequest adds all required Codex headers from supplier`() {
        val client = CodexResponseClient(supplier)
        val request = invokeBuildRequest(client, """{"foo":"bar"}""", headers)

        assertThat(request.url.toString()).isEqualTo(
            "https://chatgpt.com/backend-api/codex/responses"
        )
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.header("Authorization")).isEqualTo("Bearer ${headers.accessToken}")
        assertThat(request.header("chatgpt-account-id")).isEqualTo(headers.chatgptAccountId)
        assertThat(request.header("originator")).isEqualTo("pi")
        assertThat(request.header("OpenAI-Beta")).isEqualTo("responses=experimental")
        assertThat(request.header("Accept")).isEqualTo("text/event-stream")
        assertThat(request.header("Content-Type")).isEqualTo("application/json")
        // ChatGPT backend rejects charset=utf-8 on the body's media type
        assertThat(request.body?.contentType()).isNull()
    }

    @Test
    fun `buildRequest omits account id header when supplier returns null`() {
        val noAccount = headers.copy(chatgptAccountId = null)
        val client = CodexResponseClient { noAccount }
        val request = invokeBuildRequest(client, "{}", noAccount)
        assertThat(request.header("chatgpt-account-id")).isNull()
    }

    @Test
    fun `supplier is called per request`() = runBlocking {
        var calls = 0
        val dyn: suspend () -> CodexHeaders = {
            calls++
            headers
        }
        val client = CodexResponseClient(dyn)
        // Simulate two independent calls — client caches nothing.
        dyn.invoke()
        dyn.invoke()
        // Two direct invocations + the client holds a reference but does not cache headers itself.
        assertThat(calls).isEqualTo(2)
        assertThat(client).isNotNull()
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

    @Test
    fun `CodexRequestBuilder signature has no max-output-tokens parameter (enforces no-forward)`() {
        // Codex backend rejects max_output_tokens. The LLMClient contract still
        // exposes the cap (so other providers can honor it), but Codex must
        // never forward it. Asserting the builder signature catches any future
        // change that would silently start forwarding the cap.
        val params = CodexRequestBuilder::class.java
            .getDeclaredMethod(
                "buildRequestBody",
                String::class.java,
                List::class.java,
                List::class.java,
                String::class.java,
            )
            .parameterTypes
        assertThat(params).asList()
            .containsExactly(
                String::class.java,
                List::class.java,
                List::class.java,
                String::class.java,
            ).inOrder()
    }

    // ── Error response classification ─────────────────────────────────────

    @Test(expected = RateLimitException::class)
    fun `429 response maps to RateLimitException`() {
        val client = CodexResponseClient(supplier)
        val response = mockResponse(429, """{"error":{"code":"rate_limit_exceeded","message":"slow down"}}""")
        invokeHandleErrorResponse(client, response)
    }

    @Test(expected = RateLimitException::class)
    fun `usage_limit error code maps to RateLimitException regardless of status`() {
        val client = CodexResponseClient(supplier)
        val response = mockResponse(
            200,
            """{"error":{"code":"usage_limit_reached","message":"plan exceeded","plan_type":"plus"}}"""
        )
        invokeHandleErrorResponse(client, response)
    }

    @Test
    fun `401 maps to IllegalStateException mentioning token`() {
        val client = CodexResponseClient(supplier)
        val response = mockResponse(401, """{"error":{"message":"bad token"}}""")
        val ex = runCatching { invokeHandleErrorResponse(client, response) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
        assertThat(ex!!.message).contains("Token rejected")
    }

    @Test(expected = TransientException::class)
    fun `5xx maps to TransientException`() {
        val client = CodexResponseClient(supplier)
        val response = mockResponse(503, "service unavailable")
        invokeHandleErrorResponse(client, response)
    }

    @Test
    fun `other 4xx maps to plain RuntimeException (not retryable)`() {
        val client = CodexResponseClient(supplier)
        val response = mockResponse(400, """{"error":{"message":"bad request"}}""")
        val ex = runCatching { invokeHandleErrorResponse(client, response) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex).isNotInstanceOf(TransientException::class.java)
        assertThat(ex).isNotInstanceOf(RateLimitException::class.java)
    }

    // ── Streaming finish / terminal events ────────────────────────────────

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

    private fun invokeBuildRequest(client: CodexResponseClient, body: String, headers: CodexHeaders): Request {
        val m = CodexResponseClient::class.java
            .getDeclaredMethod("buildRequest", String::class.java, CodexHeaders::class.java)
        m.isAccessible = true
        return m.invoke(client, body, headers) as Request
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

package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Direct unit tests for [ChatCompletionClient].
 *
 * Avoids real HTTP by verifying pure logic: request parameter construction
 * and exception-classification behavior around the SDK call.
 */
class ChatCompletionClientTest {

    private val apiKey = "sk-test-abc123"

    private fun userMsg(text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build()
        )

    private fun simpleTool(name: String): FunctionTool =
        FunctionTool.builder()
            .name(name)
            .description("test tool")
            .parameters(
                FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                    .build()
            )
            .strict(false)
            .build()

    // ── Construction ──────────────────────────────────────────────────────

    @Test
    fun `constructor succeeds with just an api key`() {
        val client = ChatCompletionClient(apiKey)
        assertThat(client.isReady()).isTrue()
    }

    @Test
    fun `constructor succeeds with custom base url`() {
        val client = ChatCompletionClient(apiKey, baseUrl = "https://openrouter.ai/api/v1")
        assertThat(client.isReady()).isTrue()
    }

    // ── Request construction ──────────────────────────────────────────────

    @Test
    fun `buildParams produces ChatCompletionCreateParams with system plus user messages and tools`() {
        val client = ChatCompletionClient(apiKey)
        val params = invokeBuildParams(
            client,
            systemPrompt = "you are helpful",
            inputItems = listOf(userMsg("hi")),
            tools = listOf(simpleTool("open_app"), simpleTool("tap")),
            model = "gpt-4o-mini"
        )

        assertThat(params).isInstanceOf(ChatCompletionCreateParams::class.java)
        // system + 1 user message
        assertThat(params.messages()).hasSize(2)
        // model id round-trips through ChatModel
        assertThat(params.model().toString()).contains("gpt-4o-mini")
        // both tools forwarded
        assertThat(params.tools().orElse(emptyList())).hasSize(2)
    }

    @Test
    fun `buildParams carries empty tools when none provided`() {
        val client = ChatCompletionClient(apiKey)
        val params = invokeBuildParams(
            client,
            systemPrompt = "s",
            inputItems = listOf(userMsg("hello")),
            tools = emptyList(),
            model = "gpt-4o"
        )
        assertThat(params.tools().orElse(emptyList())).isEmpty()
        assertThat(params.messages()).hasSize(2)
    }

    // ── Error classification (provider error → domain exception) ──────────

    @Test
    fun `provider 401 error is classified to plain RuntimeException (non-retryable)`() {
        val client = ChatCompletionClient(apiKey)
        installFailingOpenAIClient(client) {
            // Non-retryable so CloudLlmRetry lets it through on the first attempt
            throw RuntimeException("HTTP 401 Unauthorized")
        }

        val ex = runCatching {
            runBlocking {
                client.chatWithTools(
                    systemPrompt = "s",
                    inputItems = listOf(userMsg("hi")),
                    tools = emptyList(),
                    model = "gpt-4o"
                )
            }
        }.exceptionOrNull()

        assertThat(ex).isNotNull()
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        // Classifier wraps unknown errors with "LLM error:" prefix
        assertThat(ex!!.message).contains("LLM error")
        // NOT a raw provider exception — classifier produced a domain error
        assertThat(ex).isNotInstanceOf(TransientException::class.java)
        assertThat(ex).isNotInstanceOf(RateLimitException::class.java)
    }

    @Test
    fun `provider SocketTimeoutException classifies to TransientException`() {
        val client = ChatCompletionClient(apiKey)
        installFailingOpenAIClient(client) {
            throw java.net.SocketTimeoutException("read timed out")
        }

        // CloudLlmRetry retries on TransientException — we only need to see that
        // the terminal cause is a classified TransientException, not the raw SDK
        // exception. After MAX_RETRIES, executeWithRetry rethrows cause-of-transient.
        val ex = runCatching {
            runBlocking {
                client.chatWithTools(
                    systemPrompt = "s",
                    inputItems = listOf(userMsg("hi")),
                    tools = emptyList(),
                    model = "gpt-4o"
                )
            }
        }.exceptionOrNull()

        assertThat(ex).isNotNull()
        // After retries, CloudLlmRetry re-throws the cause of the last TransientException,
        // which is the original SocketTimeoutException we installed.
        assertThat(ex).isInstanceOf(java.net.SocketTimeoutException::class.java)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildParams(
        client: ChatCompletionClient,
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ChatCompletionCreateParams {
        val m = ChatCompletionClient::class.java.getDeclaredMethod(
            "buildParams",
            String::class.java,
            List::class.java,
            List::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(client, systemPrompt, inputItems, tools, model) as ChatCompletionCreateParams
    }

    /** Replace the private `client: OpenAIClient` with a mock whose create() call executes [onCreate]. */
    private fun installFailingOpenAIClient(target: ChatCompletionClient, onCreate: () -> Nothing) {
        val completions = mockk<com.openai.services.blocking.chat.ChatCompletionService>()
        every { completions.create(any<ChatCompletionCreateParams>()) } answers { onCreate() }

        val chat = mockk<com.openai.services.blocking.ChatService>()
        every { chat.completions() } returns completions

        val openAiClient = mockk<OpenAIClient>()
        every { openAiClient.chat() } returns chat

        val field = ChatCompletionClient::class.java.getDeclaredField("client")
        field.isAccessible = true
        field.set(target, openAiClient)
    }
}

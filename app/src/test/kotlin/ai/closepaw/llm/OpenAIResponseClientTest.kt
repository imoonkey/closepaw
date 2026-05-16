package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import org.junit.Test

/**
 * Direct unit tests for [OpenAIResponseClient].
 *
 * Asserts that [OpenAIResponseClient.buildResponseParams] threads the
 * optional max-output-tokens cap into the Responses API request.
 */
class OpenAIResponseClientTest {

    private val apiKey = "sk-test-abc123"

    private fun userMsg(text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build()
        )

    @Test
    fun `buildResponseParams forwards maxOutputTokens to Responses API`() {
        val client = OpenAIResponseClient(apiKey)
        val params = invokeBuildResponseParams(
            client,
            systemPrompt = "s",
            inputItems = listOf(userMsg("hi")),
            tools = emptyList(),
            model = "gpt-4o",
            maxOutputTokens = 5_000L,
        )
        assertThat(params.maxOutputTokens().orElse(null)).isEqualTo(5_000L)
    }

    @Test
    fun `buildResponseParams omits maxOutputTokens when cap is null`() {
        val client = OpenAIResponseClient(apiKey)
        val params = invokeBuildResponseParams(
            client,
            systemPrompt = "s",
            inputItems = listOf(userMsg("hi")),
            tools = emptyList(),
            model = "gpt-4o",
            maxOutputTokens = null,
        )
        assertThat(params.maxOutputTokens().isPresent).isFalse()
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildResponseParams(
        client: OpenAIResponseClient,
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String,
        maxOutputTokens: Long?,
    ): ResponseCreateParams {
        val m = OpenAIResponseClient::class.java.getDeclaredMethod(
            "buildResponseParams",
            String::class.java,
            List::class.java,
            List::class.java,
            String::class.java,
            java.lang.Long::class.java,
        )
        m.isAccessible = true
        return m.invoke(client, systemPrompt, inputItems, tools, model, maxOutputTokens) as ResponseCreateParams
    }
}

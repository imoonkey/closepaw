package ai.closepaw.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMClientFactoryTest {

    private val catalogJson = """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider": "OPENAI",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-chat": {
            "display_name": "GPT-5.2 (Chat)",
            "provider": "OPENAI",
            "api": "chat",
            "model_id": "gpt-5.2"
          },
          "glm-4.7": {
            "display_name": "GLM-4.7",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "zhipu-ai/glm-4.7"
          }
        }
    """.trimIndent()

    private val catalog = ModelCatalog.fromJson(catalogJson)

    private val resolver: (String) -> String? = { envVar ->
        when (envVar) {
            "OPENAI_API_KEY" -> "sk-test-key"
            "OPENROUTER_API_KEY" -> "sk-or-test-key"
            else -> null
        }
    }

    @Test
    fun `create returns OpenAIResponseClient for response api`() {
        val factory = LLMClientFactory(catalog, resolver)
        val client = factory.create("gpt-5.2")
        assertTrue(client is OpenAIResponseClient)
    }

    @Test
    fun `create returns ChatCompletionClient for chat api`() {
        val factory = LLMClientFactory(catalog, resolver)
        val client = factory.create("gpt-5.2-chat")
        assertTrue(client is ChatCompletionClient)
    }

    @Test
    fun `create caches clients by provider and api type`() {
        val factory = LLMClientFactory(catalog, resolver)
        val client1 = factory.create("gpt-5.2")
        val client2 = factory.create("gpt-5.2") // Same provider+api → same client
        assertSame(client1, client2)
    }

    @Test
    fun `different api types get different clients`() {
        val factory = LLMClientFactory(catalog, resolver)
        val responseClient = factory.create("gpt-5.2")
        val chatClient = factory.create("gpt-5.2-chat")
        assertTrue(responseClient is OpenAIResponseClient)
        assertTrue(chatClient is ChatCompletionClient)
    }

    @Test
    fun `different providers get different clients`() {
        val factory = LLMClientFactory(catalog, resolver)
        val openaiClient = factory.create("gpt-5.2-chat")
        val openrouterClient = factory.create("glm-4.7")
        // Both are ChatCompletionClient but different instances (different providers)
        assertTrue(openaiClient !== openrouterClient)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create throws for unknown model`() {
        val factory = LLMClientFactory(catalog, resolver)
        factory.create("nonexistent-model")
    }

    @Test(expected = IllegalStateException::class)
    fun `create throws when api key not found`() {
        val noKeyResolver: (String) -> String? = { null }
        val factory = LLMClientFactory(catalog, noKeyResolver)
        factory.create("gpt-5.2")
    }

    @Test
    fun `catalog resolution passes correct model entry`() {
        val entry = catalog.resolve("glm-4.7")
        assertEquals(LLMProvider.OPENROUTER, entry.provider)
        assertEquals(ApiType.CHAT, entry.api)
        assertEquals("zhipu-ai/glm-4.7", entry.modelId)
        assertEquals("https://openrouter.ai/api/v1", entry.effectiveBaseUrl)
    }
}

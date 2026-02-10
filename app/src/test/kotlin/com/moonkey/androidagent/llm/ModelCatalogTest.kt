package com.moonkey.androidagent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val sampleJson = """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider": "OPENAI",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-chat": {
            "display_name": "GPT-5.2 (Chat API)",
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

    @Test
    fun `fromJson parses valid catalog`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        assertEquals(3, catalog.size)
        assertTrue("gpt-5.2" in catalog)
        assertTrue("gpt-5.2-chat" in catalog)
        assertTrue("glm-4.7" in catalog)
    }

    @Test
    fun `resolve returns correct entry for known model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val entry = catalog.resolve("gpt-5.2")

        assertEquals("gpt-5.2", entry.name)
        assertEquals("GPT-5.2", entry.displayName)
        assertEquals(LLMProvider.OPENAI, entry.provider)
        assertEquals(ApiType.RESPONSE, entry.api)
        assertEquals("gpt-5.2", entry.modelId)
        assertNull(entry.baseUrl)
        assertNull(entry.apiKeyEnv)
        assertTrue(entry.supportsVision)
    }

    @Test
    fun `resolve returns correct entry for chat API model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val entry = catalog.resolve("gpt-5.2-chat")

        assertEquals(ApiType.CHAT, entry.api)
        assertEquals(LLMProvider.OPENAI, entry.provider)
        assertEquals("gpt-5.2", entry.modelId)
    }

    @Test
    fun `resolve returns correct entry for OpenRouter model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val entry = catalog.resolve("glm-4.7")

        assertEquals("GLM-4.7", entry.displayName)
        assertEquals(LLMProvider.OPENROUTER, entry.provider)
        assertEquals(ApiType.CHAT, entry.api)
        assertEquals("zhipu-ai/glm-4.7", entry.modelId)
        assertEquals("https://openrouter.ai/api/v1", entry.effectiveBaseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolve throws for unknown model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        catalog.resolve("nonexistent-model")
    }

    @Test
    fun `resolveOrNull returns entry for known model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val entry = catalog.resolveOrNull("gpt-5.2")
        assertNotNull(entry)
        assertEquals("gpt-5.2", entry!!.modelId)
    }

    @Test
    fun `resolveOrNull returns null for unknown model`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        assertNull(catalog.resolveOrNull("nonexistent-model"))
    }

    @Test
    fun `all returns entries in insertion order`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val names = catalog.all().map { it.name }
        assertEquals(listOf("gpt-5.2", "gpt-5.2-chat", "glm-4.7"), names)
    }

    @Test
    fun `names returns all model names`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        assertEquals(setOf("gpt-5.2", "gpt-5.2-chat", "glm-4.7"), catalog.names())
    }

    @Test
    fun `contains returns true for known models`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        assertTrue("gpt-5.2" in catalog)
        assertFalse("unknown" in catalog)
    }

    // ── ModelEntry computed properties ──────────────────────────────────

    @Test
    fun `effectiveApiKeyEnv uses provider default when entry has no override`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val openai = catalog.resolve("gpt-5.2")
        val openrouter = catalog.resolve("glm-4.7")

        assertEquals("OPENAI_API_KEY", openai.effectiveApiKeyEnv)
        assertEquals("OPENROUTER_API_KEY", openrouter.effectiveApiKeyEnv)
    }

    @Test
    fun `effectiveApiKeyEnv uses entry override when present`() {
        val json = """
            {
              "custom": {
                "display_name": "Custom",
                "provider": "OPENAI",
                "api": "chat",
                "model_id": "custom-model",
                "api_key_env": "MY_CUSTOM_KEY"
              }
            }
        """.trimIndent()
        val entry = ModelCatalog.fromJson(json).resolve("custom")
        assertEquals("MY_CUSTOM_KEY", entry.effectiveApiKeyEnv)
    }

    @Test
    fun `effectiveBaseUrl uses provider default when entry has no override`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        val openai = catalog.resolve("gpt-5.2")
        val openrouter = catalog.resolve("glm-4.7")

        assertNull(openai.effectiveBaseUrl) // OpenAI uses SDK default
        assertEquals("https://openrouter.ai/api/v1", openrouter.effectiveBaseUrl)
    }

    @Test
    fun `effectiveBaseUrl uses entry override when present`() {
        val json = """
            {
              "local": {
                "display_name": "Local vLLM",
                "provider": "OPENAI",
                "api": "chat",
                "model_id": "my-model",
                "base_url": "http://localhost:8000/v1"
              }
            }
        """.trimIndent()
        val entry = ModelCatalog.fromJson(json).resolve("local")
        assertEquals("http://localhost:8000/v1", entry.effectiveBaseUrl)
    }

    // ── Error handling ──────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects empty catalog`() {
        ModelCatalog.fromJson("{}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects unknown provider`() {
        ModelCatalog.fromJson("""
            {
              "bad": {
                "display_name": "Bad",
                "provider": "UNKNOWN_PROVIDER",
                "api": "chat",
                "model_id": "bad"
              }
            }
        """.trimIndent())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects unknown api type`() {
        ModelCatalog.fromJson("""
            {
              "bad": {
                "display_name": "Bad",
                "provider": "OPENAI",
                "api": "graphql",
                "model_id": "bad"
              }
            }
        """.trimIndent())
    }

    @Test
    fun `supportsVision defaults to true`() {
        val catalog = ModelCatalog.fromJson(sampleJson)
        assertTrue(catalog.resolve("gpt-5.2").supportsVision)
    }

    @Test
    fun `supportsVision can be set to false`() {
        val json = """
            {
              "no-vision": {
                "display_name": "No Vision",
                "provider": "OPENAI",
                "api": "chat",
                "model_id": "text-only",
                "supports_vision": false
              }
            }
        """.trimIndent()
        val entry = ModelCatalog.fromJson(json).resolve("no-vision")
        assertFalse(entry.supportsVision)
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun `fromJson throws for malformed JSON`() {
        ModelCatalog.fromJson("[not valid json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromJson rejects blank model_id`() {
        ModelCatalog.fromJson("""
            {
              "bad": {
                "display_name": "Bad",
                "provider": "OPENAI",
                "api": "chat",
                "model_id": "  "
              }
            }
        """.trimIndent())
    }

    @Test
    fun `fromJson ignores unknown fields gracefully`() {
        val json = """
            {
              "model": {
                "display_name": "Model",
                "provider": "OPENAI",
                "api": "response",
                "model_id": "m",
                "future_field": "should be ignored"
              }
            }
        """.trimIndent()
        val catalog = ModelCatalog.fromJson(json)
        assertNotNull(catalog.resolve("model"))
    }
}

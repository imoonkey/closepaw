package com.moonkey.androidagent.session

import android.content.Context
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.llm.ChatCompletionClient
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.test.FakeAndroidPlatform
import com.moonkey.androidagent.trace.NoopTraceRecorder
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionServicesProviderRoutingTest {

    @Test
    fun `openrouter model works without openai key`() {
        val context = contextWithCatalog()
        @Suppress("DEPRECATION")
        val config =
                SessionConfig(
                        llmBackend = LLMBackendType.OPENAI,
                        mainModel = "glm-4.7",
                        maxTurns = 1
                )
        val services =
                SessionServices.create(
                        config = config,
                        platform = FakeAndroidPlatform(),
                        apiKeys = mapOf("OPENROUTER_API_KEY" to "sk-or-test"),
                        context = context,
                        traceRecorder = NoopTraceRecorder
                )

        assertThat(services.llmClient).isInstanceOf(ChatCompletionClient::class.java)
    }

    @Test
    fun `pro mode validates executor model provider key`() {
        val context = contextWithCatalog()
        @Suppress("DEPRECATION")
        val config =
                SessionConfig(
                        llmBackend = LLMBackendType.OPENAI,
                        mainModel = "gpt-5.2",
                        executorModel = "autoglm-phone-9b-multilingual",
                        agentMode = AgentMode.PRO,
                        maxTurns = 1
                )

        val error =
                assertThrows(IllegalStateException::class.java) {
                    SessionServices.create(
                        config = config,
                        platform = FakeAndroidPlatform(),
                        apiKeys = mapOf("OPENAI_API_KEY" to "sk-openai-test"),
                        context = context,
                        traceRecorder = NoopTraceRecorder
                    )
                }
        assertThat(error.message).contains("NOVITA_API_KEY")
    }

    private fun contextWithCatalog(): Context {
        val context = mockk<Context>(relaxed = true)
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.open("llm_models.json") } answers {
            ByteArrayInputStream(CATALOG_JSON.toByteArray())
        }
        return context
    }

    companion object {
        private val CATALOG_JSON =
                """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider": "OPENAI",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "glm-4.7": {
            "display_name": "GLM-4.7",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "z-ai/glm-4.7"
          },
          "autoglm-phone-9b-multilingual": {
            "display_name": "AutoGLM Phone 9B Multilingual",
            "provider": "NOVITA",
            "api": "chat",
            "model_id": "zai-org/autoglm-phone-9b-multilingual"
          }
        }
        """.trimIndent()
    }
}

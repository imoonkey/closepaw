package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.llm.ResponsesResult
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class AgentModelResolverTest {

        @Test
        fun `known catalog model uses factory metadata and client`() {
                val sessionClient = FakeTestLLMClient()
                val catalog =
                        ModelCatalog.fromJson(
                                """
                {
                  "test-model": {
                    "display_name": "Test Model",
                    "provider": "OPENAI",
                    "api": "response",
                    "model_id": "provider-model-id",
                    "supports_vision": true
                  }
                }
                """
                        )
                val factory = LLMClientFactory(catalog = catalog, apiKeyResolver = { "test-key" })
                val resolver = AgentModelResolver(sessionClient, catalog, factory)

                val resolved = resolver.resolve("test-model")

                assertThat(resolved.modelId).isEqualTo("provider-model-id")
                assertThat(resolved.supportsVision).isTrue()
                assertThat(resolved.llmClient).isNotSameInstanceAs(sessionClient)
        }

        @Test
        fun `unknown model falls back to session client`() {
                val sessionClient = FakeTestLLMClient()
                val catalog =
                        ModelCatalog.fromJson(
                                """
                {
                  "known-model": {
                    "display_name": "Known Model",
                    "provider": "OPENAI",
                    "api": "response",
                    "model_id": "known-model-id"
                  }
                }
                """
                        )
                val factory = LLMClientFactory(catalog = catalog, apiKeyResolver = { "test-key" })
                val resolver = AgentModelResolver(sessionClient, catalog, factory)

                val resolved = resolver.resolve("legacy-local-model")

                assertThat(resolved.llmClient).isSameInstanceAs(sessionClient)
                assertThat(resolved.modelId).isEqualTo("legacy-local-model")
                assertThat(resolved.supportsVision).isFalse()
        }
}

private class FakeTestLLMClient : LLMClient() {
        override suspend fun chatWithTools(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): ResponsesResult {
                error("Not used in tests")
        }

        override fun chatWithToolsStreaming(
                systemPrompt: String,
                inputItems: List<ResponseInputItem>,
                tools: List<FunctionTool>,
                model: String
        ): Flow<LLMStreamEvent> = emptyFlow()
}

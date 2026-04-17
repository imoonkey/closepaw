package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ResponsesResult
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

        @Test
        fun `known model falls back to session client when factory cannot build client`() {
                val sessionClient = FakeTestLLMClient()
                val catalog =
                        ModelCatalog.fromJson(
                                """
                {
                  "known-model": {
                    "display_name": "Known Model",
                    "provider": "OPENAI",
                    "api": "response",
                    "model_id": "known-model-id",
                    "supports_vision": true
                  }
                }
                """
                        )
                val factory = LLMClientFactory(catalog = catalog, apiKeyResolver = { null })
                val resolver = AgentModelResolver(sessionClient, catalog, factory)

                val resolved = resolver.resolve("known-model")

                assertThat(resolved.llmClient).isSameInstanceAs(sessionClient)
                assertThat(resolved.modelId).isEqualTo("known-model")
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

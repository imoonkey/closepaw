package com.moonkey.androidagent.agent

import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog

internal data class AgentModelResolution(
        val llmClient: LLMClient,
        val modelId: String,
        val supportsVision: Boolean
)

/**
 * Resolves model runtime details for an agent execution.
 *
 * Preferred path is catalog-driven (provider/api/model_id/supports_vision). If a model is missing
 * from catalog (legacy/local path), fall back to the prebuilt session client and treat model name
 * as the API model id.
 */
internal class AgentModelResolver(
        private val sessionLlmClient: LLMClient,
        private val modelCatalog: ModelCatalog,
        private val llmClientFactory: LLMClientFactory
) {
        fun resolve(modelName: String): AgentModelResolution {
                val entry = modelCatalog.resolveOrNull(modelName)
                if (entry != null) {
                        val catalogClient =
                                runCatching { llmClientFactory.create(modelName) }.getOrNull()
                        if (catalogClient != null) {
                                return AgentModelResolution(
                                        llmClient = catalogClient,
                                        modelId = entry.modelId,
                                        supportsVision = entry.supportsVision
                                )
                        }
                }

                return AgentModelResolution(
                        llmClient = sessionLlmClient,
                        modelId = modelName,
                        supportsVision = false
                )
        }
}

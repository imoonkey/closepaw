package com.moonkey.androidagent.agent

import android.util.Log
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
        companion object {
                private const val TAG = "AgentModelResolver"
        }

        fun resolve(modelName: String): AgentModelResolution {
                val entry = modelCatalog.resolveOrNull(modelName)
                if (entry != null) {
                        val catalogClient =
                                runCatching { llmClientFactory.create(modelName) }
                                        .onFailure { error ->
                                                Log.w(
                                                        TAG,
                                                        "Failed to create catalog client for '$modelName'; using session fallback",
                                                        error
                                                )
                                        }
                                        .getOrNull()
                        if (catalogClient != null) {
                                return AgentModelResolution(
                                        llmClient = catalogClient,
                                        modelId = entry.modelId,
                                        supportsVision = entry.supportsVision
                                )
                        }
                }

                Log.w(
                        TAG,
                        "Model '$modelName' not found in catalog or client unavailable; using session client fallback"
                )
                return AgentModelResolution(
                        llmClient = sessionLlmClient,
                        modelId = modelName,
                        supportsVision = false
                )
        }
}

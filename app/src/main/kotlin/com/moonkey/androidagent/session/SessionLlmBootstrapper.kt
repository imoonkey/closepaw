package com.moonkey.androidagent.session

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.resolvedBackendTypeCompat
import com.moonkey.androidagent.protocol.resolvedLocalLlmConfigCompat
import kotlinx.serialization.SerializationException

internal data class SessionLlmBootstrap(
        val modelCatalog: ModelCatalog,
        val llmClientFactory: LLMClientFactory,
        val llmClient: LLMClient
)

/** Creates catalog + LLM factory + runtime LLM client for a session. */
internal object SessionLlmBootstrapper {
    private const val TAG = "SessionLlmBootstrap"

    fun create(
            config: SessionConfig,
            context: Context,
            apiKeys: Map<String, String>
    ): SessionLlmBootstrap {
        val backend = config.resolvedBackendTypeCompat()
        val modelCatalog = loadModelCatalog(context)
        Log.d(TAG, "Loaded ModelCatalog with ${modelCatalog.size} models: ${modelCatalog.names()}")

        val llmClientFactory =
                LLMClientFactory(
                        catalog = modelCatalog,
                        apiKeyResolver = { envVar -> apiKeys[envVar] }
                )

        val llmClient =
                when (backend) {
                    LLMBackendType.OPENAI -> {
                        ensureRequiredCloudKeys(config, modelCatalog, apiKeys)
                        llmClientFactory.create(config.mainModel)
                    }
                    LLMBackendType.LOCAL -> {
                        val localConfig = config.resolvedLocalLlmConfigCompat()
                        LFMLLMClient(context, localConfig)
                    }
                }

        return SessionLlmBootstrap(
                modelCatalog = modelCatalog,
                llmClientFactory = llmClientFactory,
                llmClient = llmClient
        )
    }

    /**
     * Load ModelCatalog from assets/llm_models.json. Falls back to a minimal single-model catalog
     * when the asset is missing or malformed.
     *
     * Note: Performs blocking I/O on the calling thread. Callers must ensure this runs off the
     * main thread (e.g. `Dispatchers.IO`).
     */
    private fun loadModelCatalog(context: Context): ModelCatalog {
        return try {
            val json = context.assets.open("llm_models.json").bufferedReader().use { it.readText() }
            ModelCatalog.fromJson(json)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Failed to read llm_models.json from assets; using fallback", e)
            ModelCatalog.fromJson(FALLBACK_CATALOG_JSON)
        } catch (e: SerializationException) {
            Log.w(TAG, "Failed to parse llm_models.json; using fallback", e)
            ModelCatalog.fromJson(FALLBACK_CATALOG_JSON)
        }
    }

    private fun ensureRequiredCloudKeys(
            config: SessionConfig,
            catalog: ModelCatalog,
            apiKeys: Map<String, String>
    ) {
        val requiredModels = linkedSetOf(config.mainModel)
        if (config.agentMode == AgentMode.PRO) {
            config.executorModel?.let(requiredModels::add)
        }

        requiredModels.forEach { modelName ->
            val entry = catalog.resolve(modelName)
            val requiredEnv = entry.effectiveApiKeyEnv
            if (apiKeys[requiredEnv].isNullOrBlank()) {
                throw IllegalStateException(
                        "Missing API key '$requiredEnv' for model '$modelName' " +
                                "(provider=${entry.provider}, api=${entry.api})."
                )
            }
        }
    }

    private const val FALLBACK_CATALOG_JSON =
            """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider": "OPENAI",
            "api": "response",
            "model_id": "gpt-5.2"
          }
        }
        """
}

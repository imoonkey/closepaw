package com.moonkey.androidagent.session

import android.content.Context
import android.content.res.AssetManager
import android.os.Looper
import android.util.Log
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMClientFactory
import com.moonkey.androidagent.llm.LLMProvider
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import kotlinx.serialization.SerializationException
import java.util.WeakHashMap

internal data class SessionLlmBootstrap(
        val modelCatalog: ModelCatalog,
        val llmClientFactory: LLMClientFactory,
        val llmClient: LLMClient
)

/** Creates catalog + LLM factory + runtime LLM client for a session. */
internal object SessionLlmBootstrapper {
    private const val TAG = "SessionLlmBootstrap"
    private val catalogLock = Any()
    private val cachedCatalogByAssets = WeakHashMap<AssetManager, ModelCatalog>()

    fun create(
            config: SessionConfig,
            context: Context,
            apiKeys: Map<String, String>
    ): SessionLlmBootstrap {
        requireOffMainThread()
        val backend = config.llm.backendType
        val baseCatalog = getOrLoadModelCatalog(context)

        // Extract provider base URL overrides from apiKeys (__BASE_URL_<PROVIDER> convention)
        val baseUrlOverrides = extractBaseUrlOverrides(apiKeys)
        val modelCatalog = baseCatalog.withBaseUrlOverrides(baseUrlOverrides)
        if (baseUrlOverrides.isNotEmpty()) {
            Log.d(TAG, "Applied provider base URL overrides: $baseUrlOverrides")
        }
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
                        val localConfig = config.llm.localConfig ?: LocalLLMConfig()
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
    private fun getOrLoadModelCatalog(context: Context): ModelCatalog {
        val assets = context.assets
        synchronized(catalogLock) {
            cachedCatalogByAssets[assets]?.let { return it }
            val loaded = loadModelCatalog(context)
            cachedCatalogByAssets[assets] = loaded
            return loaded
        }
    }

    private fun requireOffMainThread() {
        val mainLooper = Looper.getMainLooper() ?: return // null in unit-test environment
        check(Looper.myLooper() != mainLooper) {
            "SessionLlmBootstrapper.create() must not be called on the main thread; " +
                    "asset I/O would block the UI"
        }
    }

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

    private const val BASE_URL_PREFIX = "__BASE_URL_"

    private fun extractBaseUrlOverrides(apiKeys: Map<String, String>): Map<LLMProvider, String> =
            apiKeys.entries
                    .filter { it.key.startsWith(BASE_URL_PREFIX) }
                    .mapNotNull { (key, value) ->
                        try {
                            LLMProvider.valueOf(key.removePrefix(BASE_URL_PREFIX)) to value
                        } catch (_: IllegalArgumentException) {
                            Log.w(TAG, "Unknown provider in base URL override key: $key")
                            null
                        }
                    }
                    .toMap()

    private const val FALLBACK_CATALOG_JSON =
            """
        {
          "glm-5": {
            "display_name": "GLM-5",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "z-ai/glm-5"
          }
        }
        """
}

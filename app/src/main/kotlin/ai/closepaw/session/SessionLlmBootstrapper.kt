package ai.closepaw.session

import android.content.Context
import android.content.res.AssetManager
import android.os.Looper
import android.util.Log
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.MissingCredential
import ai.closepaw.llm.LFMLLMClient
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig
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
            authStore: AuthStore?,
            baseUrlOverrides: Map<LLMProvider, String> = emptyMap()
    ): SessionLlmBootstrap {
        requireOffMainThread()
        val backend = config.llm.backendType
        val baseCatalog = getOrLoadModelCatalog(context)

        val modelCatalog = baseCatalog.withBaseUrlOverrides(baseUrlOverrides)
        if (baseUrlOverrides.isNotEmpty()) {
            Log.d(TAG, "Applied provider base URL overrides: $baseUrlOverrides")
        }
        Log.d(TAG, "Loaded ModelCatalog with ${modelCatalog.size} models: ${modelCatalog.names()}")

        val llmClientFactory =
                LLMClientFactory(
                        catalog = modelCatalog,
                        authStore = authStore,
                        baseUrlOverrides = baseUrlOverrides
                )

        val llmClient =
                when (backend) {
                    LLMBackendType.OPENAI -> {
                        ensureRequiredCredentials(config, modelCatalog, authStore)
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
        val mainLooper = Looper.getMainLooper() ?: return
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

    private fun ensureRequiredCredentials(
            config: SessionConfig,
            catalog: ModelCatalog,
            authStore: AuthStore?
    ) {
        if (authStore == null) return
        val requiredModels = linkedSetOf(config.mainModel)
        config.executorModel?.let(requiredModels::add)
        requiredModels.forEach { modelName ->
            val entry = catalog.resolve(modelName)
            val provider = entry.provider
            if (provider == LLMProvider.LOCAL_LFM) return@forEach
            if (!authStore.has(provider)) {
                throw MissingCredential(provider)
            }
        }
    }

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

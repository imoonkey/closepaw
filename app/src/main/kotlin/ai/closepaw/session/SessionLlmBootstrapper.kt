package ai.closepaw.session

import android.content.Context
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
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.SessionConfig

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
            catalogRepository: ModelCatalogRepository,
            context: Context,
            authStore: AuthStore?,
            baseUrlOverrides: Map<LLMProvider, String> = emptyMap()
    ): SessionLlmBootstrap {
        requireOffMainThread()
        val backend = config.llm.backendType
        val baseCatalog = catalogRepository.catalog.value

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

    private fun requireOffMainThread() {
        val mainLooper = Looper.getMainLooper() ?: return
        check(Looper.myLooper() != mainLooper) {
            "SessionLlmBootstrapper.create() must not be called on the main thread; " +
                    "catalog snapshot read should run off-main"
        }
    }

    private fun ensureRequiredCredentials(
            config: SessionConfig,
            catalog: ModelCatalog,
            authStore: AuthStore?
    ) {
        if (authStore == null) return
        if (config.mainModel == ModelCatalogRepository.OTHER_CUSTOM_NAME) {
            // Short-circuit BEFORE catalog.resolve: when otherBaseUrl or otherModelId is
            // blank the synth entry isn't in the catalog, so catalog.resolve would throw
            // "unknown model". Surface a clean MissingCredential(OTHER) instead so the
            // banner deep-links to the OTHER tab.
            if (catalog.resolveOrNull(config.mainModel) == null ||
                !authStore.has(LLMProvider.OTHER)) {
                throw MissingCredential(LLMProvider.OTHER)
            }
            return
        }
        val provider = catalog.resolve(config.mainModel).provider
        if (provider == LLMProvider.LOCAL_LFM) return
        if (!authStore.has(provider)) {
            throw MissingCredential(provider)
        }
    }
}

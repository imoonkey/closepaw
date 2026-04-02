package com.moonkey.androidagent.llm

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates [LLMClient] instances from model names using the [ModelCatalog].
 *
 * Resolves API keys via a caller-supplied lambda (so the factory is decoupled from Android system
 * properties, SharedPreferences, etc.).
 *
 * Caches clients by `(provider, baseUrl, api)` tuple — multiple models from the same provider share
 * a connection pool. This makes per-turn client resolution efficient: the underlying OkHttp client
 * is reused.
 *
 * Thread-safe: [create] and [cleanupAll] may be called from any thread.
 */
class LLMClientFactory(
        private val catalog: ModelCatalog,
        private val apiKeyResolver: (String) -> String?,
        private val clientOverride: LLMClient? = null
) {
    companion object {
        private const val TAG = "LLMClientFactory"

        /**
         * Create a factory that always returns the given [client] regardless of model name. Useful
         * for unit tests that inject a mock/fake LLM.
         */
        fun forTest(catalog: ModelCatalog, client: LLMClient): LLMClientFactory =
                LLMClientFactory(catalog, apiKeyResolver = { "test-key" }, clientOverride = client)
    }

    private val clientCache = ConcurrentHashMap<String, LLMClient>()

    /**
     * Create (or return cached) LLMClient for the given model name.
     *
     * @param modelName Key from llm_models.json (e.g. "gpt-5.2", "glm-4.7")
     * @throws IllegalArgumentException if model is not in the catalog
     * @throws IllegalStateException if API key is not found
     */
    fun create(modelName: String): LLMClient {
        // Test override: skip catalog lookup and cache, just return the injected client.
        clientOverride?.let {
            return it
        }

        val entry = catalog.resolve(modelName)
        val oauth = isOAuth(entry)
        val cacheKey = "${entry.provider}|${entry.effectiveBaseUrl ?: "default"}|${entry.api}|$oauth"

        return clientCache.computeIfAbsent(cacheKey) {
            val apiKey = resolveApiKey(entry)
            val client =
                    when (entry.api) {
                        ApiType.RESPONSE -> {
                            if (oauth) CodexResponseClient(apiKey)
                            else OpenAIResponseClient(apiKey, entry.effectiveBaseUrl)
                        }
                        ApiType.CHAT -> ChatCompletionClient(apiKey, entry.effectiveBaseUrl)
                    }
            Log.d(
                    TAG,
                    "Created ${client.javaClass.simpleName} for model '$modelName' " +
                            "(provider=${entry.provider}, api=${entry.api})"
            )
            client
        }
    }

    /**
     * Resolve the API key for a model entry.
     *
     * Checks the entry's effective env var via the resolver lambda.
     */
    private fun resolveApiKey(entry: ModelEntry): String {
        val envVar = entry.effectiveApiKeyEnv
        return apiKeyResolver(envVar)
                ?: throw IllegalStateException(
                        "API key not found for env var '$envVar' " +
                                "(model '${entry.name}', provider ${entry.provider}). " +
                                "Ensure the key is set in environment, intent extras, or settings."
                )
    }

    /** Detect OAuth auth method via the __AUTH_METHOD_OPENAI signal key. */
    private fun isOAuth(entry: ModelEntry): Boolean {
        if (entry.provider != LLMProvider.OPENAI) return false
        return apiKeyResolver("__AUTH_METHOD_OPENAI") == "oauth"
    }

    /** Cleanup all cached clients. Call from session teardown. */
    suspend fun cleanupAll() {
        Log.d(TAG, "Cleaning up ${clientCache.size} cached clients")
        clientCache.values.forEach { it.cleanup() }
        clientCache.clear()
    }
}

package ai.closepaw.llm

import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.MissingCredential
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * Creates [LLMClient] instances from model names using the [ModelCatalog].
 *
 * Routes purely by [LLMProvider]. Credentials are sourced from [AuthStore]; no
 * resolver lambda or signal keys. The Codex client receives a header supplier
 * closure that reads [AuthStore] per request, so cached clients stay valid
 * across token rotations and account switches.
 *
 * Each cache entry carries the [AuthStore.generation] it was built under.
 * `create()` uses [ConcurrentHashMap.compute] to atomically check-and-rebuild:
 * a stale entry is cleaned up and replaced with a fresh client inside the same
 * per-key critical section, so concurrent callers either both see a new client
 * or one old + one new — never a stale client produced after a generation bump.
 *
 * Thread-safe: [create] and [cleanupAll] may be called from any thread.
 */
class LLMClientFactory(
        private val catalog: ModelCatalog,
        private val authStore: AuthStore?,
        private val baseUrlOverrides: Map<LLMProvider, String> = emptyMap(),
        private val clientOverride: LLMClient? = null,
) {
    companion object {
        private const val TAG = "LLMClientFactory"

        /**
         * Create a factory that always returns the given [client] regardless of model name. Useful
         * for unit tests that inject a mock/fake LLM.
         */
        fun forTest(catalog: ModelCatalog, client: LLMClient): LLMClientFactory =
                LLMClientFactory(catalog, authStore = null, clientOverride = client)
    }

    private data class Entry(val generation: Long, val client: LLMClient)

    private val clientCache = ConcurrentHashMap<String, Entry>()

    /**
     * Create (or return cached) LLMClient for the given model name.
     *
     * @throws IllegalArgumentException if model is not in the catalog
     * @throws ai.closepaw.auth.MissingCredential if the provider's credential is absent
     * @throws ai.closepaw.auth.WrongCredentialType if the stored credential is the wrong shape
     */
    fun create(modelName: String): LLMClient {
        clientOverride?.let { return it }

        val entry = catalog.resolve(modelName)
        val provider = entry.provider
        val store = authStore

        val result = clientCache.compute(modelName) { _, existing ->
            // Read generation inside the per-key critical section so a concurrent
            // authStore.set() bump either happens-before this block (we see the new
            // gen and rebuild) or happens-after (the next create() sees the bump).
            val currentGen = if (store != null && provider != LLMProvider.LOCAL_LFM) {
                store.generation(provider)
            } else 0L

            if (existing != null && existing.generation == currentGen) {
                existing
            } else {
                existing?.let { stale ->
                    runBlocking {
                        try { stale.client.cleanup() } catch (e: Exception) {
                            Log.w(TAG, "cleanup of stale client failed: ${e.message}")
                        }
                    }
                }
                val built = build(entry)
                Log.d(
                        TAG,
                        "Created ${built.javaClass.simpleName} for model '$modelName' " +
                                "(provider=$provider, api=${entry.api}, gen=$currentGen)"
                )
                Entry(currentGen, built)
            }
        }!!
        return result.client
    }

    private fun build(entry: ModelEntry): LLMClient {
        val store = authStore
                ?: throw IllegalStateException(
                        "LLMClientFactory has no AuthStore — test-only factory cannot build clients for model '${entry.name}'."
                )
        val baseUrl = baseUrlOverrides[entry.provider] ?: entry.effectiveBaseUrl
        return when (entry.provider) {
            LLMProvider.OPENAI_API ->
                    when (entry.api) {
                        ApiType.RESPONSE ->
                                OpenAIResponseClient(store.requireApiKey(LLMProvider.OPENAI_API), baseUrl)
                        ApiType.CHAT ->
                                ChatCompletionClient(store.requireApiKey(LLMProvider.OPENAI_API), baseUrl)
                    }
            LLMProvider.OPENAI_CODEX ->
                    CodexResponseClient(
                            headerSupplier = { store.codexHeaders(LLMProvider.OPENAI_CODEX) }
                    )
            LLMProvider.OPENROUTER ->
                    ChatCompletionClient(store.requireApiKey(LLMProvider.OPENROUTER), baseUrl)
            LLMProvider.OTHER -> {
                // Hard-require a non-blank baseUrl at this boundary. If anything upstream
                // produced a malformed OTHER entry (synth missing settings, stale catalog),
                // the OpenAI SDK would otherwise default to api.openai.com — leaking the
                // user's OTHER key to OpenAI. Surface it as a missing-credential error so
                // the UI deep-links to the OTHER tab instead of silently misrouting.
                val otherBaseUrl = entry.baseUrl
                if (otherBaseUrl.isNullOrBlank()) {
                    throw MissingCredential(LLMProvider.OTHER)
                }
                ChatCompletionClient(store.requireApiKey(LLMProvider.OTHER), otherBaseUrl)
            }
            LLMProvider.LOCAL_LFM ->
                    throw IllegalStateException(
                            "LLMClientFactory does not build LFMLLMClient; use LFMLLMClient(context) directly."
                    )
        }
    }

    /** Cleanup all cached clients. Call from session teardown. */
    suspend fun cleanupAll() {
        Log.d(TAG, "Cleaning up ${clientCache.size} cached clients")
        clientCache.values.forEach { it.client.cleanup() }
        clientCache.clear()
    }
}

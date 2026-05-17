package ai.closepaw.llm

import android.content.Context
import android.util.Log
import ai.closepaw.app.AppSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Process-wide observable model catalog.
 *
 * Single source of truth for the merged set of [ModelEntry]s the rest of the
 * stack (selector, factory, session bootstrap, validation) consumes. The
 * catalog merges:
 *  - the JSON seed in `assets/llm_models.json`;
 *  - a synthesized `other-custom` [ModelEntry] when both `otherBaseUrl` and
 *    `otherModelId` are non-blank in [AppSettingsStore];
 *  - discovered entries from [ModelDiscoveryCache], scoped by current
 *    effective baseUrl per provider.
 *
 * Created once per process via [ModelCatalogRepositoryHolder] so the UI layer
 * (Compose recomposition via [StateFlow]) and the session layer
 * (`SessionLlmBootstrapper` reading off-main via `.value`) observe identical
 * state.
 */
class ModelCatalogRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val discoveryCache: ModelDiscoveryCache,
    private val discoverFn: suspend (LLMProvider, String, String) -> List<DiscoveredModel> =
        ModelDiscovery::discover,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _catalog: MutableStateFlow<ModelCatalog> = MutableStateFlow(load())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    private val _discoveryState: MutableStateFlow<DiscoveryState> =
        MutableStateFlow(initialDiscoveryState())
    /**
     * Per-provider discovery status — `lastFetchedAt` lets the settings UI
     * render a "Last refreshed: X ago" stamp without re-reading the cache.
     */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    /**
     * Recompute the merged catalog from seed + settings + discovery cache and
     * publish via [catalog]. Safe to call from any thread.
     */
    fun invalidate() {
        _catalog.value = load()
        _discoveryState.value = _discoveryState.value.copy(
            lastFetchedAt = readLastFetchedAt(),
        )
    }

    /**
     * Fetch fresh entries from `{baseUrl}/models` for [provider] using [key],
     * persist them to the cache, and republish [catalog]. Errors are
     * surfaced via [discoveryState]; the existing cache is preserved on
     * failure.
     *
     * For [LLMProvider.OPENROUTER] the URL comes from the seed default; for
     * [LLMProvider.OTHER] from the validated `otherBaseUrl` setting. Other
     * providers are rejected because their `/models` payloads are either too
     * thin (OpenAI) or unavailable (LOCAL_LFM, OPENAI_CODEX).
     */
    suspend fun refresh(provider: LLMProvider, key: String) {
        require(provider == LLMProvider.OPENROUTER || provider == LLMProvider.OTHER) {
            "Refresh only supported for OPENROUTER and OTHER (got $provider)"
        }
        val baseUrl = effectiveBaseUrlFor(provider)
            ?: run {
                _discoveryState.value = _discoveryState.value.withError(
                    provider,
                    "Configure a base URL before refreshing.",
                )
                return
            }
        _discoveryState.value = _discoveryState.value.withRefreshing(provider, true)
        try {
            val discovered = discoverFn(provider, baseUrl, key)
            val cacheKey = ModelDiscoveryCache.cacheKey(provider, baseUrl)
            val now = clock()
            discoveryCache.write(cacheKey, now, discovered)
            _catalog.value = load()
            _discoveryState.value = _discoveryState.value
                .withRefreshing(provider, false)
                .withSuccess(provider, now)
        } catch (e: Exception) {
            Log.w(TAG, "refresh($provider) failed", e)
            _discoveryState.value = _discoveryState.value
                .withRefreshing(provider, false)
                .withError(provider, e.message ?: "Refresh failed")
        }
    }

    /**
     * Resolve the effective base URL for a discovery-capable provider, or
     * null if the configuration is incomplete (e.g. OTHER's `otherBaseUrl`
     * is blank or invalid).
     */
    fun effectiveBaseUrlFor(provider: LLMProvider): String? = when (provider) {
        LLMProvider.OPENROUTER -> provider.defaultBaseUrl
        LLMProvider.OTHER -> OtherBaseUrlValidator.validate(settingsStore.load().otherBaseUrl).getOrNull()
        else -> null
    }

    private fun load(): ModelCatalog {
        val seed = ModelCatalog.fromJson(readSeedJsonOrFallback())
        val extras = buildList {
            synthOtherEntry()?.let { add(it) }
            addAll(discoveredEntriesScopedByCurrentBaseUrl())
        }
        return if (extras.isEmpty()) seed else seed.withExtraEntries(extras)
    }

    /**
     * Return discovered entries whose cache-key baseUrl matches the
     * currently effective baseUrl for that provider. Prevents the credential
     * leak failure mode where the user changes `otherBaseUrl` from A to B and
     * an old-A cached entry remains selectable — the agent would then send
     * the current key to the stale URL.
     */
    private fun discoveredEntriesScopedByCurrentBaseUrl(): List<ModelEntry> {
        val openRouterBase = effectiveBaseUrlFor(LLMProvider.OPENROUTER)
        val otherBase = effectiveBaseUrlFor(LLMProvider.OTHER)
        val all = discoveryCache.readAll()
        return all.entries.flatMap { (key, bucket) ->
            val sep = key.indexOf(':')
            if (sep <= 0) return@flatMap emptyList()
            val provider = runCatching { LLMProvider.valueOf(key.substring(0, sep)) }
                .getOrNull() ?: return@flatMap emptyList()
            val expectedBase = when (provider) {
                LLMProvider.OPENROUTER -> openRouterBase
                LLMProvider.OTHER -> otherBase
                else -> null
            } ?: return@flatMap emptyList()
            val expectedKey = ModelDiscoveryCache.cacheKey(provider, expectedBase)
            if (expectedKey != key) emptyList() else bucket.toDiscovered(provider).map { it.entry }
        }
    }

    private fun synthOtherEntry(): ModelEntry? {
        val settings = settingsStore.load()
        val modelId = settings.otherModelId.trim()
        if (modelId.isBlank()) return null
        // Reject (don't synth) if the persisted base URL fails validation. Without this,
        // ensureRequiredCredentials sees a synth row, calls into the factory, and the
        // OpenAI SDK constructor surfaces a non-credential error with no clean OTHER
        // deep-link. Forcing the synth to be absent for invalid URLs makes the existing
        // "catalog row absent" check in the bootstrapper return MissingCredential(OTHER)
        // cleanly. Store the NORMALIZED URL (trailing slash trimmed) so downstream
        // consumers don't have to re-normalize.
        val normalizedBaseUrl = OtherBaseUrlValidator.validate(settings.otherBaseUrl).getOrNull()
            ?: return null
        return ModelEntry(
            name = OTHER_CUSTOM_NAME,
            displayName = modelId,
            provider = LLMProvider.OTHER,
            api = ApiType.CHAT,
            modelId = modelId,
            contextWindow = 128_000,
            baseUrl = normalizedBaseUrl,
            apiKeyEnv = null,
            supportsVision = false,
        )
    }

    private fun readSeedJsonOrFallback(): String {
        val raw = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Failed to read $ASSET_NAME from assets; using fallback", e)
            return FALLBACK_CATALOG_JSON
        }
        // Probe-parse with the same Json reader the catalog uses so corrupt seed JSON falls back
        // here instead of throwing out of load(). Keeps a single catalog parse call site in main —
        // fallback handling lives at the string layer.
        return try {
            seedProbeJson.decodeFromString<Map<String, JsonElement>>(raw)
            raw
        } catch (e: SerializationException) {
            Log.w(TAG, "Failed to parse $ASSET_NAME; using fallback", e)
            FALLBACK_CATALOG_JSON
        }
    }

    private fun initialDiscoveryState(): DiscoveryState {
        val openRouterAt = readLastFetchedAtFor(LLMProvider.OPENROUTER)
        val otherAt = readLastFetchedAtFor(LLMProvider.OTHER)
        return DiscoveryState(
            lastFetchedAt = mapOf(
                LLMProvider.OPENROUTER to openRouterAt,
                LLMProvider.OTHER to otherAt,
            ).filterValues { it != null }
                .mapValues { (_, v) -> v!! },
        )
    }

    private fun readLastFetchedAt(): Map<LLMProvider, Long> = mapOf(
        LLMProvider.OPENROUTER to readLastFetchedAtFor(LLMProvider.OPENROUTER),
        LLMProvider.OTHER to readLastFetchedAtFor(LLMProvider.OTHER),
    ).filterValues { it != null }.mapValues { (_, v) -> v!! }

    private fun readLastFetchedAtFor(provider: LLMProvider): Long? {
        val base = effectiveBaseUrlFor(provider) ?: return null
        return discoveryCache.read(ModelDiscoveryCache.cacheKey(provider, base))?.fetchedAt
    }

    /**
     * UI-facing discovery state. `refreshing` is the set of providers with an
     * in-flight refresh; `lastFetchedAt` is the timestamp from the most
     * recent successful refresh; `lastError` is the most recent error
     * message per provider (cleared on successful refresh).
     */
    data class DiscoveryState(
        val refreshing: Set<LLMProvider> = emptySet(),
        val lastFetchedAt: Map<LLMProvider, Long> = emptyMap(),
        val lastError: Map<LLMProvider, String> = emptyMap(),
    ) {
        fun withRefreshing(provider: LLMProvider, value: Boolean): DiscoveryState =
            copy(refreshing = if (value) refreshing + provider else refreshing - provider)

        fun withSuccess(provider: LLMProvider, at: Long): DiscoveryState = copy(
            lastFetchedAt = lastFetchedAt + (provider to at),
            lastError = lastError - provider,
        )

        fun withError(provider: LLMProvider, message: String): DiscoveryState = copy(
            lastError = lastError + (provider to message),
        )
    }

    companion object {
        private const val TAG = "ModelCatalogRepo"
        private const val ASSET_NAME = "llm_models.json"
        private val seedProbeJson = Json { ignoreUnknownKeys = true }

        /** Stable catalog key for the synthesized OTHER entry. */
        const val OTHER_CUSTOM_NAME = "other-custom"

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
}

/**
 * Application-scoped [ModelCatalogRepository] singleton — mirrors
 * `AuthStoreHolder`. One instance per process keeps UI and session reads
 * coherent across configuration change, service rebind, and process reattach.
 */
object ModelCatalogRepositoryHolder {
    @Volatile private var instance: ModelCatalogRepository? = null

    fun get(context: Context): ModelCatalogRepository {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    /** Replace the cached instance with a test fixture. Pair with [resetForTest]. */
    fun setForTest(repo: ModelCatalogRepository) {
        synchronized(this) { instance = repo }
    }

    /** Clear the cached instance — call from `@After` so [StateFlow] state does not leak across cases. */
    fun resetForTest() {
        synchronized(this) { instance = null }
    }

    private fun build(appContext: Context): ModelCatalogRepository = ModelCatalogRepository(
        context = appContext,
        settingsStore = AppSettingsStore(appContext),
        discoveryCache = ModelDiscoveryCache(appContext),
    )
}

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
 * In-process discovery cache.
 *
 * PR1 stub — always empty. PR2 will persist discovered entries to
 * `filesDir/model_discovery_cache.json` and key them by
 * `"{provider.name}:{normalizedBaseUrl}"`.
 */
class ModelDiscoveryCache(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun snapshot(): List<ModelEntry> = emptyList()
}

/**
 * Process-wide observable model catalog.
 *
 * Single source of truth for the merged set of [ModelEntry]s the rest of the
 * stack (selector, factory, session bootstrap, validation) consumes. PR1 backs
 * the catalog only with the JSON seed in `assets/llm_models.json`; future PRs
 * overlay a synthesized OTHER entry from [AppSettingsStore] and discovered
 * entries from [ModelDiscoveryCache].
 *
 * Created once per process via [ModelCatalogRepositoryHolder] so the UI layer
 * (Compose recomposition via [StateFlow]) and the session layer
 * (`SessionLlmBootstrapper` reading off-main via `.value`) observe identical
 * state.
 */
class ModelCatalogRepository(
    private val context: Context,
    @Suppress("unused") private val settingsStore: AppSettingsStore,
    @Suppress("unused") private val discoveryCache: ModelDiscoveryCache,
) {
    private val _catalog: MutableStateFlow<ModelCatalog> = MutableStateFlow(load())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    /**
     * Recompute the merged catalog from seed + settings + discovery cache and
     * publish via [catalog]. Safe to call from any thread.
     */
    fun invalidate() {
        _catalog.value = load()
    }

    private fun load(): ModelCatalog = ModelCatalog.fromJson(readSeedJsonOrFallback())

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

    companion object {
        private const val TAG = "ModelCatalogRepo"
        private const val ASSET_NAME = "llm_models.json"
        private val seedProbeJson = Json { ignoreUnknownKeys = true }
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

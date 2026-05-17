package ai.closepaw.llm

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent on-disk cache for discovered model entries.
 *
 * Stored at `filesDir/model_discovery_cache.json` as a single JSON object
 * keyed by `"{provider.name}:{normalizedBaseUrl}"`. Each bucket carries the
 * fetch timestamp, the source `baseUrl`, and a SLIM list of model fields.
 * Rich upstream metadata (pricing, description, modality details) is
 * dropped before persist so a ~440KB OpenRouter response collapses to ~30KB
 * on disk. Field names are intentionally compact and per-entry `baseUrl`
 * is folded onto the bucket — every entry in a bucket shares the bucket's
 * baseUrl by construction.
 *
 * Stale buckets remain on disk after the user changes `otherBaseUrl` so they
 * reappear when the user reverts. The visible catalog scopes by current
 * effective baseUrl (see [ModelCatalogRepository]) so stale buckets cannot
 * leak credentials.
 */
class ModelDiscoveryCache(context: Context) {

    private val file: File = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /** Read-through snapshot of every bucket. Empty if file missing or unreadable. */
    fun readAll(): Map<String, Bucket> = synchronized(lock) {
        if (!file.exists()) return emptyMap()
        return try {
            val text = file.readText()
            if (text.isBlank()) emptyMap() else json.decodeFromString<Map<String, Bucket>>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $FILE_NAME; treating as empty", e)
            emptyMap()
        }
    }

    /** Bucket for a single cache key, or null if absent. */
    fun read(key: String): Bucket? = readAll()[key]

    /**
     * Overwrite the bucket at [key] with [discovered] and [fetchedAt]. Each
     * entry is slimmed to its persisted shape before write; rich upstream
     * metadata never reaches disk.
     *
     * The bucket's `baseUrl` is taken from the first entry; all entries in
     * a single refresh share the same source URL by construction (see
     * [ModelDiscovery]).
     */
    fun write(key: String, fetchedAt: Long, discovered: List<DiscoveredModel>) {
        synchronized(lock) {
            val current = readAll().toMutableMap()
            val sharedBaseUrl = discovered.firstOrNull()?.entry?.baseUrl
            current[key] = Bucket(
                fetchedAt = fetchedAt,
                baseUrl = sharedBaseUrl,
                entries = discovered.map(::toSlim),
            )
            try {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(current))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write $FILE_NAME", e)
            }
        }
    }

    companion object {
        const val FILE_NAME = "model_discovery_cache.json"
        private const val TAG = "ModelDiscoveryCache"

        // Compact output: omit defaults, no pretty-print, shorter field names.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = false
            explicitNulls = false
        }

        fun cacheKey(provider: LLMProvider, normalizedBaseUrl: String): String =
            "${provider.name}:$normalizedBaseUrl"

        private fun toSlim(d: DiscoveredModel): SlimEntry = SlimEntry(
            modelId = d.entry.modelId,
            displayName = d.entry.displayName.takeIf { it != d.entry.modelId },
            contextWindow = d.entry.contextWindow.takeIf { it != DEFAULT_CONTEXT },
            supportsVision = d.entry.supportsVision.takeIf { it },
            created = d.created.takeIf { it != 0L },
        )

        private const val DEFAULT_CONTEXT = 128_000
    }

    @Serializable
    data class Bucket(
        @SerialName("t") val fetchedAt: Long,
        @SerialName("u") val baseUrl: String? = null,
        @SerialName("e") val entries: List<SlimEntry> = emptyList(),
    ) {
        /**
         * Materialize [DiscoveredModel] rows for the given [provider]. The
         * provider is needed because the wire format intentionally omits it
         * — it's already encoded in the cache key.
         */
        fun toDiscovered(provider: LLMProvider): List<DiscoveredModel> = entries.map { slim ->
            val displayName = slim.displayName ?: slim.modelId
            val created = slim.created ?: 0L
            DiscoveredModel(
                entry = ModelEntry(
                    name = "${provider.name.lowercase()}:${slim.modelId}",
                    displayName = displayName,
                    provider = provider,
                    api = ApiType.CHAT,
                    modelId = slim.modelId,
                    contextWindow = slim.contextWindow ?: 128_000,
                    baseUrl = baseUrl,
                    apiKeyEnv = null,
                    supportsVision = slim.supportsVision == true,
                    created = created,
                ),
                created = created,
            )
        }
    }

    @Serializable
    data class SlimEntry(
        @SerialName("i") val modelId: String,
        @SerialName("n") val displayName: String? = null,
        @SerialName("c") val contextWindow: Int? = null,
        @SerialName("v") val supportsVision: Boolean? = null,
        @SerialName("k") val created: Long? = null,
    )
}

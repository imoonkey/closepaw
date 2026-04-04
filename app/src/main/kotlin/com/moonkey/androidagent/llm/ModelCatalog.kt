package com.moonkey.androidagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which OpenAI-compatible API shape this model uses.
 *
 * Independent of provider — both OPENAI and OPENROUTER support both shapes. The value controls
 * which [LLMClient] implementation is instantiated.
 */
enum class ApiType {
    /** OpenAI Responses API (native function calling, streaming). */
    RESPONSE,

    /** OpenAI Chat Completions API (widely compatible with third-party providers). */
    CHAT
}

/**
 * LLM provider — determines default API key env var and base URL.
 *
 * Adding a new provider is a two-line change:
 * 1. Add the enum value here with its defaults.
 * 2. Add entries to `llm_models.json`.
 */
enum class LLMProvider(val defaultApiKeyEnv: String, val defaultBaseUrl: String?) {
    /** OpenAI — api.openai.com */
    OPENAI(defaultApiKeyEnv = "OPENAI_API_KEY", defaultBaseUrl = null),

    /** OpenRouter — openrouter.ai (aggregates many model providers) */
    OPENROUTER(
            defaultApiKeyEnv = "OPENROUTER_API_KEY",
            defaultBaseUrl = "https://openrouter.ai/api/v1"
    ),

    /** Novita — api.novita.ai */
    NOVITA(defaultApiKeyEnv = "NOVITA_API_KEY", defaultBaseUrl = "https://api.novita.ai/openai/v1")
}

/** Human-readable label for UI display. */
val LLMProvider.displayLabel: String
    get() = when (this) {
        LLMProvider.OPENAI -> "OpenAI"
        LLMProvider.OPENROUTER -> "OpenRouter"
        LLMProvider.NOVITA -> "Novita"
    }

/**
 * One model entry from `llm_models.json`.
 *
 * Intentionally flat — no inheritance, no generics, no builder patterns. All fields are resolved at
 * parse time; runtime code just reads values.
 *
 * @property name JSON key, e.g. "gpt-5.2". Used as the stable identifier
 * ```
 *                         in [SessionConfig], settings storage, and intent extras.
 * @property displayName
 * ```
 * Shown in UI dropdowns.
 * @property provider Determines which API key env var to read.
 * @property api Determines which [LLMClient] subclass to use.
 * @property modelId The model string sent to the API (e.g. "gpt-5.2", "zhipu-ai/glm-4.7").
 * @property baseUrl Custom API endpoint. Null = use provider default.
 * @property apiKeyEnv Env var name for API key. Null = use provider default.
 * @property supportsVision Whether this model accepts image inputs. Default true for cloud models.
 */
data class ModelEntry(
        val name: String,
        val displayName: String,
        val provider: LLMProvider,
        val api: ApiType,
        val modelId: String,
        val baseUrl: String? = null,
        val apiKeyEnv: String? = null,
        val supportsVision: Boolean = true
) {
    /** Effective API key env var (entry override or provider default). */
    val effectiveApiKeyEnv: String
        get() = apiKeyEnv ?: provider.defaultApiKeyEnv

    /** Effective base URL (entry override or provider default). */
    val effectiveBaseUrl: String?
        get() = baseUrl ?: provider.defaultBaseUrl
}

/**
 * Loads, caches, and resolves model entries from `llm_models.json`.
 *
 * Thread-safe after construction — the entry map is immutable. The catalog is the single source of
 * truth for available models.
 */
class ModelCatalog private constructor(private val entries: Map<String, ModelEntry>) {
    /**
     * Resolve a model by name (the JSON key).
     *
     * @throws IllegalArgumentException if the name is not in the catalog.
     */
    fun resolve(name: String): ModelEntry =
            entries[name]
                    ?: throw IllegalArgumentException(
                            "Unknown model '$name'. Available: ${entries.keys.sorted()}"
                    )

    /** Resolve a model by name, returning null if not found. */
    fun resolveOrNull(name: String): ModelEntry? = entries[name]

    /** All entries, in insertion order. */
    fun all(): List<ModelEntry> = entries.values.toList()

    /** All model names (JSON keys). */
    fun names(): Set<String> = entries.keys

    /** Number of models in the catalog. */
    val size: Int
        get() = entries.size

    /** Check if a model name exists. */
    operator fun contains(name: String): Boolean = name in entries

    /** Models for a given provider, optionally filtered by API type. */
    fun modelsFor(provider: LLMProvider, api: ApiType? = null): List<ModelEntry> =
            entries.values.filter { it.provider == provider && (api == null || it.api == api) }

    /** First (preferred) model for a given provider/API type, or null if none match. */
    fun preferredModelFor(provider: LLMProvider, api: ApiType? = null): ModelEntry? =
            modelsFor(provider, api).firstOrNull()

    /**
     * Return a new catalog with provider-level base URL overrides applied.
     *
     * Only overrides entries that don't already have an explicit [ModelEntry.baseUrl].
     * Returns `this` if [overrides] is empty.
     */
    fun withBaseUrlOverrides(overrides: Map<LLMProvider, String>): ModelCatalog {
        if (overrides.isEmpty()) return this
        val overridden = entries.mapValues { (_, entry) ->
            val override = overrides[entry.provider]
            if (override != null && entry.baseUrl == null) entry.copy(baseUrl = override) else entry
        }
        return ModelCatalog(LinkedHashMap(overridden))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse from JSON string (read from assets or file).
         *
         * JSON schema: top-level object where each key is the model name and the value is a
         * [JsonModelEntry].
         *
         * @throws kotlinx.serialization.SerializationException if JSON syntax is invalid.
         * @throws IllegalArgumentException if catalog is empty or contains invalid entries.
         */
        fun fromJson(jsonString: String): ModelCatalog {
            val raw: Map<String, JsonModelEntry> = json.decodeFromString(jsonString)
            require(raw.isNotEmpty()) { "Model catalog JSON must contain at least one model" }

            val entries = LinkedHashMap(raw.mapValues { (name, entry) -> entry.toModelEntry(name) })
            return ModelCatalog(entries)
        }
    }
}

// ── JSON deserialization model ──────────────────────────────────────────

/**
 * Wire format for a single model entry in `llm_models.json`.
 *
 * Internal — callers use [ModelEntry] after parsing. Kept separate from [ModelEntry] so the domain
 * model isn't polluted with serialization annotations.
 */
@Serializable
internal data class JsonModelEntry(
        @SerialName("display_name") val displayName: String,
        val provider: String,
        val api: String,
        @SerialName("model_id") val modelId: String,
        @SerialName("base_url") val baseUrl: String? = null,
        @SerialName("api_key_env") val apiKeyEnv: String? = null,
        @SerialName("supports_vision") val supportsVision: Boolean = true
) {
    fun toModelEntry(name: String): ModelEntry {
        require(name.isNotBlank()) { "Model name must not be blank" }
        require(displayName.isNotBlank()) { "display_name must not be blank for model '$name'" }
        require(modelId.isNotBlank()) { "model_id must not be blank for model '$name'" }

        val resolvedProvider =
                try {
                    LLMProvider.valueOf(provider.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                            "Unknown provider '$provider' for model '$name'. " +
                                    "Valid: ${LLMProvider.entries.map { it.name }}"
                    )
                }
        val resolvedApi =
                when (api.lowercase()) {
                    "response" -> ApiType.RESPONSE
                    "chat" -> ApiType.CHAT
                    else ->
                            throw IllegalArgumentException(
                                    "Unknown api type '$api' for model '$name'. Valid: response, chat"
                            )
                }
        return ModelEntry(
                name = name,
                displayName = displayName,
                provider = resolvedProvider,
                api = resolvedApi,
                modelId = modelId,
                baseUrl = baseUrl,
                apiKeyEnv = apiKeyEnv,
                supportsVision = supportsVision
        )
    }
}

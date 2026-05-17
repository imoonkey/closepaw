package ai.closepaw.llm

/**
 * How a provider is authenticated. Drives UI grouping (OAuth / API Key / Local tabs) and
 * factory routing.
 */
enum class AuthMode {
    OAuth,
    ApiKey,
    Local,
}

/**
 * Flat LLM provider — encodes both the backend and the auth mode. One entry per (backend, mode)
 * pair so the catalog, factory, and credential store can all key off a single enum value.
 *
 * `defaultApiKeyEnv` and `defaultBaseUrl` are retained for the current factory/catalog wiring;
 * OAuth and Local entries populate them with placeholders (unused on their code paths).
 */
enum class LLMProvider(
    val mode: AuthMode,
    val defaultApiKeyEnv: String,
    val defaultBaseUrl: String?,
) {
    /** OpenAI via API key — api.openai.com, Responses or Chat Completions. */
    OPENAI_API(
        mode = AuthMode.ApiKey,
        defaultApiKeyEnv = "OPENAI_API_KEY",
        defaultBaseUrl = null,
    ),

    /** OpenAI via ChatGPT/Codex OAuth — Responses API through the Codex backend. */
    OPENAI_CODEX(
        mode = AuthMode.OAuth,
        defaultApiKeyEnv = "OPENAI_API_KEY",
        defaultBaseUrl = null,
    ),

    /** OpenRouter — openrouter.ai (aggregates many model providers). */
    OPENROUTER(
        mode = AuthMode.ApiKey,
        defaultApiKeyEnv = "OPENROUTER_API_KEY",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
    ),

    /**
     * User-configured OpenAI-compatible endpoint. Base URL and model id live in
     * [ai.closepaw.app.AppSettingsState] (`otherBaseUrl`, `otherModelId`) and are
     * surfaced as a synthesized `other-custom` catalog entry. No hardcoded URL — the
     * factory hard-requires a non-blank `entry.baseUrl` to avoid leaking the user's
     * key to the OpenAI SDK's default base URL.
     */
    OTHER(
        mode = AuthMode.ApiKey,
        defaultApiKeyEnv = "OTHER_API_KEY",
        defaultBaseUrl = null,
    ),

    /** On-device LFM runtime (Leap SDK). No network credential. */
    LOCAL_LFM(
        mode = AuthMode.Local,
        defaultApiKeyEnv = "LOCAL_LFM",
        defaultBaseUrl = null,
    ),
}

/** Human-readable label for UI display. */
val LLMProvider.displayLabel: String
    get() = when (this) {
        LLMProvider.OPENAI_API -> "OpenAI"
        LLMProvider.OPENAI_CODEX -> "OpenAI (ChatGPT sign-in)"
        LLMProvider.OPENROUTER -> "OpenRouter"
        LLMProvider.OTHER -> "Other"
        LLMProvider.LOCAL_LFM -> "Local"
    }

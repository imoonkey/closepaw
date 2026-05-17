package ai.closepaw.ui.chat

import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.LLMProvider

/** Settings pages available for deep-linking from runtime banners. */
enum class SettingsPage { HOME, LLM_AUTH }

/**
 * Instruction to the settings host to open a specific page/tab.
 *
 * Produced by [ChatViewModel.reportStartupFailure] when session bootstrap fails
 * with a missing-credential error; consumed by the settings host (MainActivityContent)
 * when the startup-failure banner is tapped — forwarded as `initialPage` /
 * `initialAuthTab` / `initialProvider` into `SettingsSheet`.
 *
 * [provider] disambiguates within the API Key tab so banners can land directly on
 * the OTHER sub-tab (key/url/modelId trio); [authTab] alone is too coarse since
 * OPENAI_API / OPENROUTER / OTHER all share `AuthMode.ApiKey`.
 */
data class SettingsDeepLink(
    val page: SettingsPage = SettingsPage.LLM_AUTH,
    val authTab: AuthMode? = null,
    val provider: LLMProvider? = null,
)

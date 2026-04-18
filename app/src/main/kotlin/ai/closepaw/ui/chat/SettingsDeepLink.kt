package ai.closepaw.ui.chat

import ai.closepaw.llm.AuthMode

/** Settings pages available for deep-linking from runtime banners. */
enum class SettingsPage { HOME, LLM_AUTH }

/**
 * Instruction to the settings host to open a specific page/tab.
 *
 * Produced by [ChatViewModel.reportStartupFailure] when session bootstrap fails
 * with a missing-credential error; consumed by the settings host (MainActivityContent)
 * when the startup-failure banner is tapped — forwarded as `initialPage` /
 * `initialAuthTab` into `SettingsSheet`.
 */
data class SettingsDeepLink(
    val page: SettingsPage = SettingsPage.LLM_AUTH,
    val authTab: AuthMode? = null,
)

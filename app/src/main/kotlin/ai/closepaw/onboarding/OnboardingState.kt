package ai.closepaw.onboarding

import ai.closepaw.llm.LLMProvider

/** Steps in the onboarding wizard, in funnel order. */
enum class WizardStep { Accessibility, Overlay, Battery, ApiKey, Demo, Complete }

/** Durable outcome persisted for each step. */
enum class StepOutcome { Pending, Done, Skipped }

/**
 * Cloud providers selectable during onboarding's API-key path.
 *
 * Aligned one-to-one with the ApiKey-mode entries of [LLMProvider] that are
 * appropriate for first-time setup. OAuth (OPENAI_CODEX) lives on its own tab
 * and is not part of this picker. The advanced [LLMProvider.OTHER] slot is
 * intentionally excluded — it's settings-only and requires the user to supply
 * base URL + model id, not viable from a wizard.
 */
enum class OnboardingProvider(val label: String, val llmProvider: LLMProvider) {
    OPENAI_API("OpenAI", LLMProvider.OPENAI_API),
    OPENROUTER("OpenRouter", LLMProvider.OPENROUTER);

    companion object {
        /** Providers rendered in the onboarding provider picker. */
        val visibleInUi: List<OnboardingProvider> = listOf(OPENAI_API, OPENROUTER)
    }
}

/** Auth method within the API key step (OpenAI only offers OAuth). */
enum class ApiKeyAuthMethod { OAUTH, MANUAL }

/** Persisted step outcomes — loaded from store, used to derive current step. */
data class StepOutcomes(
    val accessibility: StepOutcome = StepOutcome.Pending,
    val overlay: StepOutcome = StepOutcome.Pending,
    val battery: StepOutcome = StepOutcome.Pending,
    val apiKey: StepOutcome = StepOutcome.Pending,
    val demo: StepOutcome = StepOutcome.Pending
)

// ── Per-step transient state (one active at a time) ──

sealed interface OnboardingStepState

sealed interface PermissionStepState : OnboardingStepState {
    data object Checking : PermissionStepState
    data object Ready : PermissionStepState
    data object OpeningSettings : PermissionStepState
    data object Satisfied : PermissionStepState
    data object Unsatisfied : PermissionStepState
    data object Skipped : PermissionStepState // Battery only
}

sealed interface ApiKeyStepState : OnboardingStepState {
    data object Empty : ApiKeyStepState
    data class Editing(val key: String) : ApiKeyStepState
    data class Validating(val key: String) : ApiKeyStepState
    data class Invalid(val key: String, val message: String) : ApiKeyStepState
    data class TransientError(val key: String, val message: String) : ApiKeyStepState
    data class Valid(val key: String) : ApiKeyStepState

    // OAuth states
    data object OAuthReady : ApiKeyStepState
    data object OAuthInProgress : ApiKeyStepState
    data object OAuthFinishing : ApiKeyStepState
    data class OAuthSuccess(val email: String) : ApiKeyStepState
    data class OAuthError(val message: String) : ApiKeyStepState
}

sealed interface DemoStepState : OnboardingStepState {
    data object Ready : DemoStepState
    data object Preflight : DemoStepState
    data object Running : DemoStepState
    data class Success(val message: String) : DemoStepState
    data class Failure(val reason: String) : DemoStepState
    /** Credential error surfaced inline on the demo step with a re-auth CTA. */
    data class CredentialError(val message: String, val isOAuth: Boolean) : DemoStepState
    data object Skipped : DemoStepState
}

/** One-shot effects emitted by ViewModel, consumed by composable. */
sealed interface OnboardingEffect {
    data object OpenAccessibilitySettings : OnboardingEffect
    data object OpenOverlaySettings : OnboardingEffect
    data object OpenBatteryOptimization : OnboardingEffect
    data object OpenBatteryOptimizationList : OnboardingEffect
    data object BringMainActivityToFront : OnboardingEffect
    data class LaunchOAuth(val url: String) : OnboardingEffect
}

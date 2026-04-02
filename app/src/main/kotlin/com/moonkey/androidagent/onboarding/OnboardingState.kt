package com.moonkey.androidagent.onboarding

/** Steps in the onboarding wizard, in funnel order. */
enum class WizardStep { Accessibility, Overlay, Battery, ApiKey, Demo, Complete }

/** Durable outcome persisted for each step. */
enum class StepOutcome { Pending, Done, Skipped }

/** Provider choices available during onboarding API key step. */
enum class OnboardingProvider(val label: String, val apiKeyEnv: String) {
    OPENAI("OpenAI", "OPENAI_API_KEY"),
    OPENROUTER("OpenRouter", "OPENROUTER_API_KEY")
}

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
}

sealed interface DemoStepState : OnboardingStepState {
    data object Ready : DemoStepState
    data object Preflight : DemoStepState
    data object Running : DemoStepState
    data class Success(val message: String) : DemoStepState
    data class Failure(val reason: String) : DemoStepState
    data object Skipped : DemoStepState
}

/** One-shot effects emitted by ViewModel, consumed by composable. */
sealed interface OnboardingEffect {
    data object OpenAccessibilitySettings : OnboardingEffect
    data object OpenOverlaySettings : OnboardingEffect
    data object OpenBatteryOptimization : OnboardingEffect
    data object OpenBatteryOptimizationList : OnboardingEffect
    data object BringMainActivityToFront : OnboardingEffect
}

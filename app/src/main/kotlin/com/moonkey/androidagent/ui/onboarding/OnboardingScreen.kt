package com.moonkey.androidagent.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.moonkey.androidagent.onboarding.ApiKeyStepState
import com.moonkey.androidagent.onboarding.DemoStepState
import com.moonkey.androidagent.onboarding.OnboardingEffect
import com.moonkey.androidagent.onboarding.OnboardingProvider
import com.moonkey.androidagent.onboarding.OnboardingStepState
import com.moonkey.androidagent.onboarding.PermissionStepState
import com.moonkey.androidagent.onboarding.StepOutcomes
import com.moonkey.androidagent.onboarding.WizardStep
import kotlinx.coroutines.flow.Flow

/**
 * Full-screen onboarding wizard.
 *
 * Routes to per-step composables based on [currentStep]. Uses [OnboardingShell]
 * for shared scaffold (progress bar, step count, title).
 */
@Composable
fun OnboardingScreen(
    currentStep: WizardStep,
    stepState: OnboardingStepState?,
    outcomes: StepOutcomes,
    selectedProvider: OnboardingProvider,
    effects: Flow<OnboardingEffect>,
    onOpenSettings: () -> Unit,
    onSkipStep: () -> Unit,
    onProviderSelected: (OnboardingProvider) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onValidateApiKey: () -> Unit,
    onRetryValidation: () -> Unit,
    onStartDemo: () -> Unit,
    onFinish: () -> Unit,
    onEffect: (OnboardingEffect) -> Unit
) {
    // Consume one-shot effects
    LaunchedEffect(Unit) {
        effects.collect { effect -> onEffect(effect) }
    }

    val totalSteps = 5 // Accessibility, Overlay, Battery, ApiKey, Demo (Complete is not a "step")

    when (currentStep) {
        WizardStep.Accessibility -> {
            OnboardingShell(
                stepIndex = 1,
                totalSteps = totalSteps,
                title = "Let Android Agent control your phone"
            ) {
                PermissionStepContent(
                    step = WizardStep.Accessibility,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {}
                )
            }
        }

        WizardStep.Overlay -> {
            OnboardingShell(
                stepIndex = 2,
                totalSteps = totalSteps,
                title = "See controls while the agent works"
            ) {
                PermissionStepContent(
                    step = WizardStep.Overlay,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {}
                )
            }
        }

        WizardStep.Battery -> {
            OnboardingShell(
                stepIndex = 3,
                totalSteps = totalSteps,
                title = "Keep long tasks alive"
            ) {
                PermissionStepContent(
                    step = WizardStep.Battery,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = onSkipStep
                )
            }
        }

        WizardStep.ApiKey -> {
            OnboardingShell(
                stepIndex = 4,
                totalSteps = totalSteps,
                title = "Connect your model"
            ) {
                ApiKeyStepContent(
                    state = stepState as? ApiKeyStepState ?: ApiKeyStepState.Empty,
                    selectedProvider = selectedProvider,
                    onProviderSelected = onProviderSelected,
                    onKeyChanged = onApiKeyChanged,
                    onValidate = onValidateApiKey,
                    onRetry = onRetryValidation
                )
            }
        }

        WizardStep.Demo -> {
            OnboardingShell(
                stepIndex = 5,
                totalSteps = totalSteps,
                title = "Try a safe demo"
            ) {
                DemoStepContent(
                    state = stepState as? DemoStepState ?: DemoStepState.Ready,
                    onRunDemo = onStartDemo,
                    onSkip = onSkipStep
                )
            }
        }

        WizardStep.Complete -> {
            CompleteStepContent(
                outcomes = outcomes,
                onFinish = onFinish
            )
        }
    }
}

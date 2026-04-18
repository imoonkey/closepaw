package ai.closepaw.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ai.closepaw.onboarding.ApiKeyAuthMethod
import ai.closepaw.onboarding.ApiKeyStepState
import ai.closepaw.onboarding.DemoStepState
import ai.closepaw.onboarding.OnboardingEffect
import ai.closepaw.onboarding.OnboardingProvider
import ai.closepaw.onboarding.OnboardingStepState
import ai.closepaw.onboarding.PermissionStepState
import ai.closepaw.onboarding.StepOutcomes
import ai.closepaw.onboarding.WizardStep
import kotlinx.coroutines.flow.Flow

/**
 * Full-screen onboarding wizard.
 *
 * Routes to per-step composables based on [currentStep]. Uses [OnboardingShell]
 * for shared scaffold (progress bar, step count, title, back arrow).
 */
@Composable
fun OnboardingScreen(
    currentStep: WizardStep,
    stepState: OnboardingStepState?,
    outcomes: StepOutcomes,
    selectedProvider: OnboardingProvider,
    authMethod: ApiKeyAuthMethod,
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    batteryGranted: Boolean,
    effects: Flow<OnboardingEffect>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipStep: () -> Unit,
    onProviderSelected: (OnboardingProvider) -> Unit,
    onAuthMethodSelected: (ApiKeyAuthMethod) -> Unit,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onValidateApiKey: () -> Unit,
    onRetryValidation: () -> Unit,
    onStartDemo: () -> Unit,
    onFinish: () -> Unit,
    onGoToAuthStep: () -> Unit,
    onEffect: (OnboardingEffect) -> Unit
) {
    // Consume one-shot effects
    LaunchedEffect(Unit) {
        effects.collect { effect -> onEffect(effect) }
    }

    val totalSteps = 5
    // Back arrow on all steps except the first
    val backAction: (() -> Unit)? = if (currentStep != WizardStep.Accessibility) onBack else null

    when (currentStep) {
        WizardStep.Accessibility -> {
            OnboardingShell(
                stepIndex = 1,
                totalSteps = totalSteps,
                title = "Let ClosePaw control your phone"
            ) {
                PermissionStepContent(
                    step = WizardStep.Accessibility,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Overlay -> {
            OnboardingShell(
                stepIndex = 2,
                totalSteps = totalSteps,
                title = "See controls while the agent works",
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Overlay,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Battery -> {
            OnboardingShell(
                stepIndex = 3,
                totalSteps = totalSteps,
                title = "Keep long tasks alive",
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Battery,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = onSkipStep,
                    onContinue = onContinue
                )
            }
        }

        WizardStep.ApiKey -> {
            OnboardingShell(
                stepIndex = 4,
                totalSteps = totalSteps,
                title = "Connect your model",
                onBack = backAction
            ) {
                ApiKeyStepContent(
                    state = stepState as? ApiKeyStepState ?: ApiKeyStepState.Empty,
                    selectedProvider = selectedProvider,
                    authMethod = authMethod,
                    onProviderSelected = onProviderSelected,
                    onAuthMethodSelected = onAuthMethodSelected,
                    onStartOAuth = onStartOAuth,
                    onCancelOAuth = onCancelOAuth,
                    onContinue = onContinue,
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
                title = "Try a safe demo",
                onBack = backAction
            ) {
                DemoStepContent(
                    state = stepState as? DemoStepState ?: DemoStepState.Ready,
                    onRunDemo = onStartDemo,
                    onSkip = onSkipStep,
                    onGoToAuthStep = onGoToAuthStep
                )
            }
        }

        WizardStep.Complete -> {
            CompleteStepContent(
                outcomes = outcomes,
                authMethod = authMethod,
                accessibilityGranted = accessibilityGranted,
                overlayGranted = overlayGranted,
                batteryGranted = batteryGranted,
                onFinish = onFinish
            )
        }
    }
}

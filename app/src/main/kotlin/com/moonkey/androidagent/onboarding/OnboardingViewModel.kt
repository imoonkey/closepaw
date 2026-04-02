package com.moonkey.androidagent.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moonkey.androidagent.app.AppSettingsState
import com.moonkey.androidagent.app.AppSettingsStore
import com.moonkey.androidagent.llm.LLMProvider
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.llm.ModelEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Onboarding wizard state machine.
 *
 * Manages step transitions, permission checks, credential validation, and demo lifecycle.
 * Persists durable outcomes via [OnboardingStore]; transient UI state is derived on each resume.
 */
class OnboardingViewModel(
    private val context: Context,
    private val store: OnboardingStore,
    private val settingsState: AppSettingsState,
    private val modelCatalog: ModelCatalog,
    private val permissionMonitor: PermissionStateMonitor,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "OnboardingVM"
        private const val AUTO_ADVANCE_DELAY_MS = 400L
        private const val A11Y_POLL_INTERVAL_MS = 200L
        private const val A11Y_POLL_MAX_ATTEMPTS = 15 // 3 seconds
    }

    // ── Observable state ──

    var currentStep by mutableStateOf(WizardStep.Accessibility)
        private set

    var stepState by mutableStateOf<OnboardingStepState>(PermissionStepState.Checking)
        private set

    var outcomes by mutableStateOf(StepOutcomes())
        private set

    private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // ── Validator and demo controller (injected after construction) ──

    var demoController: OnboardingDemoController? = null

    // ── Provider selection for API key step ──

    var selectedProvider by mutableStateOf(OnboardingProvider.OPENAI)
        private set

    val providerLabel: String get() = selectedProvider.label

    fun selectProvider(provider: OnboardingProvider) {
        if (currentStep != WizardStep.ApiKey) return
        if (provider == selectedProvider) return
        selectedProvider = provider
        // Reset key field when switching providers
        val existingKey = getExistingApiKeyForProvider(provider)
        stepState = if (existingKey?.isNotBlank() == true) ApiKeyStepState.Editing(existingKey)
            else ApiKeyStepState.Empty
    }

    // ── Initialization ──

    init {
        outcomes = store.loadOutcomes()
        val firstIncomplete = firstIncompleteStep()
        currentStep = firstIncomplete
        enterStep(firstIncomplete, isResume = false)
    }

    // ── Lifecycle ──

    /** Called from MainActivity.onResume() — re-check current permission step. */
    fun onHostResumed() {
        if (currentStep in listOf(WizardStep.Accessibility, WizardStep.Overlay, WizardStep.Battery)) {
            checkCurrentPermission(isReturnFromSettings = true)
        }
    }

    // ── Actions ──

    fun openSystemSettings() {
        when (currentStep) {
            WizardStep.Accessibility -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenAccessibilitySettings)
            }
            WizardStep.Overlay -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenOverlaySettings)
            }
            WizardStep.Battery -> {
                stepState = PermissionStepState.OpeningSettings
                _effects.trySend(OnboardingEffect.OpenBatteryOptimization)
            }
            else -> {}
        }
    }

    fun onApiKeyChanged(key: String) {
        if (currentStep != WizardStep.ApiKey) return
        stepState = if (key.isBlank()) ApiKeyStepState.Empty else ApiKeyStepState.Editing(key)
        if (key.isNotBlank()) store.saveApiKeyDraft(key) else store.clearApiKeyDraft()
    }

    fun validateApiKey() {
        val state = stepState
        val key = when (state) {
            is ApiKeyStepState.Editing -> state.key
            is ApiKeyStepState.Invalid -> state.key
            else -> return
        }
        if (key.isBlank()) return

        stepState = ApiKeyStepState.Validating(key)
        scope.launch {
            val validator = createValidatorForProvider(selectedProvider)
            if (validator == null) {
                stepState = ApiKeyStepState.TransientError(key, "No model found for ${selectedProvider.label}")
                return@launch
            }
            val result = validator.validate(key)
            when (result) {
                is LlmCredentialValidator.Result.Valid -> {
                    stepState = ApiKeyStepState.Valid(key)
                    saveApiKeyForProvider(selectedProvider, key)
                    store.clearApiKeyDraft()
                    store.saveOutcome(WizardStep.ApiKey, StepOutcome.Done)
                    outcomes = outcomes.copy(apiKey = StepOutcome.Done)
                    delay(AUTO_ADVANCE_DELAY_MS)
                    advanceToNextStep()
                }
                is LlmCredentialValidator.Result.InvalidKey -> {
                    stepState = ApiKeyStepState.Invalid(key, result.message)
                }
                is LlmCredentialValidator.Result.TransientError -> {
                    stepState = ApiKeyStepState.TransientError(key, result.message)
                }
            }
        }
    }

    fun retryValidation() {
        val state = stepState
        if (state is ApiKeyStepState.TransientError) {
            stepState = ApiKeyStepState.Editing(state.key)
            validateApiKey()
        }
    }

    fun startDemo() {
        if (currentStep != WizardStep.Demo) return
        stepState = DemoStepState.Preflight

        // Preflight: re-check hard gates
        if (!isAccessibilityEnabled() || !isOverlayEnabled()) {
            Log.w(TAG, "Demo preflight failed: permission revoked")
            val brokenStep = if (!isAccessibilityEnabled()) WizardStep.Accessibility else WizardStep.Overlay
            store.saveOutcome(brokenStep, StepOutcome.Pending)
            outcomes = when (brokenStep) {
                WizardStep.Accessibility -> outcomes.copy(accessibility = StepOutcome.Pending)
                else -> outcomes.copy(overlay = StepOutcome.Pending)
            }
            currentStep = brokenStep
            enterStep(brokenStep, isResume = false)
            return
        }
        if (outcomes.apiKey != StepOutcome.Done) {
            currentStep = WizardStep.ApiKey
            enterStep(WizardStep.ApiKey, isResume = false)
            return
        }

        stepState = DemoStepState.Running
        demoController?.run(
            onSuccess = { message ->
                stepState = DemoStepState.Success(message)
                store.saveOutcome(WizardStep.Demo, StepOutcome.Done)
                outcomes = outcomes.copy(demo = StepOutcome.Done)
                scope.launch {
                    delay(AUTO_ADVANCE_DELAY_MS)
                    advanceToNextStep()
                }
            },
            onFailure = { reason ->
                stepState = DemoStepState.Failure(reason)
            },
            onBringToFront = {
                _effects.trySend(OnboardingEffect.BringMainActivityToFront)
            }
        )
    }

    fun skipStep() {
        when (currentStep) {
            WizardStep.Battery -> {
                store.saveOutcome(WizardStep.Battery, StepOutcome.Skipped)
                outcomes = outcomes.copy(battery = StepOutcome.Skipped)
                advanceToNextStep()
            }
            WizardStep.Demo -> {
                store.saveOutcome(WizardStep.Demo, StepOutcome.Skipped)
                outcomes = outcomes.copy(demo = StepOutcome.Skipped)
                advanceToNextStep()
            }
            else -> {}
        }
    }

    fun finish() {
        store.setCompleted()
        Log.d(TAG, "Onboarding completed")
    }

    // ── Internal ──

    private fun firstIncompleteStep(): WizardStep {
        // Live hard-gate checks override stored Done
        if (!isAccessibilityEnabled()) return WizardStep.Accessibility
        if (!isOverlayEnabled()) return WizardStep.Overlay
        if (outcomes.battery == StepOutcome.Pending && !isBatteryOptimized()) return WizardStep.Battery
        if (outcomes.battery != StepOutcome.Pending && outcomes.apiKey == StepOutcome.Pending) return WizardStep.ApiKey
        if (outcomes.battery == StepOutcome.Pending) return WizardStep.Battery
        if (outcomes.apiKey == StepOutcome.Pending) return WizardStep.ApiKey
        if (outcomes.demo == StepOutcome.Pending) return WizardStep.Demo
        return WizardStep.Complete
    }

    private fun enterStep(step: WizardStep, isResume: Boolean) {
        currentStep = step
        when (step) {
            WizardStep.Accessibility, WizardStep.Overlay, WizardStep.Battery -> {
                checkCurrentPermission(isReturnFromSettings = isResume)
            }
            WizardStep.ApiKey -> {
                // Auto-select provider if user already has a key for one
                val openaiKey = getExistingApiKeyForProvider(OnboardingProvider.OPENAI)
                val openrouterKey = getExistingApiKeyForProvider(OnboardingProvider.OPENROUTER)
                if (openaiKey?.isNotBlank() == true) {
                    selectedProvider = OnboardingProvider.OPENAI
                } else if (openrouterKey?.isNotBlank() == true) {
                    selectedProvider = OnboardingProvider.OPENROUTER
                }
                // Pre-populate from draft or existing key for selected provider
                val draft = store.loadApiKeyDraft()
                val existingKey = getExistingApiKeyForProvider(selectedProvider)
                val key = draft ?: existingKey
                stepState = if (key?.isNotBlank() == true) ApiKeyStepState.Editing(key) else ApiKeyStepState.Empty
            }
            WizardStep.Demo -> {
                stepState = DemoStepState.Ready
            }
            WizardStep.Complete -> {
                stepState = DemoStepState.Ready // not used for Complete
            }
        }
    }

    private fun checkCurrentPermission(isReturnFromSettings: Boolean) {
        stepState = PermissionStepState.Checking

        val satisfied = when (currentStep) {
            WizardStep.Accessibility -> isAccessibilityEnabled()
            WizardStep.Overlay -> isOverlayEnabled()
            WizardStep.Battery -> isBatteryOptimized()
            else -> return
        }

        if (satisfied) {
            onPermissionSatisfied()
            return
        }

        if (currentStep == WizardStep.Accessibility && isReturnFromSettings) {
            // A11y service connection can lag — poll briefly
            scope.launch {
                for (i in 1..A11Y_POLL_MAX_ATTEMPTS) {
                    delay(A11Y_POLL_INTERVAL_MS)
                    if (isAccessibilityEnabled()) {
                        onPermissionSatisfied()
                        return@launch
                    }
                }
                stepState = if (isReturnFromSettings) PermissionStepState.Unsatisfied
                    else PermissionStepState.Ready
            }
            return
        }

        stepState = if (isReturnFromSettings) PermissionStepState.Unsatisfied
            else PermissionStepState.Ready
    }

    private fun onPermissionSatisfied() {
        stepState = PermissionStepState.Satisfied
        val outcomeStep = currentStep
        store.saveOutcome(outcomeStep, StepOutcome.Done)
        outcomes = when (outcomeStep) {
            WizardStep.Accessibility -> outcomes.copy(accessibility = StepOutcome.Done)
            WizardStep.Overlay -> outcomes.copy(overlay = StepOutcome.Done)
            WizardStep.Battery -> outcomes.copy(battery = StepOutcome.Done)
            else -> outcomes
        }
        scope.launch {
            delay(AUTO_ADVANCE_DELAY_MS)
            advanceToNextStep()
        }
    }

    private fun advanceToNextStep() {
        val next = nextStep(currentStep)
        enterStep(next, isResume = false)
    }

    private fun nextStep(current: WizardStep): WizardStep = when (current) {
        WizardStep.Accessibility -> WizardStep.Overlay
        WizardStep.Overlay -> WizardStep.Battery
        WizardStep.Battery -> WizardStep.ApiKey
        WizardStep.ApiKey -> WizardStep.Demo
        WizardStep.Demo -> WizardStep.Complete
        WizardStep.Complete -> WizardStep.Complete
    }

    // ── Permission checks (delegate to monitor) ──

    private fun isAccessibilityEnabled(): Boolean = permissionMonitor.isAccessibilityEnabled()

    private fun isOverlayEnabled(): Boolean = permissionMonitor.isOverlayEnabled()

    private fun isBatteryOptimized(): Boolean = permissionMonitor.isBatteryOptimized()

    // ── Settings integration ──

    private fun getExistingApiKeyForProvider(provider: OnboardingProvider): String? {
        val keys = settingsState.buildApiKeys()
        return keys[provider.apiKeyEnv]?.takeIf { it.isNotBlank() }
    }

    /** Find a model entry for the given provider to use for validation. */
    private fun findModelForProvider(provider: OnboardingProvider): ModelEntry? {
        val llmProvider = when (provider) {
            OnboardingProvider.OPENAI -> LLMProvider.OPENAI
            OnboardingProvider.OPENROUTER -> LLMProvider.OPENROUTER
        }
        return modelCatalog.all().firstOrNull { it.provider == llmProvider }
    }

    private fun createValidatorForProvider(provider: OnboardingProvider): LlmCredentialValidator? {
        val entry = findModelForProvider(provider) ?: return null
        val baseUrl = entry.effectiveBaseUrl ?: "https://api.openai.com/v1"
        return HttpLlmCredentialValidator(baseUrl, entry.modelId)
    }

    private fun saveApiKeyForProvider(provider: OnboardingProvider, key: String) {
        when (provider) {
            OnboardingProvider.OPENAI -> settingsState.updateApiKey(key)
            OnboardingProvider.OPENROUTER -> settingsState.updateOpenRouterApiKey(key)
        }
        // Set default model to one from the chosen provider
        val entry = findModelForProvider(provider)
        if (entry != null) {
            settingsState.updateModel(entry.name)
            Log.d(TAG, "Default model set to ${entry.name} (${provider.label})")
        }
        Log.d(TAG, "API key saved for provider ${provider.label}")
    }
}

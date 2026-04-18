package ai.closepaw.onboarding

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.app.AppSettingsState
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.OpenAiSignInResult
import ai.closepaw.auth.openAiSignIn
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding wizard state machine.
 *
 * Auth credentials are read from and written to [AuthStore]; the API-key typed
 * during manual entry is held only in `stepState` and is lost on process death.
 * Wizard progress (step outcomes, completion) persists via [OnboardingStore].
 */
class OnboardingViewModel(
    private val store: OnboardingStore,
    private val settingsState: AppSettingsState,
    private val modelCatalog: ModelCatalog,
    private val permissionMonitor: PermissionStateMonitor,
    private val authStore: AuthStore,
    private val demoController: OnboardingDemoController,
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

    // ── Provider selection for API key step ──

    var selectedProvider by mutableStateOf(OnboardingProvider.OPENAI_API)
        private set

    var authMethod by mutableStateOf(ApiKeyAuthMethod.OAUTH)
        private set

    val providerLabel: String get() = selectedProvider.label

    private var oauthJob: kotlinx.coroutines.Job? = null

    fun selectProvider(provider: OnboardingProvider) {
        if (currentStep != WizardStep.ApiKey) return
        if (provider == selectedProvider) return
        selectedProvider = provider
        if (provider == OnboardingProvider.OPENAI_API) {
            authMethod = ApiKeyAuthMethod.OAUTH
            stepState = ApiKeyStepState.OAuthReady
        } else {
            authMethod = ApiKeyAuthMethod.MANUAL
            stepState = ApiKeyStepState.Empty
        }
    }

    fun selectAuthMethod(method: ApiKeyAuthMethod) {
        if (currentStep != WizardStep.ApiKey) return
        authMethod = method
        stepState = if (method == ApiKeyAuthMethod.MANUAL) ApiKeyStepState.Empty
        else ApiKeyStepState.OAuthReady
    }

    /** Launch OAuth flow using shared suspend helper. */
    fun startOAuth() {
        if (stepState is ApiKeyStepState.OAuthInProgress) return

        oauthJob = scope.launch {
            stepState = ApiKeyStepState.OAuthInProgress

            val result = openAiSignIn { url ->
                _effects.trySend(OnboardingEffect.LaunchOAuth(url))
            }

            when (result) {
                is OpenAiSignInResult.Success -> {
                    val tokens = result.tokens
                    withContext(Dispatchers.IO) {
                        authStore.set(
                            LLMProvider.OPENAI_CODEX,
                            AuthCredential.OAuth(
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                expiresAt = tokens.expiresAt,
                                email = tokens.email,
                                idToken = tokens.idToken,
                            )
                        )
                    }
                    applyDefaultModelFor(LLMProvider.OPENAI_CODEX)
                    store.saveOutcome(WizardStep.ApiKey, StepOutcome.Done)
                    outcomes = outcomes.copy(apiKey = StepOutcome.Done)
                    stepState = ApiKeyStepState.OAuthSuccess(tokens.email ?: "")
                    Log.d(TAG, "OAuth success, email=${tokens.email}")
                    delay(AUTO_ADVANCE_DELAY_MS)
                    advanceToNextStep()
                }
                is OpenAiSignInResult.Error -> {
                    stepState = ApiKeyStepState.OAuthError(result.message)
                }
            }
        }
    }

    fun cancelOAuth() {
        oauthJob?.cancel()
        oauthJob = null
        stepState = ApiKeyStepState.OAuthReady
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

    fun goBack() {
        val prev = previousStep(currentStep) ?: return
        enterStep(prev, isResume = false, autoAdvance = false)
    }

    /** Manual advance from a satisfied step (used after back navigation). */
    fun continueForward() {
        advanceToNextStep()
    }

    /** Jump back to the ApiKey step from a credential error in the demo. */
    fun goToAuthStep() {
        store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending)
        store.saveOutcome(WizardStep.Demo, StepOutcome.Pending)
        outcomes = outcomes.copy(
            apiKey = StepOutcome.Pending,
            demo = StepOutcome.Pending,
        )
        currentStep = WizardStep.ApiKey
        enterStep(WizardStep.ApiKey, isResume = false)
    }

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
                    withContext(Dispatchers.IO) {
                        authStore.set(selectedProvider.llmProvider, AuthCredential.ApiKey(key))
                    }
                    applyDefaultModelFor(selectedProvider.llmProvider)
                    stepState = ApiKeyStepState.Valid(key)
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
        demoController.run(
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
            onCredentialError = { message, isOAuth ->
                stepState = DemoStepState.CredentialError(message, isOAuth)
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

    private fun enterStep(step: WizardStep, isResume: Boolean, autoAdvance: Boolean = true) {
        currentStep = step
        when (step) {
            WizardStep.Accessibility, WizardStep.Overlay, WizardStep.Battery -> {
                checkCurrentPermission(isReturnFromSettings = isResume, autoAdvance = autoAdvance)
            }
            WizardStep.ApiKey -> {
                // If step already Done (back navigation), show a success state
                // derived from AuthStore rather than any OnboardingStore value.
                // If AuthStore has no matching credential (cleared/upgraded), fall
                // through to the normal editable state so the user can recover.
                if (outcomes.apiKey == StepOutcome.Done) {
                    if (authStore.has(LLMProvider.OPENAI_CODEX)) {
                        authMethod = ApiKeyAuthMethod.OAUTH
                        selectedProvider = OnboardingProvider.OPENAI_API
                        stepState = ApiKeyStepState.OAuthSuccess("")
                        return
                    }
                    val matched = OnboardingProvider.entries
                        .firstOrNull { authStore.has(it.llmProvider) }
                    if (matched != null) {
                        authMethod = ApiKeyAuthMethod.MANUAL
                        selectedProvider = matched
                        stepState = ApiKeyStepState.Valid("")
                        return
                    }
                    // Credential was deleted — reset outcome so user can re-enter.
                    store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending)
                    outcomes = outcomes.copy(apiKey = StepOutcome.Pending)
                }

                if (selectedProvider == OnboardingProvider.OPENAI_API) {
                    authMethod = ApiKeyAuthMethod.OAUTH
                    stepState = ApiKeyStepState.OAuthReady
                } else {
                    authMethod = ApiKeyAuthMethod.MANUAL
                    stepState = ApiKeyStepState.Empty
                }
            }
            WizardStep.Demo -> {
                stepState = DemoStepState.Ready
            }
            WizardStep.Complete -> {
                stepState = DemoStepState.Ready // not used for Complete
            }
        }
    }

    private fun checkCurrentPermission(isReturnFromSettings: Boolean, autoAdvance: Boolean = true) {
        stepState = PermissionStepState.Checking

        val satisfied = when (currentStep) {
            WizardStep.Accessibility -> isAccessibilityEnabled()
            WizardStep.Overlay -> isOverlayEnabled()
            WizardStep.Battery -> isBatteryOptimized()
            else -> return
        }

        if (satisfied) {
            if (autoAdvance) {
                onPermissionSatisfied()
            } else {
                stepState = PermissionStepState.Satisfied
            }
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

    /** Returns null for the first step (no back from Accessibility). */
    private fun previousStep(current: WizardStep): WizardStep? = when (current) {
        WizardStep.Accessibility -> null
        WizardStep.Overlay -> WizardStep.Accessibility
        WizardStep.Battery -> WizardStep.Overlay
        WizardStep.ApiKey -> WizardStep.Battery
        WizardStep.Demo -> WizardStep.ApiKey
        WizardStep.Complete -> WizardStep.Demo
    }

    // ── Permission checks (delegate to monitor) ──

    private fun isAccessibilityEnabled(): Boolean = permissionMonitor.isAccessibilityEnabled()
    private fun isOverlayEnabled(): Boolean = permissionMonitor.isOverlayEnabled()
    private fun isBatteryOptimized(): Boolean = permissionMonitor.isBatteryOptimized()

    // ── Settings/catalog integration ──

    private fun applyDefaultModelFor(provider: LLMProvider) {
        val entry = modelCatalog.modelsFor(provider).lastOrNull() ?: return
        settingsState.updateModel(entry.name)
        Log.d(TAG, "Default model set to ${entry.name} for provider $provider")
    }

    private fun createValidatorForProvider(provider: OnboardingProvider): LlmCredentialValidator? {
        val entry: ModelEntry = modelCatalog.modelsFor(provider.llmProvider).lastOrNull() ?: return null
        val baseUrl = resolveBaseUrl(entry)
        return HttpLlmCredentialValidator(baseUrl, entry.modelId)
    }

    /**
     * Mirrors [ai.closepaw.llm.LLMClientFactory.build]: for OPENAI_API entries, an
     * [AppSettingsState.openaiBaseUrl] override (set from `.env` via intent) wins over
     * the catalog entry's baseUrl. Required so debug builds talking to a proxy validate
     * against the proxy, not api.openai.com.
     */
    private fun resolveBaseUrl(entry: ModelEntry): String {
        val override = settingsState.openaiBaseUrl
        if (entry.provider == LLMProvider.OPENAI_API && override.isNotBlank()) return override
        return entry.effectiveBaseUrl ?: "https://api.openai.com/v1"
    }
}

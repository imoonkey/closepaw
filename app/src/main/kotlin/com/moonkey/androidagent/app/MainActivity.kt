package com.moonkey.androidagent.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.history.ResumedSessionData
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.model.SessionInfo
import com.moonkey.androidagent.history.model.isReloadable
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.onboarding.DefaultOnboardingDemoController
import com.moonkey.androidagent.onboarding.OnboardingEffect
import com.moonkey.androidagent.onboarding.OnboardingStore
import com.moonkey.androidagent.onboarding.OnboardingViewModel
import com.moonkey.androidagent.onboarding.PermissionStateMonitor
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.platform.OverlayTouchGate
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.session.SessionCoordinator
import com.moonkey.androidagent.session.SubmitResult
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.onboarding.OnboardingScreen
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus
import com.moonkey.androidagent.ui.theme.ChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_INTENT_PAYLOAD_CONSUMED = "intent_payload_consumed"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_FRESH_SESSION = "fresh_session"
        const val EXTRA_LLM_BACKEND = "llm_backend" // "openai" or "local"
        const val EXTRA_PERCEPTION_MODE = "perception_mode"
        const val EXTRA_DEBUG_MODE = "debug_mode"
        const val EXTRA_TRACE_ENABLED = "trace_enabled"
        const val EXTRA_TRACE_RUN_ID = "trace_run_id"
        const val EXTRA_AGENT_MODE = "agent_mode"
        const val EXTRA_MAIN_MODEL = "main_model"
        const val EXTRA_EXECUTOR_MODEL = "executor_model"
        const val EXTRA_MAX_TURNS = "max_turns"
        const val EXTRA_PLATFORM_MODE = "platform_mode"
        const val EXTRA_EXCLUDED_TOOLS = "excluded_tools"
        const val EXTRA_OPENROUTER_API_KEY = "openrouter_api_key"
        const val EXTRA_NOVITA_API_KEY = "novita_api_key"
        const val EXTRA_OPENAI_BASE_URL = "openai_base_url"
    }

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val coordinator = SessionCoordinator(sessionScope)
    private lateinit var settingsState: AppSettingsState
    private var pendingTraceEnabled: Boolean? = null
    private var pendingTraceRunId: String? = null
    private var pendingExcludedTools: Set<String> = emptySet()
    private var pendingAutoStartGoal: String? = null
    private var pendingGoalRunnable: Runnable? = null
    private var intentPayloadConsumed = false
    private lateinit var sessionHistoryManager: SessionHistoryManager
    private lateinit var viewModel: ChatViewModel
    private var showSettings by mutableStateOf(false)
    private lateinit var onboardingStore: OnboardingStore
    private lateinit var oauthCredentialStore: com.moonkey.androidagent.auth.OAuthCredentialStore
    private var onboardingViewModel: OnboardingViewModel? = null
    private var onboardingRequired by mutableStateOf(false)

    private enum class SessionLaunchPolicy {
        AUTO,
        FORCE_FRESH
    }

    private val modelCatalog: ModelCatalog by lazy {
        try {
            val json = assets.open("llm_models.json").bufferedReader().use { it.readText() }
            ModelCatalog.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load model catalog for UI", e)
            ModelCatalog.fromJson(
                    """{"glm-5":{"display_name":"GLM-5","provider":"OPENROUTER","api":"chat","model_id":"z-ai/glm-5"}}"""
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intentPayloadConsumed = savedInstanceState?.getBoolean(KEY_INTENT_PAYLOAD_CONSUMED, false) ?: false
        settingsState = AppSettingsState(AppSettingsStore(applicationContext))
        settingsState.load()

        // Onboarding: migrate + check completion
        onboardingStore = OnboardingStore(applicationContext)
        oauthCredentialStore = com.moonkey.androidagent.auth.OAuthCredentialStore(applicationContext)
        onboardingStore.migrateIfNeeded {
            hasLegacyUsageEvidence()
        }

        // Eval/debug bypass: EXTRA_FRESH_SESSION + EXTRA_GOAL → skip onboarding
        val isEvalMode = intent.getBooleanExtra(EXTRA_FRESH_SESSION, false) &&
            intent.hasExtra(EXTRA_GOAL)
        onboardingRequired = !onboardingStore.isCompleted && !isEvalMode

        if (onboardingRequired) {
            val vm = OnboardingViewModel(
                context = applicationContext,
                store = onboardingStore,
                settingsState = settingsState,
                modelCatalog = modelCatalog,
                permissionMonitor = PermissionStateMonitor(applicationContext),
                oauthCredentialStore = oauthCredentialStore,
                scope = lifecycleScope
            )
            // Wire demo controller
            vm.demoController = DefaultOnboardingDemoController(
                settingsState = settingsState,
                modelCatalog = modelCatalog,
                scope = lifecycleScope
            )
            onboardingViewModel = vm
        }

        handleIntent(intent)
        val sessionStorage = SessionStorage(applicationContext)
        sessionHistoryManager = SessionHistoryManager.create(sessionStorage, sessionScope)
        viewModel =
                ChatViewModel(
                        sessionProvider = { coordinator.currentSession },
                        sessionHistoryManager = sessionHistoryManager,
                        onSessionNeeded = { text -> ensureSessionAndSend(text) }
                )

        setContent {
            if (onboardingRequired) {
                val vm = onboardingViewModel!!
                ChatTheme {
                    OnboardingScreen(
                        currentStep = vm.currentStep,
                        stepState = vm.stepState,
                        outcomes = vm.outcomes,
                        selectedProvider = vm.selectedProvider,
                        authMethod = vm.authMethod,
                        effects = vm.effects,
                        onBack = { vm.goBack() },
                        onContinue = { vm.continueForward() },
                        onOpenSettings = { vm.openSystemSettings() },
                        onSkipStep = { vm.skipStep() },
                        onProviderSelected = { vm.selectProvider(it) },
                        onAuthMethodSelected = { vm.selectAuthMethod(it) },
                        onStartOAuth = { vm.startOAuth() },
                        onCancelOAuth = { vm.cancelOAuth() },
                        onApiKeyChanged = { vm.onApiKeyChanged(it) },
                        onValidateApiKey = { vm.validateApiKey() },
                        onRetryValidation = { vm.retryValidation() },
                        onStartDemo = { vm.startDemo() },
                        onFinish = {
                            vm.finish()
                            onboardingRequired = false
                        },
                        onEffect = { effect -> handleOnboardingEffect(effect) }
                    )
                }
            } else {
                MainActivityContent(
                    viewModel = viewModel,
                    settingsState = settingsState,
                    modelCatalog = modelCatalog,
                    appVersion = BuildConfig.VERSION_NAME,
                    showSettings = showSettings,
                    onShowSettingsChange = { showSettings = it },
                    onSessionSelect = { session ->
                        coordinator.selectedSessionForReload = session
                        viewModel.resumeSession(session) {
                            sessionHistoryManager.setActiveSessionId(null)
                            sessionHistoryManager.getRecordingService().clearSessionAndAwait()
                            coordinator.detachSession()
                            Log.d(
                                    TAG,
                                    "History session resumed for viewing; cleared recording state"
                            )
                        }
                    },
                    onNewSession = {
                        coordinator.selectedSessionForReload = null
                        viewModel.startNewSession(settingsState.selectedModel, BuildConfig.VERSION_NAME)
                    },
                    onOpenViewer = { openViewer(this@MainActivity) },
                    isAccessibilityEnabled = AgentService.instance != null,
                    isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity),
                    onAccessibilityClick = { openAccessibilitySettings(this@MainActivity) },
                    onOverlayClick = { openOverlaySettings(this@MainActivity) },
                    repairModel = deriveRepairModel(),
                    onFixBattery = {
                        handleOnboardingEffect(OnboardingEffect.OpenBatteryOptimization)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        setIntent(intent)
        intentPayloadConsumed = false
        handleIntent(intent)
        AgentService.instance?.onMainAppVisible()
    }

    override fun onStart() {
        super.onStart()
        AgentService.instance?.onMainAppVisible()
        rebindActiveServiceSessionIfNeeded()
        retryPendingAutoStartGoalIfReady()
    }

    override fun onResume() {
        super.onResume()
        AgentService.instance?.onMainAppVisible()
        onboardingViewModel?.onHostResumed()
        retryPendingAutoStartGoalIfReady()
    }

    override fun onDestroy() {
        pendingGoalRunnable?.let { window.decorView.removeCallbacks(it) }
        pendingGoalRunnable = null
        sessionScope.cancel()
        super.onDestroy()
        Log.d(TAG, "onDestroy called, session active: ${coordinator.currentSession != null}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INTENT_PAYLOAD_CONSUMED, intentPayloadConsumed)
    }

    private fun handleIntent(intent: Intent) {
        val payload = MainActivityIntentPayload.from(intent)
        val applyResult =
                applyIntentPayloadToSettings(
                        payload = payload,
                        settingsState = settingsState,
                        currentPendingTraceEnabled = pendingTraceEnabled,
                        currentPendingTraceRunId = pendingTraceRunId,
                        currentPendingExcludedTools = pendingExcludedTools,
                        log = { message -> Log.d(TAG, message) }
                )
        pendingTraceEnabled = applyResult.pendingTraceEnabled
        pendingTraceRunId = applyResult.pendingTraceRunId
        pendingExcludedTools = applyResult.pendingExcludedTools

        if (intentPayloadConsumed) {
            Log.d(TAG, "Intent payload already consumed, skipping action dispatch")
            return
        }
        intentPayloadConsumed = true

        if (payload.freshSession) {
            Log.d(TAG, "Fresh session requested, clearing existing state")
            lifecycleScope.launch {
                clearCurrentSession()
                coordinator.selectedSessionForReload = null
                payload.goalText?.let {
                    Log.d(TAG, "Goal set from intent: $it")
                    delay(500)
                    ensureSessionAndSend(it, launchPolicy = SessionLaunchPolicy.FORCE_FRESH)
                }
            }
        } else {
            payload.goalText?.let {
                Log.d(TAG, "Goal set from intent: $it")
                scheduleGoalDispatch(it)
            }
        }
    }

    private suspend fun clearCurrentSession() {
        coordinator.clearSession()

        if (::viewModel.isInitialized) {
            viewModel.clearConversation()
        }

        if (::sessionHistoryManager.isInitialized) {
            sessionHistoryManager.setActiveSessionId(null)
            sessionHistoryManager.getRecordingService().clearSessionAndAwait()
        }

        Log.d(TAG, "Current session cleared")
    }

    private fun rebindActiveServiceSessionIfNeeded() {
        val service = AgentService.instance ?: return
        val serviceSession = service.getActiveSession() ?: return
        if (coordinator.currentSession === serviceSession) return

        coordinator.attachSession(serviceSession)
        sessionHistoryManager.setActiveSessionId(serviceSession.sessionId.value)
        val snapshot = serviceSession.getServices().recordingService.getCurrentSession()
        snapshot?.let {
            viewModel.restoreMessagesFromRecords(snapshot.messages)
            viewModel.startEventCollection(
                    serviceSession,
                    replayCutoffTimestamp = snapshot.lastUpdated
            )
            Log.i(
                    TAG,
                    "Rebound active session ${serviceSession.sessionId} with ${snapshot.messages.size} recorded messages"
            )
        } ?: run {
            viewModel.startEventCollection(serviceSession)
            Log.i(TAG, "Rebound active session ${serviceSession.sessionId} without recorder snapshot")
        }
    }

    /**
     * Validate preconditions (permissions, services), then route input through
     * the [SessionCoordinator]: submit to existing session, or create a new one.
     *
     * Input queuing and drain are handled by the coordinator (event-driven,
     * no timer-loop).
     */
    private fun ensureSessionAndSend(
            text: String,
            launchPolicy: SessionLaunchPolicy = SessionLaunchPolicy.AUTO
    ) {
        if (!validateCloudKeysForSelectedModels()) return

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
            openOverlaySettings(this)
            return
        }

        val service = AgentService.instance
        if (service == null) {
            pendingAutoStartGoal = text
            Toast.makeText(this, "Please enable the Accessibility Service", Toast.LENGTH_LONG)
                    .show()
            openAccessibilitySettings(this)
            return
        }

        lifecycleScope.launch {
            // Try existing session first
            val submitResult = coordinator.submit(text)
            when (submitResult) {
                SubmitResult.SENT -> {
                    pendingAutoStartGoal = null
                    return@launch
                }
                SubmitResult.QUEUED -> return@launch
                SubmitResult.NO_SESSION, SubmitResult.SESSION_DEAD -> { /* create new session */ }
            }

            // Auto-reload: if session just died and no explicit reload target is set,
            // recover the dead session's checkpoint so the user keeps context.
            var autoReload = false
            if (coordinator.selectedSessionForReload == null
                && launchPolicy != SessionLaunchPolicy.FORCE_FRESH
            ) {
                val deadFileName = coordinator.consumeDeadSessionFileName()
                val deadSessionId = sessionHistoryManager.getCurrentSessionId()
                if (deadFileName != null && deadSessionId != null) {
                    coordinator.selectedSessionForReload = SessionInfo(
                        id = deadSessionId,
                        fileName = deadFileName,
                        startTime = 0,
                        lastUpdated = 0,
                        messageCount = 0,
                        displayTitle = "",
                        firstUserMessage = ""
                    )
                    autoReload = true
                }
            }

            // Create new session under coordinator's creation lock
            try {
                val created = coordinator.createAndSubmit(text) {
                    createOrReloadSession(service, launchPolicy, autoReload)
                }
                if (!created) {
                    // Creation lock unavailable (another creation in progress).
                    // Enqueue directly — input drains when session transitions to Idle/Created.
                    coordinator.enqueue(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                if (settingsState.llmBackend == LLMBackendType.LOCAL) {
                    settingsState.updateModelLoadingStatus(
                            ModelLoadingStatus.Error(e.message ?: "Unknown error")
                    )
                }
                Toast.makeText(
                                this@MainActivity,
                                "Failed to start: ${e.message}",
                                Toast.LENGTH_LONG
                        )
                        .show()
            }
        }
    }

    /**
     * Create or reload a session. Called inside the coordinator's creation lock.
     * Returns null to abort creation (e.g. non-reloadable checkpoint).
     *
     * @param autoReload If true, reload failure falls back to a fresh session
     *   silently instead of aborting with a toast (used for dead-session recovery).
     */
    private suspend fun createOrReloadSession(
            service: AgentService,
            launchPolicy: SessionLaunchPolicy,
            autoReload: Boolean = false
    ): AgentSession? {
        refreshOAuthTokenIfNeeded()
        val apiKeys = settingsState.buildApiKeys()
        val visualizer = service.getActionVisualizer()
        val touchGate = service.getOverlayTouchGate()
        val selectedForReload =
                if (launchPolicy == SessionLaunchPolicy.FORCE_FRESH) null
                else coordinator.selectedSessionForReload

        val session = if (selectedForReload != null) {
            val reloaded = tryReloadSelectedSession(
                    service = service,
                    apiKeys = apiKeys,
                    visualizer = visualizer,
                    touchGate = touchGate,
                    selected = selectedForReload
            )
            if (reloaded != null) {
                Log.i(TAG, "Reloaded session ${reloaded.sessionId} from checkpoint")
                reloaded
            } else if (autoReload) {
                Log.w(TAG, "Auto-reload failed for ${selectedForReload.id}, falling back to fresh session")
                coordinator.selectedSessionForReload = null
                createFreshSession(service, apiKeys, visualizer, touchGate)
            } else {
                Log.w(TAG, "Selected session ${selectedForReload.id} has no reloadable checkpoint")
                Toast.makeText(
                                this@MainActivity,
                                "Unable to reload selected session context. Please start a new session or select another history item.",
                                Toast.LENGTH_LONG
                        )
                        .show()
                return null
            }
        } else {
            coordinator.selectedSessionForReload = null
            createFreshSession(service, apiKeys, visualizer, touchGate)
        }

        pendingTraceEnabled = null
        pendingTraceRunId = null
        pendingExcludedTools = emptySet()
        pendingAutoStartGoal = null

        sessionHistoryManager.setActiveSessionId(session.sessionId.value)
        viewModel.startEventCollection(session)
        service.observeExternalSession(session, settingsState.platformMode)

        Log.i(TAG, "Session ready with backend=${settingsState.llmBackend} and message sent")
        return session
    }

    private suspend fun tryReloadSelectedSession(
            service: AgentService,
            apiKeys: Map<String, String>,
            visualizer: ActionVisualizerManager?,
            touchGate: OverlayTouchGate?,
            selected: SessionInfo
    ): AgentSession? {
        val storage = SessionStorage(applicationContext)
        val contextFileName = storage.contextFileNameFor(selected.fileName)
        val snapshot = storage.readSnapshot(contextFileName).getOrNull() ?: return null
        if (snapshot.schemaVersion != 1) return null
        if (!snapshot.checkpointState.isReloadable()) return null

        val session = withContext(Dispatchers.Default) {
            AgentSession.reload(
                    snapshot = snapshot,
                    service = service,
                    scope = sessionScope,
                    apiKeys = apiKeys,
                    visualizer = visualizer,
                    overlayTouchGate = touchGate,
            )
        }
        if (session == null) return null

        val existingRecord = storage.readSession(selected.fileName).getOrNull()
        if (existingRecord != null) {
            session.getServices().recordingService.resumeSession(
                    ResumedSessionData(
                            session = existingRecord,
                            fileName = selected.fileName
                    )
            )
        }
        return session
    }

    private suspend fun createFreshSession(
            service: AgentService,
            apiKeys: Map<String, String>,
            visualizer: ActionVisualizerManager?,
            touchGate: OverlayTouchGate?
    ): AgentSession {
        val localConfig =
                if (settingsState.llmBackend == LLMBackendType.LOCAL) {
                    LocalLLMConfig(
                            modelSlug = settingsState.localModelSlug,
                            quantizationSlug = settingsState.localModelQuant
                    )
                } else null

        if (settingsState.llmBackend == LLMBackendType.LOCAL) {
            settingsState.updateModelLoadingStatus(ModelLoadingStatus.Loading)
        }

        val sessionConfig =
                SessionConfig(
                        maxTurns = settingsState.maxTurns,
                        mainModel = settingsState.selectedModel,
                        executorModel = settingsState.executorModel,
                        debugMode = settingsState.debugMode,
                        traceEnabled = pendingTraceEnabled ?: settingsState.debugMode,
                        traceRunId = pendingTraceRunId,
                        llm =
                                SessionLlmConfig(
                                        backendType = settingsState.llmBackend,
                                        localConfig = localConfig
                                ),
                        agentMode = settingsState.agentMode,
                        perceptionConfig =
                                when (settingsState.perceptionMode) {
                                    "screenshot_only" ->
                                            PerceptionConfig.ScreenshotOnly()
                                    "hybrid" -> PerceptionConfig.Hybrid()
                                    else -> PerceptionConfig.AccessibilityOnly
                                },
                        platformMode = settingsState.platformMode,
                        excludedTools = pendingExcludedTools
                )

        val session =
                withContext(Dispatchers.Default) {
                    AgentSession.create(
                            config = sessionConfig,
                            service = service,
                            scope = sessionScope,
                            apiKeys = apiKeys,
                            visualizer = visualizer,
                            overlayTouchGate = touchGate,
                    )
                }

        if (settingsState.llmBackend == LLMBackendType.LOCAL) {
            val localClient = session.getServices().llmClient as? LFMLLMClient
            if (localClient == null) {
                settingsState.updateModelLoadingStatus(
                        ModelLoadingStatus.Error("Local LLM client unavailable")
                )
            } else {
                localClient.loadModel { state ->
                    settingsState.updateModelLoadingStatus(state.toUiStatus())
                }
            }
        }

        return session
    }

    private fun retryPendingAutoStartGoalIfReady() {
        val pendingGoal = pendingAutoStartGoal ?: return
        if (AgentService.instance == null) return
        if (!Settings.canDrawOverlays(this)) return
        if (findMissingCloudKeys(settingsState, modelCatalog).isNotEmpty()) return
        // Clear before dispatching to prevent double-fire from rapid lifecycle callbacks
        pendingAutoStartGoal = null
        ensureSessionAndSend(pendingGoal)
    }

    private fun scheduleGoalDispatch(goal: String, delayMs: Long = 500L) {
        pendingGoalRunnable?.let { window.decorView.removeCallbacks(it) }
        val runnable =
                Runnable {
                    pendingGoalRunnable = null
                    ensureSessionAndSend(goal)
                }
        pendingGoalRunnable = runnable
        window.decorView.postDelayed(runnable, delayMs)
    }

    private fun validateCloudKeysForSelectedModels(): Boolean {
        val missing = findMissingCloudKeys(settingsState, modelCatalog)

        if (missing.isEmpty()) return true

        Toast.makeText(this, "Missing API key(s): ${missing.joinToString("; ")}", Toast.LENGTH_LONG)
                .show()
        showSettings = true
        return false
    }

    // ── OAuth token refresh ──

    private suspend fun refreshOAuthTokenIfNeeded() {
        val creds = oauthCredentialStore.load() ?: return
        if (!oauthCredentialStore.isExpiringSoon()) return

        Log.d(TAG, "OAuth token expiring soon, refreshing...")
        val result = com.moonkey.androidagent.auth.OAuthTokenExchange.refresh(creds.refreshToken)
        when (result) {
            is com.moonkey.androidagent.auth.OAuthTokenExchange.Result.Success -> {
                oauthCredentialStore.save(result.tokens)
                settingsState.updateApiKey(result.tokens.accessToken)
                Log.d(TAG, "OAuth token refreshed successfully")
            }
            is com.moonkey.androidagent.auth.OAuthTokenExchange.Result.Error -> {
                Log.w(TAG, "OAuth token refresh failed: ${result.message}")
                // Proceed with current token — may fail at API call
            }
        }
    }

    // ── Onboarding helpers ──

    private fun handleOnboardingEffect(effect: OnboardingEffect) {
        when (effect) {
            OnboardingEffect.OpenAccessibilitySettings ->
                openAccessibilitySettings(this)
            OnboardingEffect.OpenOverlaySettings ->
                openOverlaySettings(this)
            OnboardingEffect.OpenBatteryOptimization -> {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Unable to open battery settings", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            OnboardingEffect.OpenBatteryOptimizationList -> {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open battery settings", Toast.LENGTH_SHORT).show()
                }
            }
            OnboardingEffect.BringMainActivityToFront -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(intent)
            }
            is OnboardingEffect.LaunchOAuth -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch OAuth browser", e)
                    Toast.makeText(this, "Unable to open browser for sign-in", Toast.LENGTH_SHORT).show()
                    onboardingViewModel?.cancelOAuth()
                }
            }
        }
    }

    /** Derive permission repair model for post-onboarding state. */
    private fun deriveRepairModel(): PermissionStateMonitor.PermissionRepairModel? {
        if (!onboardingStore.isCompleted) return null
        val outcomes = onboardingStore.loadOutcomes()
        val batteryWasDone = outcomes.battery == com.moonkey.androidagent.onboarding.StepOutcome.Done
        return PermissionStateMonitor(applicationContext).deriveRepairModel(batteryWasDone)
    }

    /** Check for evidence this is an existing user (for onboarding migration). */
    private fun hasLegacyUsageEvidence(): Boolean {
        val settings = settingsState
        // Existing API key or non-default model/backend → existing user
        if (settings.apiKey.isNotBlank()) return true
        if (settings.openRouterApiKey.isNotBlank()) return true
        if (settings.novitaApiKey.isNotBlank()) return true
        if (settings.selectedModel != AppSettingsStore.DEFAULT_MODEL) return true
        if (settings.llmBackend != AppSettingsStore.DEFAULT_LLM_BACKEND) return true
        // Allow-list entries
        val allowList = AppSettingsStore(applicationContext).loadPersistentAllowList()
        if (allowList.isNotEmpty()) return true
        // Session directory has files
        val sessionsDir = java.io.File(applicationContext.filesDir, "sessions")
        if (sessionsDir.exists() && (sessionsDir.listFiles()?.isNotEmpty() == true)) return true
        return false
    }
}

package com.moonkey.androidagent.app

import android.content.Intent
import android.os.Bundle
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
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.protocol.SessionState
import com.moonkey.androidagent.platform.OverlayTouchGate
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus
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
        const val EXTRA_AUTO_START = "auto_start"
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
        const val EXTRA_OPENROUTER_API_KEY = "openrouter_api_key"
        const val EXTRA_NOVITA_API_KEY = "novita_api_key"
    }

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentSession: AgentSession? = null
    private val sessionCreationLock = Any()
    @Volatile private var sessionCreationInProgress = false
    private val pendingInputs = mutableListOf<String>()  // guarded by sessionCreationLock
    private lateinit var settingsState: AppSettingsState
    private var pendingTraceEnabled: Boolean? = null
    private var pendingTraceRunId: String? = null
    private var pendingAutoStartGoal: String? = null
    private var pendingGoalRunnable: Runnable? = null
    private var drainPendingRunnable: Runnable? = null
    private var intentPayloadConsumed = false
    private var selectedSessionForReload: SessionInfo? = null
    private lateinit var sessionHistoryManager: SessionHistoryManager
    private lateinit var viewModel: ChatViewModel
    private var showSettings by mutableStateOf(false)

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
        handleIntent(intent)
        val sessionStorage = SessionStorage(applicationContext)
        sessionHistoryManager = SessionHistoryManager.create(sessionStorage, sessionScope)
        viewModel =
                ChatViewModel(
                        sessionProvider = { currentSession },
                        sessionHistoryManager = sessionHistoryManager,
                        onSessionNeeded = { text -> ensureSessionAndSend(text) },
                        onTaskCompleted = {
                            Log.d(TAG, "Task completed; session remains in Idle for follow-up")
                        }
                )

        setContent {
            MainActivityContent(
                    viewModel = viewModel,
                    settingsState = settingsState,
                    modelCatalog = modelCatalog,
                    appVersion = BuildConfig.VERSION_NAME,
                    showSettings = showSettings,
                    onShowSettingsChange = { showSettings = it },
                    onSessionSelect = { session ->
                        selectedSessionForReload = session
                        viewModel.resumeSession(session) {
                            sessionHistoryManager.setActiveSessionId(null)
                            sessionHistoryManager.getRecordingService().clearSessionAndAwait()
                            currentSession = null
                            Log.d(
                                    TAG,
                                    "History session resumed for viewing; cleared recording state"
                            )
                        }
                    },
                    onNewSession = {
                        selectedSessionForReload = null
                        viewModel.startNewSession(settingsState.selectedModel, BuildConfig.VERSION_NAME)
                    },
                    onOpenViewer = { openViewer(this@MainActivity) },
                    isAccessibilityEnabled = AgentService.instance != null,
                    isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity),
                    onAccessibilityClick = { openAccessibilitySettings(this@MainActivity) },
                    onOverlayClick = { openOverlaySettings(this@MainActivity) }
            )
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
        retryPendingAutoStartGoalIfReady()
    }

    override fun onDestroy() {
        pendingGoalRunnable?.let { window.decorView.removeCallbacks(it) }
        pendingGoalRunnable = null
        drainPendingRunnable?.let { window.decorView.removeCallbacks(it) }
        drainPendingRunnable = null
        sessionScope.cancel()
        super.onDestroy()
        Log.d(TAG, "onDestroy called, session active: ${currentSession != null}")
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
                        log = { message -> Log.d(TAG, message) }
                )
        pendingTraceEnabled = applyResult.pendingTraceEnabled
        pendingTraceRunId = applyResult.pendingTraceRunId

        // Guard: skip action dispatch if this intent was already consumed.
        // Prevents activity recreation from re-processing stale fresh_session/goal
        // extras, which would clear an existing session and restart the original goal.
        if (intentPayloadConsumed) {
            Log.d(TAG, "Intent payload already consumed, skipping action dispatch")
            return
        }
        intentPayloadConsumed = true

        if (payload.freshSession) {
            Log.d(TAG, "Fresh session requested, clearing existing state")
            lifecycleScope.launch {
                clearCurrentSession()
                selectedSessionForReload = null
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
        currentSession?.let { session ->
            try {
                session.submit(Op.Shutdown)
                delay(100)
                Log.d(TAG, "Existing session shutdown completed")
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down session: ${e.message}")
            }
        }
        currentSession = null

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
        if (currentSession === serviceSession) return

        currentSession = serviceSession
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

    private fun ensureSessionAndSend(
            text: String,
            launchPolicy: SessionLaunchPolicy = SessionLaunchPolicy.AUTO
    ) {
        if (!validateCloudKeysForSelectedModels()) {
            return
        }

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

        if (currentSession == null) {
            val shouldCreate =
                    synchronized(sessionCreationLock) {
                        if (currentSession != null || sessionCreationInProgress) {
                            false
                        } else {
                            sessionCreationInProgress = true
                            true
                        }
                    }
            if (!shouldCreate) {
                lifecycleScope.launch {
                    val active = currentSession
                    if (active != null) {
                        when (active.state.value) {
                            SessionState.Shutdown -> {
                                currentSession = null
                                ensureSessionAndSend(text, launchPolicy)
                            }
                            SessionState.Running, SessionState.Paused -> {
                                enqueuePendingInput(text)
                            }
                            else -> active.submit(Op.UserInput(text))
                        }
                    } else {
                        enqueuePendingInput(text)
                    }
                }
                return
            }

            lifecycleScope.launch {
                try {
                    val apiKeys = settingsState.buildApiKeys()
                    val visualizer = service.getActionVisualizer()
                    val touchGate = service.getOverlayTouchGate()
                    val selectedForReload =
                            if (launchPolicy == SessionLaunchPolicy.FORCE_FRESH) {
                                null
                            } else {
                                selectedSessionForReload
                            }

                    val reloaded =
                            if (selectedForReload != null) {
                                tryReloadSelectedSession(
                                        service = service,
                                        apiKeys = apiKeys,
                                        visualizer = visualizer,
                                        touchGate = touchGate,
                                        selected = selectedForReload
                                )
                            } else {
                                null
                            }

                    val session = if (reloaded != null) {
                        Log.i(TAG, "Reloaded session ${reloaded.sessionId} from checkpoint")
                        reloaded
                    } else if (selectedForReload != null) {
                        Log.w(
                                TAG,
                                "Selected session ${selectedForReload.id} has no reloadable checkpoint"
                        )
                        Toast.makeText(
                                        this@MainActivity,
                                        "Unable to reload selected session context. Please start a new session or select another history item.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                        return@launch
                    } else {
                        selectedSessionForReload = null
                        createFreshSession(service, apiKeys, visualizer, touchGate)
                    }

                    currentSession = session
                    pendingTraceEnabled = null
                    pendingTraceRunId = null

                    sessionHistoryManager.setActiveSessionId(session.sessionId.value)
                    viewModel.startEventCollection(session)

                    service.observeExternalSession(session, settingsState.platformMode)
                    session.submit(Op.UserInput(text))
                    pendingAutoStartGoal = null
                    drainPendingInputs()

                    Log.i(
                            TAG,
                            "Session ready with backend=${settingsState.llmBackend} and message sent"
                    )
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
                } finally {
                    synchronized(sessionCreationLock) {
                        sessionCreationInProgress = false
                    }
                }
            }
        } else {
            pendingAutoStartGoal = null
            val session = currentSession
            when (session?.state?.value) {
                null, SessionState.Shutdown -> {
                    currentSession = null
                    ensureSessionAndSend(text, launchPolicy)
                    return
                }
                SessionState.Running, SessionState.Paused -> {
                    enqueuePendingInput(text)
                    return
                }
                else -> {
                    lifecycleScope.launch { session.submit(Op.UserInput(text)) }
                }
            }
        }
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
                        platformMode = settingsState.platformMode
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
        if (sessionCreationInProgress) return
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

    private fun scheduleDrainPendingInputs(delayMs: Long = 200L) {
        drainPendingRunnable?.let { window.decorView.removeCallbacks(it) }
        val runnable =
                Runnable {
                    drainPendingRunnable = null
                    drainPendingInputs()
                }
        drainPendingRunnable = runnable
        window.decorView.postDelayed(runnable, delayMs)
    }

    private fun drainPendingInputs() {
        val inputs = synchronized(sessionCreationLock) {
            val copy = pendingInputs.toList()
            pendingInputs.clear()
            copy
        }
        if (inputs.isEmpty()) return
        lifecycleScope.launch {
            inputs.forEach { input -> ensureSessionAndSend(input) }
        }
    }

    private fun enqueuePendingInput(text: String) {
        val shouldScheduleRetry =
                synchronized(sessionCreationLock) {
                    pendingInputs.add(text)
                    pendingInputs.size == 1
                }
        if (shouldScheduleRetry) {
            scheduleDrainPendingInputs()
        }
    }

    private fun validateCloudKeysForSelectedModels(): Boolean {
        val missing = findMissingCloudKeys(settingsState, modelCatalog)

        if (missing.isEmpty()) return true

        Toast.makeText(this, "Missing API key(s): ${missing.joinToString("; ")}", Toast.LENGTH_LONG)
                .show()
        showSettings = true
        return false
    }
}

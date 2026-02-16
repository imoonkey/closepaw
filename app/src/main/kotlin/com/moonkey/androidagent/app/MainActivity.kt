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
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.protocol.SessionLlmConfig
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
    private lateinit var sessionHistoryManager: SessionHistoryManager
    private lateinit var viewModel: ChatViewModel
    private var showSettings by mutableStateOf(false)

    private val modelCatalog: ModelCatalog by lazy {
        try {
            val json = assets.open("llm_models.json").bufferedReader().use { it.readText() }
            ModelCatalog.fromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load model catalog for UI", e)
            ModelCatalog.fromJson(
                    """{"gpt-5.2":{"display_name":"GPT-5.2","provider":"OPENAI","api":"response","model_id":"gpt-5.2"}}"""
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                            Log.d(TAG, "Task completed; keeping session alive for next task")
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
                        viewModel.resumeSession(session) {
                            sessionHistoryManager.getRecordingService().clearSession()
                            currentSession = null
                            Log.d(
                                    TAG,
                                    "History session resumed for viewing; cleared recording state"
                            )
                        }
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
        handleIntent(intent)
        AgentService.instance?.onMainAppVisible()
    }

    override fun onStart() {
        super.onStart()
        AgentService.instance?.onMainAppVisible()
        rebindActiveServiceSessionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        AgentService.instance?.onMainAppVisible()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, session active: ${currentSession != null}")
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

        if (payload.freshSession) {
            Log.d(TAG, "Fresh session requested, clearing existing state")
            lifecycleScope.launch {
                clearCurrentSession()
                payload.goalText?.let {
                    Log.d(TAG, "Goal set from intent: $it")
                    delay(500)
                    ensureSessionAndSend(it)
                }
            }
        } else {
            payload.goalText?.let {
                Log.d(TAG, "Goal set from intent: $it")
                window.decorView.postDelayed({ ensureSessionAndSend(it) }, 500)
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
            sessionHistoryManager.getRecordingService().clearSession()
        }

        Log.d(TAG, "Current session cleared")
    }

    private fun rebindActiveServiceSessionIfNeeded() {
        val service = AgentService.instance ?: return
        val serviceSession = service.getActiveSession() ?: return
        if (currentSession === serviceSession) return

        currentSession = serviceSession
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

    private fun ensureSessionAndSend(text: String) {
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
                        active.submit(Op.UserInput(text))
                    } else {
                        val shouldScheduleRetry =
                                synchronized(sessionCreationLock) {
                                    pendingInputs.add(text)
                                    pendingInputs.size == 1
                                }
                        if (shouldScheduleRetry) {
                            window.decorView.postDelayed({ drainPendingInputs() }, 200)
                        }
                    }
                }
                return
            }

            lifecycleScope.launch {
                try {
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

                    val apiKeys = settingsState.buildApiKeys()
                    val visualizer = service.getActionVisualizer()
                    val session =
                            withContext(Dispatchers.Default) {
                                AgentSession.create(
                                        config = sessionConfig,
                                        service = service,
                                        scope = sessionScope,
                                        apiKeys = apiKeys,
                                        visualizer = visualizer
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

                    currentSession = session
                    pendingTraceEnabled = null
                    pendingTraceRunId = null

                    viewModel.startEventCollection(session)

                    service.observeExternalSession(session, settingsState.platformMode)
                    session.submit(Op.UserInput(text))
                    drainPendingInputs()

                    Log.i(
                            TAG,
                            "Session created with backend=${settingsState.llmBackend} and message sent"
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
                        pendingInputs.clear()
                    }
                }
            }
        } else {
            lifecycleScope.launch { currentSession?.submit(Op.UserInput(text)) }
        }
    }

    private fun drainPendingInputs() {
        val session = currentSession ?: return
        val inputs = synchronized(sessionCreationLock) {
            val copy = pendingInputs.toList()
            pendingInputs.clear()
            copy
        }
        lifecycleScope.launch {
            inputs.forEach { input -> session.submit(Op.UserInput(input)) }
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

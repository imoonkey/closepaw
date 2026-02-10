package com.moonkey.androidagent.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.chat.ChatScreen
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus
import com.moonkey.androidagent.ui.settings.SettingsSheet
import com.moonkey.androidagent.ui.settings.catalogModelOptions
import com.moonkey.androidagent.ui.theme.ChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        const val EXTRA_OPENROUTER_API_KEY = "openrouter_api_key"
    }

    // Session scope - survives configuration changes within activity lifecycle
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current session
    private var currentSession: AgentSession? = null

    // Settings state
    private lateinit var settingsState: AppSettingsState

    // Per-intent trace controls (not persisted to settings)
    private var pendingTraceEnabled: Boolean? = null
    private var pendingTraceRunId: String? = null

    // Session history
    private lateinit var sessionHistoryManager: SessionHistoryManager

    // ViewModel
    private lateinit var viewModel: ChatViewModel

    // Settings visibility
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

    @OptIn(ExperimentalMaterial3Api::class)
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
                            sessionHistoryManager.endSession()
                            currentSession = null
                            Log.d(TAG, "Session cleared after task completion")
                        }
                )

        setContent {
            ChatTheme {
                val sessions by viewModel.sessions.collectAsStateWithLifecycle()

                ChatScreen(
                        viewModel = viewModel,
                        sessions = sessions,
                        currentModel = settingsState.selectedModel,
                        appVersion = BuildConfig.VERSION_NAME,
                        onOpenSettings = { showSettings = true },
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
                        onNewSession = {
                            viewModel.startNewSession(
                                    settingsState.selectedModel,
                                    BuildConfig.VERSION_NAME
                            )
                        },
                        onDeleteSession = { session -> viewModel.deleteSession(session) },
                        onLoadSessions = { viewModel.loadSessions() }
                )

                if (showSettings) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    ModalBottomSheet(
                            onDismissRequest = { showSettings = false },
                            sheetState = sheetState,
                            dragHandle = {}
                    ) {
                        SettingsSheet(
                                llmBackend = settingsState.llmBackend,
                                onBackendChange = settingsState::updateBackend,
                                selectedModel = settingsState.selectedModel,
                                onModelChange = settingsState::updateModel,
                                modelOptions = catalogModelOptions(modelCatalog.all()),
                                selectedExecutorModel = settingsState.executorModel,
                                onExecutorModelChange = settingsState::updateExecutorModel,
                                selectedLocalModel = settingsState.selectedLocalModelId,
                                onLocalModelChange = settingsState::updateLocalModel,
                                modelLoadingStatus = settingsState.modelLoadingStatus,
                                apiKey = settingsState.apiKey,
                                onApiKeyChange = settingsState::updateApiKey,
                                maxTurns = settingsState.maxTurns,
                                onMaxTurnsChange = settingsState::updateMaxTurns,
                                agentMode = settingsState.agentMode,
                                onAgentModeChange = settingsState::updateAgentMode,
                                perceptionMode = settingsState.perceptionMode,
                                onPerceptionModeChange = settingsState::updatePerceptionMode,
                                debugMode = settingsState.debugMode,
                                onDebugModeChange = settingsState::updateDebugMode,
                                isAccessibilityEnabled = AgentService.instance != null,
                                isOverlayEnabled = Settings.canDrawOverlays(this@MainActivity),
                                onAccessibilityClick = { openAccessibilitySettings() },
                                onOverlayClick = { openOverlaySettings() },
                                onDismiss = { showSettings = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't shutdown session when activity is destroyed - agent continues in service
        // The session will be cleaned up when task completes or user explicitly stops
        Log.d(TAG, "onDestroy called, session active: ${currentSession != null}")
    }

    private fun handleIntent(intent: Intent) {
        val payload = MainActivityIntentPayload.from(intent)

        payload.apiKey?.let { key ->
            settingsState.updateApiKey(key)
            Log.d(TAG, "API key set from intent")
        }

        payload.openRouterApiKey?.let { key ->
            settingsState.updateOpenRouterApiKey(key)
            Log.d(TAG, "OpenRouter API key set from intent")
        }

        payload.backendType?.let {
            settingsState.updateBackend(it)
            Log.d(TAG, "LLM backend set from intent: $it")
        }

        payload.agentMode?.let {
            settingsState.updateAgentMode(it)
            Log.d(TAG, "Agent mode set from intent: $it")
        }

        payload.perceptionMode?.let { mode ->
            settingsState.updatePerceptionMode(mode)
            Log.d(TAG, "Perception mode set from intent: $mode")
        }

        payload.mainModel?.let {
            settingsState.updateModel(it)
            Log.d(TAG, "Main model set from intent: $it")
        }

        payload.executorModel?.let {
            settingsState.updateExecutorModel(it)
            Log.d(TAG, "Executor model set from intent: $it")
        }

        payload.debugMode?.let { enabled ->
            settingsState.updateDebugMode(enabled)
            Log.d(TAG, "Debug mode set from intent: $enabled")
        }

        payload.traceEnabled?.let { enabled ->
            pendingTraceEnabled = enabled
            Log.d(TAG, "Trace enabled set from intent: $enabled")
        }

        payload.traceRunId?.let { runId ->
            pendingTraceRunId = runId
            Log.d(TAG, "Trace run id set from intent: $runId")
        }

        if (payload.freshSession) {
            Log.d(TAG, "Fresh session requested, clearing existing state")
            lifecycleScope.launch {
                clearCurrentSession()
                payload.goalText?.let {
                    Log.d(TAG, "Goal set from intent: $it")
                    kotlinx.coroutines.delay(500)
                    ensureSessionAndSend(it)
                }
            }
        } else {
            payload.goalText?.let {
                Log.d(TAG, "Goal set from intent: $it")
                // Auto-send the goal as first message
                window.decorView.postDelayed({ ensureSessionAndSend(it) }, 500)
            }
        }

        if (payload.autoStart) {
            Log.d(TAG, "Auto-start requested")
        }
    }

    private suspend fun clearCurrentSession() {
        currentSession?.let { session ->
            try {
                session.submit(Op.Shutdown)
                kotlinx.coroutines.delay(100)
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

    private fun ensureSessionAndSend(text: String) {
        if (settingsState.llmBackend == LLMBackendType.OPENAI && settingsState.apiKey.isBlank()) {
            Toast.makeText(this, "Please set your API key in Settings", Toast.LENGTH_LONG).show()
            showSettings = true
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }

        val service = AgentService.instance
        if (service == null) {
            Toast.makeText(this, "Please enable the Accessibility Service", Toast.LENGTH_LONG)
                    .show()
            openAccessibilitySettings()
            return
        }

        if (currentSession == null) {
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

                    @Suppress("DEPRECATION")
                    val session =
                            AgentSession.create(
                                    config =
                                            SessionConfig(
                                                    maxTurns = settingsState.maxTurns,
                                                    mainModel = settingsState.selectedModel,
                                                    executorModel = settingsState.executorModel,
                                                    debugMode = settingsState.debugMode,
                                                    traceEnabled = pendingTraceEnabled
                                                                    ?: settingsState.debugMode,
                                                    traceRunId = pendingTraceRunId,
                                                    llmBackend = settingsState.llmBackend,
                                                    localLLMConfig = localConfig,
                                                    agentMode = settingsState.agentMode,
                                                    perceptionConfig =
                                                            when (settingsState.perceptionMode) {
                                                                "screenshot_only" ->
                                                                        PerceptionConfig
                                                                                .ScreenshotOnly()
                                                                "hybrid" ->
                                                                        PerceptionConfig.Hybrid()
                                                                else ->
                                                                        PerceptionConfig
                                                                                .AccessibilityOnly
                                                            }
                                            ),
                                    service = service,
                                    scope = sessionScope,
                                    apiKeys = settingsState.buildApiKeys(),
                                    visualizer = service.getActionVisualizer()
                            )

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

                    service.observeExternalSession(session)
                    session.submit(Op.UserInput(text))

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
                }
            }
        } else {
            lifecycleScope.launch { currentSession?.submit(Op.UserInput(text)) }
        }
    }

    private fun LFMLLMClient.ModelLoadingState.toUiStatus(): ModelLoadingStatus {
        return when (this) {
            is LFMLLMClient.ModelLoadingState.NotLoaded -> ModelLoadingStatus.Idle
            is LFMLLMClient.ModelLoadingState.Downloading ->
                    ModelLoadingStatus.Downloading(progress)
            is LFMLLMClient.ModelLoadingState.Loading -> ModelLoadingStatus.Loading
            is LFMLLMClient.ModelLoadingState.Ready -> ModelLoadingStatus.Ready
            is LFMLLMClient.ModelLoadingState.Error -> ModelLoadingStatus.Error(message)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openOverlaySettings() {
        val intent =
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }
}

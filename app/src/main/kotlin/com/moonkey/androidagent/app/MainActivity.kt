package com.moonkey.androidagent.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.moonkey.androidagent.BuildConfig
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moonkey.androidagent.history.SessionHistoryManager
import com.moonkey.androidagent.history.storage.SessionStorage
import com.moonkey.androidagent.llm.LFMLLMClient
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.llm.LocalLLMConfig
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.settings.AppSettingsState
import com.moonkey.androidagent.ui.settings.AppSettingsStore
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus
import com.moonkey.androidagent.ui.chat.ChatScreen
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.settings.SettingsSheet
import com.moonkey.androidagent.ui.theme.ChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MainActivity - Chat-based UI for the Android Agent.
 * 
 * Features:
 * - Modern chat interface with streaming support
 * - Material 3 design with dark mode support
 * - Edge-to-edge display
 * - Settings accessible via long-press on header
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_AUTO_START = "auto_start"
        const val EXTRA_FRESH_SESSION = "fresh_session"
        const val EXTRA_LLM_BACKEND = "llm_backend"  // "openai" or "local"
        const val EXTRA_SCREENSHOT_INPUT = "screenshot_input"
        const val EXTRA_DEBUG_MODE = "debug_mode"
        const val EXTRA_TRACE_ENABLED = "trace_enabled"
        const val EXTRA_TRACE_RUN_ID = "trace_run_id"
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // Load settings from storage
        settingsState = AppSettingsState(AppSettingsStore(applicationContext))
        settingsState.load()
        handleIntent(intent)
        
        // Initialize session history manager
        val sessionStorage = SessionStorage(applicationContext)
        sessionHistoryManager = SessionHistoryManager.create(sessionStorage, sessionScope)
        
        // Initialize ViewModel with session provider and session creation callback
        viewModel = ChatViewModel(
            sessionProvider = { currentSession },
            sessionHistoryManager = sessionHistoryManager,
            onSessionNeeded = { text -> ensureSessionAndSend(text) },
            onTaskCompleted = { 
                // Complete session recording when task completes
                sessionHistoryManager.endSession()
                // Clear session when task completes to allow new tasks
                currentSession = null
                Log.d(TAG, "Session cleared after task completion")
            }
        )
        
        setContent {
            ChatTheme {
                // Collect session list state
                val sessions by viewModel.sessions.collectAsStateWithLifecycle()
                
                ChatScreen(
                    viewModel = viewModel,
                    sessions = sessions,
                    currentModel = settingsState.selectedModel,
                    appVersion = BuildConfig.VERSION_NAME,
                    onOpenSettings = { showSettings = true },
                    onSessionSelect = { session ->
                        viewModel.resumeSession(session) {
                            // Session resumed for viewing history only
                            // End the resumed history session to avoid recording mismatch
                            // A new session will be created when user sends a message
                            sessionHistoryManager.getRecordingService().clearSession()
                            currentSession = null
                            Log.d(TAG, "History session resumed for viewing; cleared recording state")
                        }
                    },
                    onNewSession = {
                        viewModel.startNewSession(settingsState.selectedModel, BuildConfig.VERSION_NAME)
                    },
                    onDeleteSession = { session ->
                        viewModel.deleteSession(session)
                    },
                    onLoadSessions = {
                        viewModel.loadSessions()
                    }
                )
                
                // Settings Bottom Sheet
                if (showSettings) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    
                    ModalBottomSheet(
                        onDismissRequest = { showSettings = false },
                        sheetState = sheetState,
                        dragHandle = {}  // Hide default drag handle, use custom header instead
                    ) {
                        SettingsSheet(
                            // Backend selection
                            llmBackend = settingsState.llmBackend,
                            onBackendChange = settingsState::updateBackend,
                            // Cloud model
                            selectedModel = settingsState.selectedModel,
                            onModelChange = settingsState::updateModel,
                            // Local model
                            selectedLocalModel = settingsState.selectedLocalModelId,
                            onLocalModelChange = settingsState::updateLocalModel,
                            modelLoadingStatus = settingsState.modelLoadingStatus,
                            // API key
                            apiKey = settingsState.apiKey,
                            onApiKeyChange = settingsState::updateApiKey,
                            // Other settings
                            maxTurns = settingsState.maxTurns,
                            onMaxTurnsChange = settingsState::updateMaxTurns,
                            screenshotInputEnabled = settingsState.enableScreenshotInput,
                            onScreenshotInputChange = settingsState::updateScreenshotInputEnabled,
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

        payload.backendType?.let {
            settingsState.updateBackend(it)
            Log.d(TAG, "LLM backend set from intent: $it")
        }

        payload.screenshotInputEnabled?.let { enabled ->
            settingsState.updateScreenshotInputEnabled(enabled)
            Log.d(TAG, "Screenshot input set from intent: $enabled")
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
                window.decorView.postDelayed({
                    ensureSessionAndSend(it)
                }, 500)
            }
        }
        
        // Auto-start if requested
        if (payload.autoStart) {
            Log.d(TAG, "Auto-start requested")
        }
    }
    
    /**
     * Clear the current session and conversation state.
     * Used when starting a fresh session from intents (e.g., dev.sh, debug-run.sh).
     * 
     * This is a suspending function to ensure proper ordering - the old session
     * must be shutdown before clearing state to avoid race conditions.
     */
    private suspend fun clearCurrentSession() {
        // Shutdown existing session if running and wait for completion
        currentSession?.let { session ->
            try {
                session.submit(Op.Shutdown)
                // Brief delay to allow shutdown to propagate
                kotlinx.coroutines.delay(100)
                Log.d(TAG, "Existing session shutdown completed")
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down session: ${e.message}")
            }
        }
        currentSession = null
        
        // Clear conversation UI if ViewModel is initialized
        if (::viewModel.isInitialized) {
            viewModel.clearConversation()
        }
        
        // Clear session recording if manager is initialized
        if (::sessionHistoryManager.isInitialized) {
            sessionHistoryManager.getRecordingService().clearSession()
        }
        
        Log.d(TAG, "Current session cleared")
    }
    
    /**
     * Ensure session exists and send message.
     */
    private fun ensureSessionAndSend(text: String) {
        // Check API key only for cloud backend
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
            Toast.makeText(this, "Please enable the Accessibility Service", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        
        // Create session if needed
        if (currentSession == null) {
            lifecycleScope.launch {
                try {
                    // Build local LLM config if using local backend
                    val localConfig = if (settingsState.llmBackend == LLMBackendType.LOCAL) {
                        LocalLLMConfig(
                            modelSlug = settingsState.localModelSlug,
                            quantizationSlug = settingsState.localModelQuant
                        )
                    } else null
                    
                    // Update loading status for local backend
                    if (settingsState.llmBackend == LLMBackendType.LOCAL) {
                        settingsState.updateModelLoadingStatus(ModelLoadingStatus.Loading)
                    }
                    
                    val session = AgentSession.create(
                        config = SessionConfig(
                            maxTurns = settingsState.maxTurns,
                            model = settingsState.selectedModel,
                            debugMode = settingsState.debugMode,
                            traceEnabled = pendingTraceEnabled ?: settingsState.debugMode,
                            traceRunId = pendingTraceRunId,
                                llmBackend = settingsState.llmBackend,
                                localLLMConfig = localConfig,
                                enableScreenshotInput = settingsState.enableScreenshotInput &&
                                    settingsState.llmBackend == LLMBackendType.OPENAI
                        ),
                        service = service,
                        scope = sessionScope,
                        apiKey = if (settingsState.llmBackend == LLMBackendType.OPENAI) {
                            settingsState.apiKey
                        } else null,
                        visualizer = service.getActionVisualizer()
                    )
                    
                    // Update loading status using the local model loader callbacks
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
                    
                    // Connect ViewModel to session events
                    viewModel.startEventCollection(session)
                    
                    // Let AgentService observe the session for SmartCapsule updates
                    service.observeExternalSession(session)
                    
                    // Send the message
                    session.submit(Op.UserInput(text))
                    
                    Log.i(TAG, "Session created with backend=${settingsState.llmBackend} and message sent")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create session", e)
                    if (settingsState.llmBackend == LLMBackendType.LOCAL) {
                        settingsState.updateModelLoadingStatus(
                            ModelLoadingStatus.Error(e.message ?: "Unknown error")
                        )
                    }
                    Toast.makeText(this@MainActivity, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Session exists, just send
            lifecycleScope.launch {
                currentSession?.submit(Op.UserInput(text))
            }
        }
    }
    
    private fun LFMLLMClient.ModelLoadingState.toUiStatus(): ModelLoadingStatus {
        return when (this) {
            is LFMLLMClient.ModelLoadingState.NotLoaded -> ModelLoadingStatus.Idle
            is LFMLLMClient.ModelLoadingState.Downloading -> ModelLoadingStatus.Downloading(progress)
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
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

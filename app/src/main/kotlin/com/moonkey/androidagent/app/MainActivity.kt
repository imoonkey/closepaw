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
    }
    
    // Session scope - survives configuration changes within activity lifecycle
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Current session
    private var currentSession: AgentSession? = null
    
    // Settings state
    private var apiKey by mutableStateOf("")
    private var selectedModel by mutableStateOf(AppSettingsStore.DEFAULT_MODEL)
    private var maxTurns by mutableStateOf(AppSettingsStore.DEFAULT_MAX_TURNS)
    private var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
    
    // LLM Backend settings
    private var llmBackend by mutableStateOf(AppSettingsStore.DEFAULT_LLM_BACKEND)
    private var selectedLocalModelId by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_ID)
    private var localModelSlug by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_SLUG)
    private var localModelQuant by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_QUANT)
    private var modelLoadingStatus by mutableStateOf<ModelLoadingStatus>(ModelLoadingStatus.Idle)
    
    private lateinit var settingsStore: AppSettingsStore

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
        settingsStore = AppSettingsStore(applicationContext)
        loadSettings()
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
                    currentModel = selectedModel,
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
                        viewModel.startNewSession(selectedModel, BuildConfig.VERSION_NAME)
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
                            llmBackend = llmBackend,
                            onBackendChange = { backend ->
                                llmBackend = backend
                                settingsStore.saveBackend(backend)
                                // Reset model loading status when switching backends
                                modelLoadingStatus = ModelLoadingStatus.Idle
                            },
                            // Cloud model
                            selectedModel = selectedModel,
                            onModelChange = {
                                selectedModel = it
                                settingsStore.saveModel(it)
                            },
                            // Local model
                            selectedLocalModel = selectedLocalModelId,
                            onLocalModelChange = { model ->
                                selectedLocalModelId = model.id
                                localModelSlug = model.modelSlug
                                localModelQuant = model.quantizationSlug
                                settingsStore.saveLocalModel(model.id, model.modelSlug, model.quantizationSlug)
                                // Reset model loading status when changing local model
                                modelLoadingStatus = ModelLoadingStatus.Idle
                            },
                            modelLoadingStatus = modelLoadingStatus,
                            // API key
                            apiKey = apiKey,
                            onApiKeyChange = { 
                                apiKey = it
                                settingsStore.saveApiKey(it)
                            },
                            // Other settings
                            maxTurns = maxTurns,
                            onMaxTurnsChange = {
                                maxTurns = it
                                settingsStore.saveMaxTurns(it)
                            },
                            debugMode = debugMode,
                            onDebugModeChange = {
                                debugMode = it
                                settingsStore.saveDebugMode(it)
                            },
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
        intent.getStringExtra(EXTRA_API_KEY)?.let { key ->
            if (key.isNotBlank()) {
                apiKey = key
                settingsStore.saveApiKey(key)
                Log.d(TAG, "API key set from intent")
            }
        }
        
        // Handle LLM backend selection from intent
        intent.getStringExtra(EXTRA_LLM_BACKEND)?.let { backend ->
            val backendType = when (backend.lowercase()) {
                "local" -> LLMBackendType.LOCAL
                "openai" -> LLMBackendType.OPENAI
                else -> null
            }
            backendType?.let {
                llmBackend = it
                settingsStore.saveBackend(it)
                Log.d(TAG, "LLM backend set from intent: $it")
            }
        }
        
        val goalText = intent.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }
        val freshSession = intent.getBooleanExtra(EXTRA_FRESH_SESSION, false)

        if (freshSession) {
            Log.d(TAG, "Fresh session requested, clearing existing state")
            lifecycleScope.launch {
                clearCurrentSession()
                goalText?.let {
                    Log.d(TAG, "Goal set from intent: $it")
                    kotlinx.coroutines.delay(500)
                    ensureSessionAndSend(it)
                }
            }
        } else {
            goalText?.let {
                Log.d(TAG, "Goal set from intent: $it")
                // Auto-send the goal as first message
                window.decorView.postDelayed({
                    ensureSessionAndSend(it)
                }, 500)
            }
        }
        
        // Auto-start if requested
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
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
        if (llmBackend == LLMBackendType.OPENAI && apiKey.isBlank()) {
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
                    val localConfig = if (llmBackend == LLMBackendType.LOCAL) {
                        LocalLLMConfig(
                            modelSlug = localModelSlug,
                            quantizationSlug = localModelQuant
                        )
                    } else null
                    
                    // Update loading status for local backend
                    if (llmBackend == LLMBackendType.LOCAL) {
                        modelLoadingStatus = ModelLoadingStatus.Loading
                    }
                    
                    val session = AgentSession.create(
                        config = SessionConfig(
                            maxTurns = maxTurns,
                            model = selectedModel,
                            debugMode = debugMode,
                            llmBackend = llmBackend,
                            localLLMConfig = localConfig
                        ),
                        service = service,
                        scope = sessionScope,
                        apiKey = if (llmBackend == LLMBackendType.OPENAI) apiKey else null,
                        visualizer = service.getActionVisualizer()
                    )
                    
                    // Update loading status using the local model loader callbacks
                    if (llmBackend == LLMBackendType.LOCAL) {
                        val localClient = session.getServices().llmClient as? LFMLLMClient
                        if (localClient == null) {
                            modelLoadingStatus = ModelLoadingStatus.Error("Local LLM client unavailable")
                        } else {
                            localClient.loadModel { state ->
                                modelLoadingStatus = state.toUiStatus()
                            }
                        }
                    }
                    
                    currentSession = session
                    
                    // Connect ViewModel to session events
                    viewModel.startEventCollection(session)
                    
                    // Let AgentService observe the session for SmartCapsule updates
                    service.observeExternalSession(session)
                    
                    // Send the message
                    session.submit(Op.UserInput(text))
                    
                    Log.i(TAG, "Session created with backend=$llmBackend and message sent")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create session", e)
                    if (llmBackend == LLMBackendType.LOCAL) {
                        modelLoadingStatus = ModelLoadingStatus.Error(e.message ?: "Unknown error")
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
    
    /**
     * Load all settings from SharedPreferences.
     */
    private fun loadSettings() {
        val settings = settingsStore.load()
        apiKey = settings.apiKey
        selectedModel = settings.selectedModel
        maxTurns = settings.maxTurns
        debugMode = settings.debugMode
        llmBackend = settings.llmBackend
        selectedLocalModelId = settings.localModelId
        localModelSlug = settings.localModelSlug
        localModelQuant = settings.localModelQuant

        Log.d(
            TAG,
            "Settings loaded: backend=$llmBackend, model=$selectedModel, localModel=$selectedLocalModelId, maxTurns=$maxTurns, debugMode=$debugMode"
        )
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

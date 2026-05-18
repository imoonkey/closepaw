package ai.closepaw.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.memory.MemoryStore
import ai.closepaw.onboarding.PermissionStateMonitor.PermissionRepairModel
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionState
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.capsule.CapsuleBinding
import ai.closepaw.ui.capsule.InertCapsuleBinding
import ai.closepaw.ui.capsule.voice.VoicePermissionDisposition
import ai.closepaw.ui.capsule.voice.rememberVoicePermissionGate
import ai.closepaw.ui.chat.ChatScreen
import ai.closepaw.ui.chat.ChatViewModel
import ai.closepaw.ui.chat.SettingsDeepLink
import ai.closepaw.ui.chat.SettingsPage as DeepLinkPage
import ai.closepaw.ui.settings.OpenAiAuthUiState
import ai.closepaw.ui.settings.SettingsPage
import ai.closepaw.ui.settings.SettingsSheet
import ai.closepaw.ui.theme.ClosePawTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Build a [CapsuleBinding] backed by the running [AgentService], or [InertCapsuleBinding]
 * when the service isn't bound yet (so the chat surface still renders its idle state).
 */
@Composable
private fun rememberCapsuleBinding(): CapsuleBinding {
    val holder = AgentService.instance?.capsuleStateHolder ?: return InertCapsuleBinding
    return remember(holder) {
        CapsuleBinding(
            mode = holder.mode,
            platformMode = holder.platformMode,
            isStopPending = holder.isStopPending,
            previousMode = { holder.previousMode },
            onStopRequested = { holder.onStopRequested() },
            onApprovalResolved = { callId -> holder.onApprovalResolved(callId) },
            onUserResponseSent = { callId -> holder.onUserResponseSent(callId) },
        )
    }
}

@Composable
internal fun MainActivityContent(
    viewModel: ChatViewModel,
    settingsState: AppSettingsState,
    modelLoadingStatusHolder: ModelLoadingStatusHolder,
    modelCatalog: ModelCatalog,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    onSessionSelect: (ai.closepaw.history.model.SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenApp: (String) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    repairModel: PermissionRepairModel? = null,
    onFixBattery: () -> Unit = {},
    openAiAuthUiState: OpenAiAuthUiState = OpenAiAuthUiState.SignedOut,
    onStartOAuth: () -> Unit = {},
    onCancelOAuth: () -> Unit = {},
    onSignOut: () -> Unit = {},
    initialSettingsDeepLink: SettingsDeepLink? = null,
    effectivePlatformModeFlow: StateFlow<PlatformMode?> = MutableStateFlow(null),
    appClassifier: AppClassifier,
    currentSessionStateFlow: StateFlow<SessionState?> = MutableStateFlow(null),
    memoryStore: MemoryStore,
    memoryEditGate: MemoryEditGate,
) {
    ClosePawTheme {
        val sessions by viewModel.sessions.collectAsStateWithLifecycle()
        val effectivePlatformMode by effectivePlatformModeFlow.collectAsStateWithLifecycle()
        val currentSessionState by currentSessionStateFlow.collectAsStateWithLifecycle()
        // Mirrors MemoryEditGate: any non-Shutdown session (including creation-in-progress
        // Created) counts as "session running" for the next-session subtitle on disabled
        // skills. Only `null` or Shutdown means the next session reads the latest toggle.
        val isSessionRunning = currentSessionState != null && currentSessionState != SessionState.Shutdown

        // Voice cold-start route: AgentService.requestVoicePermissionViaMainActivity() brings
        // MainActivity to the front with EXTRA_REQUEST_VOICE_PERMISSION; MainActivity sets a
        // pending flag (process state, not intent state) and we drain it here once the launcher
        // is mounted. The launcher MUST be registered unconditionally at MainActivity scope so
        // a cold-start (process freshly launched by the overlay) finds it ready.
        val activity = LocalContext.current as? MainActivity
        if (activity != null) {
            val gate = rememberVoicePermissionGate(activity) { _ -> /* voice-ui owns the real callback */ }
            val pendingRequest = activity.isVoicePermissionRequestPending()
            LaunchedEffect(pendingRequest) {
                if (!pendingRequest) return@LaunchedEffect
                when (gate.disposition()) {
                    VoicePermissionDisposition.Request -> {
                        gate.requestPermission()
                        activity.clearVoicePermissionRequest()
                    }
                    VoicePermissionDisposition.Granted,
                    VoicePermissionDisposition.OpenAppSettings -> {
                        // Granted: user toggled in Settings between request and resume. OpenAppSettings:
                        // permanently denied — the overlay's next mic tap will surface UI; nothing to do
                        // from the cold-start route. Either way, just clear so we don't re-fire.
                        activity.clearVoicePermissionRequest()
                    }
                }
            }
        }

        // Deep-link target captured when a banner/tap wants Settings opened at a
        // specific tab. Forwarded into SettingsSheet via initialPage/initialAuthTab.
        // Seeded from [initialSettingsDeepLink] (set by host pre-flight checks like
        // missing-credential validation) so auto-opened sheets land on the right page.
        var pendingDeepLink by remember(initialSettingsDeepLink) {
            mutableStateOf<SettingsDeepLink?>(initialSettingsDeepLink)
        }

        Column {
            ChatScreen(
                viewModel = viewModel,
                capsuleBinding = rememberCapsuleBinding(),
                sessions = sessions,
                currentModel = settingsState.selectedModel,
                onOpenSettings = { deepLink ->
                    pendingDeepLink = deepLink
                    onShowSettingsChange(true)
                },
                onSessionSelect = onSessionSelect,
                onNewSession = onNewSession,
                onDeleteSession = { session -> viewModel.deleteSession(session) },
                onLoadSessions = { viewModel.loadSessions() },
                onOpenViewer = onOpenViewer,
                onOpenApp = onOpenApp,
                repairModel = repairModel,
                onFixAccessibility = onAccessibilityClick,
                onFixOverlay = onOverlayClick,
                onFixBattery = onFixBattery,
            )
        }

        if (showSettings) {
            val dismissSettings = {
                onShowSettingsChange(false)
                pendingDeepLink = null
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SettingsSheet(
                    llmBackend = settingsState.llmBackend,
                    onBackendChange = modelLoadingStatusHolder::updateBackend,
                    selectedModel = settingsState.selectedModel,
                    onModelChange = settingsState::updateModel,
                    modelCatalog = modelCatalog,
                    selectedLocalModel = settingsState.localModel.id,
                    onLocalModelChange = modelLoadingStatusHolder::updateLocalModel,
                    modelLoadingStatus = modelLoadingStatusHolder.status,
                    perceptionMode = settingsState.perceptionMode,
                    onPerceptionModeChange = settingsState::updatePerceptionMode,
                    debugMode = settingsState.debugMode,
                    onDebugModeChange = settingsState::updateDebugMode,
                    traceEnabled = settingsState.traceEnabled,
                    onTraceEnabledChange = settingsState::updateTraceEnabled,
                    browserScriptEnabled = settingsState.browserScriptEnabled,
                    onBrowserScriptEnabledChange = settingsState::updateBrowserScriptEnabled,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    onAccessibilityClick = onAccessibilityClick,
                    onOverlayClick = onOverlayClick,
                    platformMode = settingsState.platformMode,
                    effectivePlatformMode = effectivePlatformMode,
                    onPlatformModeChange = settingsState::updatePlatformMode,
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = onStartOAuth,
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = onSignOut,
                    onDismiss = dismissSettings,
                    initialPage = when (pendingDeepLink?.page) {
                        DeepLinkPage.LLM_AUTH -> SettingsPage.LLM_AUTH
                        DeepLinkPage.HOME, null -> SettingsPage.HOME
                    },
                    initialAuthTab = pendingDeepLink?.authTab,
                    initialProvider = pendingDeepLink?.provider,
                    otherBaseUrl = settingsState.otherBaseUrl,
                    otherModelId = settingsState.otherModelId,
                    onOtherBaseUrlChange = settingsState::updateOtherBaseUrl,
                    onOtherModelIdChange = settingsState::updateOtherModelId,
                    appClassifier = appClassifier,
                    isSessionRunning = isSessionRunning,
                    memoryStore = memoryStore,
                    memoryEditGate = memoryEditGate,
                )
            }
        }
    }
}

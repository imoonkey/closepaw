package ai.closepaw.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.onboarding.PermissionStateMonitor.PermissionRepairModel
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.CapsuleBinding
import ai.closepaw.ui.capsule.InertCapsuleBinding
import ai.closepaw.ui.chat.ChatScreen
import ai.closepaw.ui.chat.ChatViewModel
import ai.closepaw.ui.chat.SettingsDeepLink
import ai.closepaw.ui.chat.SettingsPage as DeepLinkPage
import ai.closepaw.ui.onboarding.PermissionRepairCard
import ai.closepaw.ui.settings.OpenAiAuthUiState
import ai.closepaw.ui.settings.SettingsPage
import ai.closepaw.ui.settings.SettingsSheet
import ai.closepaw.ui.theme.ChatTheme
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
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityContent(
    viewModel: ChatViewModel,
    settingsState: AppSettingsState,
    modelLoadingStatusHolder: ModelLoadingStatusHolder,
    modelCatalog: ModelCatalog,
    appVersion: String,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    onSessionSelect: (ai.closepaw.history.model.SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onOpenViewer: () -> Unit,
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
) {
    ChatTheme {
        val sessions by viewModel.sessions.collectAsStateWithLifecycle()
        val effectivePlatformMode by effectivePlatformModeFlow.collectAsStateWithLifecycle()

        // Deep-link target captured when a banner/tap wants Settings opened at a
        // specific tab. Forwarded into SettingsSheet via initialPage/initialAuthTab.
        // Seeded from [initialSettingsDeepLink] (set by host pre-flight checks like
        // missing-credential validation) so auto-opened sheets land on the right page.
        var pendingDeepLink by remember(initialSettingsDeepLink) {
            mutableStateOf<SettingsDeepLink?>(initialSettingsDeepLink)
        }

        Column {
            if (repairModel != null) {
                PermissionRepairCard(
                    model = repairModel,
                    onFixAccessibility = onAccessibilityClick,
                    onFixOverlay = onOverlayClick,
                    onFixBattery = onFixBattery
                )
            }

            ChatScreen(
                viewModel = viewModel,
                capsuleBinding = rememberCapsuleBinding(),
                sessions = sessions,
                currentModel = settingsState.selectedModel,
                appVersion = appVersion,
                onOpenSettings = { deepLink ->
                    pendingDeepLink = deepLink
                    onShowSettingsChange(true)
                },
                onSessionSelect = onSessionSelect,
                onNewSession = onNewSession,
                onDeleteSession = { session -> viewModel.deleteSession(session) },
                onLoadSessions = { viewModel.loadSessions() },
                onOpenViewer = onOpenViewer
            )
        }

        if (showSettings) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    onShowSettingsChange(false)
                    pendingDeepLink = null
                },
                sheetState = sheetState,
                dragHandle = {}
            ) {
                SettingsSheet(
                    llmBackend = settingsState.llmBackend,
                    onBackendChange = modelLoadingStatusHolder::updateBackend,
                    selectedModel = settingsState.selectedModel,
                    onModelChange = settingsState::updateModel,
                    modelCatalog = modelCatalog,
                    selectedExecutorModel = settingsState.executorModel,
                    onExecutorModelChange = settingsState::updateExecutorModel,
                    selectedLocalModel = settingsState.localModel.id,
                    onLocalModelChange = modelLoadingStatusHolder::updateLocalModel,
                    modelLoadingStatus = modelLoadingStatusHolder.status,
                    maxTurns = settingsState.maxTurns,
                    onMaxTurnsChange = settingsState::updateMaxTurns,
                    agentMode = settingsState.agentMode,
                    onAgentModeChange = settingsState::updateAgentMode,
                    perceptionMode = settingsState.perceptionMode,
                    onPerceptionModeChange = settingsState::updatePerceptionMode,
                    debugMode = settingsState.debugMode,
                    onDebugModeChange = settingsState::updateDebugMode,
                    traceEnabled = settingsState.traceEnabled,
                    onTraceEnabledChange = settingsState::updateTraceEnabled,
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
                    onDismiss = {
                        onShowSettingsChange(false)
                        pendingDeepLink = null
                    },
                    initialPage = when (pendingDeepLink?.page) {
                        DeepLinkPage.LLM_AUTH -> SettingsPage.LLM_AUTH
                        DeepLinkPage.HOME, null -> SettingsPage.HOME
                    },
                    initialAuthTab = pendingDeepLink?.authTab,
                )
            }
        }
    }
}

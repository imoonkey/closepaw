package com.moonkey.androidagent.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.onboarding.PermissionStateMonitor.PermissionRepairModel
import com.moonkey.androidagent.ui.chat.ChatScreen
import com.moonkey.androidagent.ui.chat.ChatViewModel
import com.moonkey.androidagent.ui.onboarding.PermissionRepairCard
import com.moonkey.androidagent.ui.settings.SettingsSheet
import com.moonkey.androidagent.ui.settings.catalogModelOptions
import com.moonkey.androidagent.ui.theme.ChatTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivityContent(
    viewModel: ChatViewModel,
    settingsState: AppSettingsState,
    modelCatalog: ModelCatalog,
    appVersion: String,
    showSettings: Boolean,
    onShowSettingsChange: (Boolean) -> Unit,
    onSessionSelect: (com.moonkey.androidagent.history.model.SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onOpenViewer: () -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    repairModel: PermissionRepairModel? = null,
    onFixBattery: () -> Unit = {}
) {
    ChatTheme {
        val sessions by viewModel.sessions.collectAsStateWithLifecycle()

        Column {
            // Repair card for revoked permissions
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
                sessions = sessions,
                currentModel = settingsState.selectedModel,
                appVersion = appVersion,
                onOpenSettings = { onShowSettingsChange(true) },
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
                onDismissRequest = { onShowSettingsChange(false) },
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
                    openAiApiKey = settingsState.apiKey,
                    onOpenAiApiKeyChange = settingsState::updateApiKey,
                    openRouterApiKey = settingsState.openRouterApiKey,
                    onOpenRouterApiKeyChange = settingsState::updateOpenRouterApiKey,
                    novitaApiKey = settingsState.novitaApiKey,
                    onNovitaApiKeyChange = settingsState::updateNovitaApiKey,
                    maxTurns = settingsState.maxTurns,
                    onMaxTurnsChange = settingsState::updateMaxTurns,
                    agentMode = settingsState.agentMode,
                    onAgentModeChange = settingsState::updateAgentMode,
                    perceptionMode = settingsState.perceptionMode,
                    onPerceptionModeChange = settingsState::updatePerceptionMode,
                    debugMode = settingsState.debugMode,
                    onDebugModeChange = settingsState::updateDebugMode,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    onAccessibilityClick = onAccessibilityClick,
                    onOverlayClick = onOverlayClick,
                    onDismiss = { onShowSettingsChange(false) }
                )
            }
        }
    }
}

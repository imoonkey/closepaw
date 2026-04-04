package com.moonkey.androidagent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moonkey.androidagent.llm.ModelCatalog
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType

enum class SettingsPage {
    HOME,
    LLM_AUTH,
    AGENT_BEHAVIOR,
    PERMISSIONS_ADVANCED,
}

@Composable
fun SettingsSheet(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    authMethod: String?,
    onAuthMethodChange: (String?) -> Unit,
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    novitaApiKey: String,
    onNovitaApiKeyChange: (String) -> Unit,
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .displayCutoutPadding()
            .navigationBarsPadding()
    ) {
        AnimatedContent(
            targetState = settingsPage,
            transitionSpec = {
                if (targetState == SettingsPage.HOME) {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                } else {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                }
            },
            label = "SettingsPageTransition"
        ) { page ->
            when (page) {
                SettingsPage.HOME -> SettingsHomePage(
                    llmBackend = llmBackend,
                    selectedModel = selectedModel,
                    modelOptions = catalogModelOptions(modelCatalog.all()),
                    selectedLocalModel = selectedLocalModel,
                    agentMode = agentMode,
                    maxTurns = maxTurns,
                    perceptionMode = perceptionMode,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    debugMode = debugMode,
                    onNavigate = { settingsPage = it },
                    onDismiss = onDismiss
                )
                SettingsPage.LLM_AUTH -> LlmAuthSettingsPage(
                    llmBackend = llmBackend,
                    onBackendChange = onBackendChange,
                    authMethod = authMethod,
                    onAuthMethodChange = onAuthMethodChange,
                    selectedModel = selectedModel,
                    onModelChange = onModelChange,
                    modelCatalog = modelCatalog,
                    selectedExecutorModel = selectedExecutorModel,
                    onExecutorModelChange = onExecutorModelChange,
                    agentMode = agentMode,
                    selectedLocalModel = selectedLocalModel,
                    onLocalModelChange = onLocalModelChange,
                    modelLoadingStatus = modelLoadingStatus,
                    openAiApiKey = openAiApiKey,
                    onOpenAiApiKeyChange = onOpenAiApiKeyChange,
                    openRouterApiKey = openRouterApiKey,
                    onOpenRouterApiKeyChange = onOpenRouterApiKeyChange,
                    novitaApiKey = novitaApiKey,
                    onNovitaApiKeyChange = onNovitaApiKeyChange,
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = onStartOAuth,
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = onSignOut,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss
                )
                SettingsPage.AGENT_BEHAVIOR -> AgentBehaviorSettingsPage(
                    maxTurns = maxTurns,
                    onMaxTurnsChange = onMaxTurnsChange,
                    agentMode = agentMode,
                    onAgentModeChange = onAgentModeChange,
                    perceptionMode = perceptionMode,
                    onPerceptionModeChange = onPerceptionModeChange,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss
                )
                SettingsPage.PERMISSIONS_ADVANCED -> PermissionsAdvancedSettingsPage(
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    onAccessibilityClick = onAccessibilityClick,
                    onOverlayClick = onOverlayClick,
                    debugMode = debugMode,
                    onDebugModeChange = onDebugModeChange,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss
                )
            }
        }
    }
}


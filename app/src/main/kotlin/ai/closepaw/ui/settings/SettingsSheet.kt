package ai.closepaw.ui.settings

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType

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
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.HOME,
    initialAuthTab: AuthMode? = null,
) {
    var settingsPage by rememberSaveable(initialPage) { mutableStateOf(initialPage) }

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
                    modelCatalog = modelCatalog,
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
                    selectedModel = selectedModel,
                    onModelChange = onModelChange,
                    modelCatalog = modelCatalog,
                    selectedExecutorModel = selectedExecutorModel,
                    onExecutorModelChange = onExecutorModelChange,
                    agentMode = agentMode,
                    selectedLocalModel = selectedLocalModel,
                    onLocalModelChange = onLocalModelChange,
                    modelLoadingStatus = modelLoadingStatus,
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = onStartOAuth,
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = onSignOut,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss,
                    initialAuthTab = initialAuthTab,
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
                    traceEnabled = traceEnabled,
                    onTraceEnabledChange = onTraceEnabledChange,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss
                )
            }
        }
    }
}


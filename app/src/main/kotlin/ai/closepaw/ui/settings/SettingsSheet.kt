package ai.closepaw.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ai.closepaw.app.MemoryEditGate
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.AppClassifierHolder
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.paperGrain

enum class SettingsPage {
    HOME,
    LLM_AUTH,
    AGENT_BEHAVIOR,
    MEMORY,
    PERMISSIONS_ADVANCED,
    APP_ACCESS,
    OPEN_SOURCE_LICENSES,
}

@Composable
fun SettingsSheet(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit,
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    onPlatformModeChange: (PlatformMode) -> Unit,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.HOME,
    initialAuthTab: AuthMode? = null,
    initialProvider: LLMProvider? = null,
    otherBaseUrl: String = "",
    otherModelId: String = "",
    onOtherBaseUrlChange: (String) -> Unit = {},
    onOtherModelIdChange: (String) -> Unit = {},
    appClassifier: AppClassifier = AppClassifierHolder.get(LocalContext.current.applicationContext),
    isSessionRunning: Boolean = false,
    memoryStore: MemoryStore,
    memoryEditGate: MemoryEditGate,
) {
    var settingsPage by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    val reducedMotion = ClosePawMotion.reducedMotion()

    BackHandler {
        if (settingsPage != SettingsPage.HOME) settingsPage = SettingsPage.HOME
        else onDismiss()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .paperGrain()
            .statusBarsPadding()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        AnimatedContent(
            targetState = settingsPage,
            transitionSpec = {
                if (reducedMotion) {
                    // D1 §8: page slide collapses to a 120ms fade under reduced motion.
                    val fade = tween<Float>(durationMillis = ClosePawMotion.Quick)
                    fadeIn(fade) togetherWith fadeOut(fade)
                } else {
                    // D1 §5: 240ms page slide on EaseOutCubic. Sourced from ClosePawMotion
                    // so settings shares the same page-transition cadence as the rest of the app.
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(
                        durationMillis = ClosePawMotion.PageSlide,
                        easing = ClosePawMotion.EaseOutCubic,
                    )
                    if (targetState == SettingsPage.HOME) {
                        slideInHorizontally(spec) { -it } togetherWith slideOutHorizontally(spec) { it }
                    } else {
                        slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it }
                    }
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
                    perceptionMode = perceptionMode,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isOverlayEnabled = isOverlayEnabled,
                    debugMode = debugMode,
                    platformMode = platformMode,
                    effectivePlatformMode = effectivePlatformMode,
                    appClassifier = appClassifier,
                    onNavigate = { settingsPage = it },
                    onDismiss = onDismiss
                )
                SettingsPage.LLM_AUTH -> LlmAuthSettingsPage(
                    llmBackend = llmBackend,
                    onBackendChange = onBackendChange,
                    selectedModel = selectedModel,
                    onModelChange = onModelChange,
                    modelCatalog = modelCatalog,
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
                    initialProvider = initialProvider,
                    otherBaseUrl = otherBaseUrl,
                    otherModelId = otherModelId,
                    onOtherBaseUrlChange = onOtherBaseUrlChange,
                    onOtherModelIdChange = onOtherModelIdChange,
                )
                SettingsPage.AGENT_BEHAVIOR -> AgentBehaviorSettingsPage(
                    perceptionMode = perceptionMode,
                    onPerceptionModeChange = onPerceptionModeChange,
                    platformMode = platformMode,
                    effectivePlatformMode = effectivePlatformMode,
                    onPlatformModeChange = onPlatformModeChange,
                    browserScriptEnabled = browserScriptEnabled,
                    onBrowserScriptEnabledChange = onBrowserScriptEnabledChange,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss,
                    isSessionRunning = isSessionRunning,
                )
                SettingsPage.MEMORY -> MemorySettingsPage(
                    memoryStore = memoryStore,
                    gate = memoryEditGate,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss,
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
                SettingsPage.APP_ACCESS -> AppAccessSettingsPage(
                    appClassifier = appClassifier,
                    memoryStore = memoryStore,
                    gate = memoryEditGate,
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss,
                )
                SettingsPage.OPEN_SOURCE_LICENSES -> OpenSourceLicensesPage(
                    onBack = { settingsPage = SettingsPage.HOME },
                    onClose = onDismiss,
                )
            }
        }
    }
}

package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.closepaw.llm.ApiType
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.displayLabel
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType

enum class LlmAuthTab { SIGN_IN, API_KEY, LOCAL }

private val LlmAuthTab.label: String
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> "Sign In"
        LlmAuthTab.API_KEY -> "API Key"
        LlmAuthTab.LOCAL -> "Local"
    }

/** Providers available in the API Key tab sub-selector. */
private val API_KEY_PROVIDERS = listOf(LLMProvider.OPENAI, LLMProvider.OPENROUTER, LLMProvider.NOVITA)

@Composable
internal fun LlmAuthSettingsPage(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    authMethod: String?,
    onAuthMethodChange: (String?) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit,
    agentMode: AgentMode,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    novitaApiKey: String,
    onNovitaApiKeyChange: (String) -> Unit,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    // Re-derive from external state when authMethod/llmBackend change;
    // survive rotation via rememberSaveable when they stay the same.
    var selectedTab by rememberSaveable(authMethod, llmBackend) {
        mutableStateOf(
            when {
                authMethod == "oauth" -> LlmAuthTab.SIGN_IN
                llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
                else -> LlmAuthTab.API_KEY
            }
        )
    }

    // Commit backend/auth state before delegating to the original callback.
    // Called lazily on real user actions inside tab content, NOT on tab tap.
    fun commitSignIn(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        onAuthMethodChange("oauth")
        canonicalizeModels(
            modelCatalog = modelCatalog,
            provider = LLMProvider.OPENAI,
            api = ApiType.RESPONSE,
            selectedModel = selectedModel,
            onModelChange = onModelChange,
            selectedExecutorModel = selectedExecutorModel,
            onExecutorModelChange = onExecutorModelChange
        )
        action()
    }

    fun commitApiKey(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        onAuthMethodChange(null)
        action()
    }

    fun commitLocal(action: () -> Unit) {
        onBackendChange(LLMBackendType.LOCAL)
        onAuthMethodChange(null)
        action()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSubPageHeader(title = "LLM & Authentication", onBack = onBack, onClose = onClose)

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            LlmAuthTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp)
        ) {
            when (selectedTab) {
                LlmAuthTab.SIGN_IN -> SignInTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitSignIn { onModelChange(it) } },
                    modelCatalog = modelCatalog,
                    selectedExecutorModel = selectedExecutorModel,
                    onExecutorModelChange = { commitSignIn { onExecutorModelChange(it) } },
                    agentMode = agentMode,
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = { commitSignIn { onStartOAuth() } },
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = onSignOut
                )
                LlmAuthTab.API_KEY -> ApiKeyTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitApiKey { onModelChange(it) } },
                    modelCatalog = modelCatalog,
                    selectedExecutorModel = selectedExecutorModel,
                    onExecutorModelChange = { commitApiKey { onExecutorModelChange(it) } },
                    agentMode = agentMode,
                    openAiApiKey = openAiApiKey,
                    onOpenAiApiKeyChange = { commitApiKey { onOpenAiApiKeyChange(it) } },
                    openRouterApiKey = openRouterApiKey,
                    onOpenRouterApiKeyChange = { commitApiKey { onOpenRouterApiKeyChange(it) } },
                    novitaApiKey = novitaApiKey,
                    onNovitaApiKeyChange = { commitApiKey { onNovitaApiKeyChange(it) } }
                )
                LlmAuthTab.LOCAL -> LocalTabContent(
                    selectedLocalModel = selectedLocalModel,
                    onLocalModelChange = { commitLocal { onLocalModelChange(it) } },
                    modelLoadingStatus = modelLoadingStatus
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SignInTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit,
    agentMode: AgentMode,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    // Sign In tab: OpenAI RESPONSE models only
    val modelOptions = catalogModelOptions(
        modelCatalog.modelsFor(LLMProvider.OPENAI, ApiType.RESPONSE)
    )

    SettingsSection(title = "Cloud Model") {
        CloudModelDropdown(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            onModelChange = onModelChange
        )
    }
    if (agentMode == AgentMode.PRO) {
        Spacer(modifier = Modifier.height(20.dp))
        SettingsSection(title = "Executor Model") {
            Box(modifier = Modifier.testTag("qa-executor-model-dropdown")) {
                ExecutorModelDropdown(
                    selectedModel = selectedExecutorModel,
                    modelOptions = modelOptions,
                    onModelChange = onExecutorModelChange
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    SettingsSection(title = "Authentication") {
        OpenAiAuthCard(
            state = openAiAuthUiState,
            onStartOAuth = onStartOAuth,
            onCancelOAuth = onCancelOAuth,
            onSignOut = onSignOut
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit,
    agentMode: AgentMode,
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    novitaApiKey: String,
    onNovitaApiKeyChange: (String) -> Unit
) {
    // Re-derive from external model when it changes; survive rotation otherwise.
    var selectedProvider by rememberSaveable(selectedModel) {
        val resolved = modelCatalog.resolveOrNull(selectedModel)?.provider ?: LLMProvider.OPENAI
        mutableStateOf(resolved)
    }

    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(selectedProvider))

    // Provider sub-selector — local-only, no committed mutations.
    SettingsSection(title = "Provider") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            API_KEY_PROVIDERS.forEachIndexed { index, provider ->
                SegmentedButton(
                    selected = selectedProvider == provider,
                    onClick = {
                        if (selectedProvider != provider) {
                            selectedProvider = provider
                            canonicalizeModels(
                                modelCatalog = modelCatalog,
                                provider = provider,
                                api = null,
                                selectedModel = selectedModel,
                                onModelChange = onModelChange,
                                selectedExecutorModel = selectedExecutorModel,
                                onExecutorModelChange = onExecutorModelChange
                            )
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = API_KEY_PROVIDERS.size
                    )
                ) {
                    Text(provider.displayLabel)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    SettingsSection(title = "Cloud Model") {
        CloudModelDropdown(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            onModelChange = onModelChange
        )
    }
    if (agentMode == AgentMode.PRO) {
        Spacer(modifier = Modifier.height(20.dp))
        SettingsSection(title = "Executor Model") {
            Box(modifier = Modifier.testTag("qa-executor-model-dropdown")) {
                ExecutorModelDropdown(
                    selectedModel = selectedExecutorModel,
                    modelOptions = modelOptions,
                    onModelChange = onExecutorModelChange
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Provider-linked API key field
    SettingsSection(title = "API Key") {
        when (selectedProvider) {
            LLMProvider.OPENAI -> ApiKeyField(
                label = "OpenAI Key",
                value = openAiApiKey,
                onValueChange = onOpenAiApiKeyChange
            )
            LLMProvider.OPENROUTER -> ApiKeyField(
                label = "OpenRouter Key",
                value = openRouterApiKey,
                onValueChange = onOpenRouterApiKeyChange
            )
            LLMProvider.NOVITA -> ApiKeyField(
                label = "Novita Key",
                value = novitaApiKey,
                onValueChange = onNovitaApiKeyChange
            )
        }
    }
}

@Composable
private fun LocalTabContent(
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus
) {
    SettingsSection(title = "Local Model") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalModelDropdown(
                selectedModelId = selectedLocalModel,
                onModelChange = onLocalModelChange
            )
            ModelLoadingStatusIndicator(status = modelLoadingStatus)
        }
    }
}

/**
 * Canonicalize main/executor model when provider or auth context changes.
 * If the current model is invalid for the new context, replace with preferred.
 * If executor model is invalid, reset to null.
 */
private fun canonicalizeModels(
    modelCatalog: ModelCatalog,
    provider: LLMProvider,
    api: ApiType?,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit
) {
    val validModels = modelCatalog.modelsFor(provider, api)
    val validNames = validModels.map { it.name }.toSet()

    if (selectedModel !in validNames) {
        val preferred = validModels.firstOrNull()
        if (preferred != null) onModelChange(preferred.name)
    }

    if (selectedExecutorModel != null && selectedExecutorModel !in validNames) {
        onExecutorModelChange(null)
    }
}

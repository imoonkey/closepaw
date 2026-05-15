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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.ApiType
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.displayLabel
import ai.closepaw.protocol.LLMBackendType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

enum class LlmAuthTab { SIGN_IN, API_KEY, LOCAL }

private val LlmAuthTab.label: String
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> "Sign In"
        LlmAuthTab.API_KEY -> "API Key"
        LlmAuthTab.LOCAL -> "Local"
    }

private val LlmAuthTab.mode: AuthMode
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> AuthMode.OAuth
        LlmAuthTab.API_KEY -> AuthMode.ApiKey
        LlmAuthTab.LOCAL -> AuthMode.Local
    }

private fun AuthMode.toTab(): LlmAuthTab = when (this) {
    AuthMode.OAuth -> LlmAuthTab.SIGN_IN
    AuthMode.ApiKey -> LlmAuthTab.API_KEY
    AuthMode.Local -> LlmAuthTab.LOCAL
}

/** Default provider per tab when the current selected model's mode doesn't match the tab. */
private val LlmAuthTab.defaultProvider: LLMProvider
    get() = when (this) {
        LlmAuthTab.SIGN_IN -> LLMProvider.OPENAI_CODEX
        LlmAuthTab.API_KEY -> LLMProvider.OPENAI_API
        LlmAuthTab.LOCAL -> LLMProvider.LOCAL_LFM
    }

/** Providers available in the API Key tab sub-selector. */
private val API_KEY_PROVIDERS = listOf(LLMProvider.OPENAI_API, LLMProvider.OPENROUTER, LLMProvider.NOVITA)

@Composable
internal fun LlmAuthSettingsPage(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedSubagentModel: String?,
    onSubagentModelChange: (String?) -> Unit,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    initialAuthTab: AuthMode? = null,
) {
    // Initial tab: explicit caller request wins; else derive from selected model's provider mode.
    val modelMode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    var selectedTab by rememberSaveable(initialAuthTab, modelMode, llmBackend) {
        mutableStateOf(
            when {
                initialAuthTab != null -> initialAuthTab.toTab()
                modelMode == AuthMode.OAuth -> LlmAuthTab.SIGN_IN
                llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
                else -> LlmAuthTab.API_KEY
            }
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authStore = remember(context) { AuthStoreHolder.get(context) }
    // Debounce + single-flight + FIFO mutex: cancel any pending write on each
    // keystroke; the mutex serializes writes that already passed the debounce
    // so the final keystroke wins even if an earlier write reached AuthStore.
    val pendingApiKeyPersist = remember { arrayOf<Job?>(null) }
    val apiKeyPersistMutex = remember { Mutex() }

    // Commit wrappers — called on real user actions inside tab content, NOT on tab tap.
    fun commitSignIn(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        val target = resolveProviderForTab(LlmAuthTab.SIGN_IN, selectedModel, modelCatalog)
        canonicalizeModels(
            modelCatalog = modelCatalog,
            provider = target,
            api = null,
            selectedModel = selectedModel,
            onModelChange = onModelChange,
            selectedSubagentModel = selectedSubagentModel,
            onSubagentModelChange = onSubagentModelChange
        )
        action()
    }

    fun commitApiKey(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        action()
    }

    fun commitLocal(action: () -> Unit) {
        onBackendChange(LLMBackendType.LOCAL)
        action()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSubPageHeader(title = "LLM & Authentication", onBack = onBack, onClose = onClose)

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            LlmAuthTab.entries.forEach { tab ->
                // Local backend isn't wired through this page yet; show the tab
                // as visibly disabled (no Claw underline, dimmed label) until
                // it is, so users don't tap a no-op affordance.
                val isEnabled = tab != LlmAuthTab.LOCAL
                Tab(
                    selected = selectedTab == tab,
                    onClick = { if (isEnabled) selectedTab = tab },
                    enabled = isEnabled,
                    text = {
                        Text(
                            text = tab.label,
                            color = if (isEnabled) androidx.compose.ui.graphics.Color.Unspecified
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
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
                    selectedSubagentModel = selectedSubagentModel,
                    onSubagentModelChange = { commitSignIn { onSubagentModelChange(it) } },
                    openAiAuthUiState = openAiAuthUiState,
                    onStartOAuth = { commitSignIn { onStartOAuth() } },
                    onCancelOAuth = onCancelOAuth,
                    onSignOut = {
                        scope.launch { authStore.clear(LLMProvider.OPENAI_CODEX) }
                        onSignOut()
                    }
                )
                LlmAuthTab.API_KEY -> ApiKeyTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitApiKey { onModelChange(it) } },
                    modelCatalog = modelCatalog,
                    selectedSubagentModel = selectedSubagentModel,
                    onSubagentModelChange = { commitApiKey { onSubagentModelChange(it) } },
                    authStore = authStore,
                    onApiKeyPersist = { provider, key ->
                        commitApiKey { }
                        launchDebouncedApiKeyPersist(
                            scope = scope,
                            authStore = authStore,
                            mutex = apiKeyPersistMutex,
                            pending = pendingApiKeyPersist,
                            provider = provider,
                            key = key,
                        )
                    }
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

/**
 * Section 5 canonicalization rule: `selectedProviderForTab` derivation.
 * - If [selectedModel]'s provider matches [tab]'s mode → use that provider.
 * - Else → [tab]'s default provider.
 */
private fun resolveProviderForTab(
    tab: LlmAuthTab,
    selectedModel: String,
    modelCatalog: ModelCatalog,
): LLMProvider {
    val modelProvider = modelCatalog.resolveOrNull(selectedModel)?.provider
    return if (modelProvider != null && modelProvider.mode == tab.mode) modelProvider
    else tab.defaultProvider
}

private const val API_KEY_PERSIST_DEBOUNCE_MS = 300L

/**
 * Debounce + single-flight + FIFO mutex for per-keystroke AuthStore writes.
 * Cancels the previous pending write; the mutex guarantees that any write that
 * already passed the debounce completes in launch order, so the final keystroke
 * always wins even though [AuthStore.set] has no internal write lock.
 */
internal fun launchDebouncedApiKeyPersist(
    scope: CoroutineScope,
    authStore: AuthStore,
    mutex: Mutex,
    pending: Array<Job?>,
    provider: LLMProvider,
    key: String,
    debounceMs: Long = API_KEY_PERSIST_DEBOUNCE_MS,
    ioContext: CoroutineContext = Dispatchers.IO,
) {
    pending[0]?.cancel()
    pending[0] = scope.launch {
        delay(debounceMs)
        mutex.withLock {
            withContext(ioContext) {
                if (key.isBlank()) authStore.clear(provider)
                else authStore.set(provider, AuthCredential.ApiKey(key))
            }
        }
    }
}

@Composable
private fun SignInTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
    selectedSubagentModel: String?,
    onSubagentModelChange: (String?) -> Unit,
    openAiAuthUiState: OpenAiAuthUiState,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onSignOut: () -> Unit
) {
    // Canonical provider for this tab: selected model if OAuth-mode, else OPENAI_CODEX.
    val provider = resolveProviderForTab(LlmAuthTab.SIGN_IN, selectedModel, modelCatalog)
    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(provider))

    SettingsSection(title = "Cloud Model") {
        CloudModelDropdown(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            onModelChange = onModelChange
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    SettingsSection(title = "Subagent Model") {
        Box(modifier = Modifier.testTag("qa-subagent-model-dropdown")) {
            SubagentModelDropdown(
                selectedModel = selectedSubagentModel,
                modelOptions = modelOptions,
                onModelChange = onSubagentModelChange
            )
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
    selectedSubagentModel: String?,
    onSubagentModelChange: (String?) -> Unit,
    authStore: AuthStore,
    onApiKeyPersist: (LLMProvider, String) -> Unit,
) {
    // Canonical provider rule — re-derives whenever the external model changes.
    val derivedProvider = resolveProviderForTab(LlmAuthTab.API_KEY, selectedModel, modelCatalog)
    var selectedProvider by rememberSaveable(derivedProvider) { mutableStateOf(derivedProvider) }

    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(selectedProvider))

    // Per-provider API key text, seeded from AuthStore. Process-transient for typing;
    // blur/change invokes onApiKeyPersist to write through to AuthStore.
    var apiKeyText by remember(selectedProvider) { mutableStateOf("") }
    LaunchedEffect(selectedProvider) {
        val cred = authStore.get(selectedProvider)
        apiKeyText = (cred as? AuthCredential.ApiKey)?.key.orEmpty()
    }

    // Provider sub-selector — local view state only, no settings writes on click.
    SettingsSection(title = "Provider") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            API_KEY_PROVIDERS.forEachIndexed { index, provider ->
                SegmentedButton(
                    selected = selectedProvider == provider,
                    onClick = { selectedProvider = provider },
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
            onModelChange = { picked ->
                // Commit canonicalizes main + subagent if provider changed.
                onModelChange(picked)
                canonicalizeSubagentOnProviderChange(
                    modelCatalog = modelCatalog,
                    newMainModel = picked,
                    selectedSubagentModel = selectedSubagentModel,
                    onSubagentModelChange = onSubagentModelChange
                )
            }
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    SettingsSection(title = "Subagent Model") {
        Box(modifier = Modifier.testTag("qa-subagent-model-dropdown")) {
            SubagentModelDropdown(
                selectedModel = selectedSubagentModel,
                modelOptions = modelOptions,
                onModelChange = onSubagentModelChange
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Provider-linked API key field — value backed by AuthStore.
    SettingsSection(title = "API Key") {
        val label = when (selectedProvider) {
            LLMProvider.OPENAI_API -> "OpenAI Key"
            LLMProvider.OPENROUTER -> "OpenRouter Key"
            LLMProvider.NOVITA -> "Novita Key"
            LLMProvider.OPENAI_CODEX, LLMProvider.LOCAL_LFM -> null
        }
        if (label != null) {
            ApiKeyField(
                label = label,
                value = apiKeyText,
                onValueChange = { key ->
                    apiKeyText = key
                    onApiKeyPersist(selectedProvider, key)
                }
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
 * Canonicalize main/subagent model when provider or auth context changes.
 * If the current main model is invalid for the new context, replace with preferred.
 * If subagent's provider doesn't match new main's provider, reset to provider default
 * (or null if the provider has no entry).
 */
private fun canonicalizeModels(
    modelCatalog: ModelCatalog,
    provider: LLMProvider,
    api: ApiType?,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    selectedSubagentModel: String?,
    onSubagentModelChange: (String?) -> Unit
) {
    val validModels = modelCatalog.modelsFor(provider, api)
    val validNames = validModels.map { it.name }.toSet()
    val currentProvider = modelCatalog.resolveOrNull(selectedModel)?.provider

    if (selectedModel !in validNames) {
        val preferred = validModels.firstOrNull()
        if (preferred != null) onModelChange(preferred.name)
    }

    // Subagent canonicalization: provider-change triggers reset.
    if (currentProvider != provider) {
        val subagentProvider = selectedSubagentModel?.let { modelCatalog.resolveOrNull(it)?.provider }
        if (subagentProvider != provider) {
            val subagentDefault = modelCatalog.preferredModelFor(provider)?.name
            onSubagentModelChange(subagentDefault)
        }
    }
}

/**
 * On a main-model commit inside a tab that already matches the selected provider
 * (e.g. user picks a different model in API Key tab), ensure subagent still matches.
 */
private fun canonicalizeSubagentOnProviderChange(
    modelCatalog: ModelCatalog,
    newMainModel: String,
    selectedSubagentModel: String?,
    onSubagentModelChange: (String?) -> Unit,
) {
    val newProvider = modelCatalog.resolveOrNull(newMainModel)?.provider ?: return
    val subagentProvider = selectedSubagentModel?.let { modelCatalog.resolveOrNull(it)?.provider }
    if (subagentProvider != null && subagentProvider != newProvider) {
        onSubagentModelChange(modelCatalog.preferredModelFor(newProvider)?.name)
    }
}

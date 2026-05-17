package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.ApiType
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.OtherBaseUrlValidator
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

/**
 * Surface the Local tab in LLM & Authentication settings.
 *
 * Off because LFM 1.2B Q4 on a phone CPU takes 1-3 min to emit the first tool
 * call with the current 12-tool agent schema — it works, but it's unusable.
 * Flip to true to re-expose once we have a smaller agent-capable model or a
 * chat-only path. The rest of the local stack (LFMLLMClient, auto-download,
 * LocalTabContent) stays wired so flipping this is a one-line change.
 */
private const val LOCAL_TAB_ENABLED = false

private val VISIBLE_TABS: List<LlmAuthTab> =
    LlmAuthTab.entries.filter { LOCAL_TAB_ENABLED || it != LlmAuthTab.LOCAL }

private fun LlmAuthTab.visibleOrFallback(): LlmAuthTab =
    if (this in VISIBLE_TABS) this else LlmAuthTab.API_KEY

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
private val API_KEY_PROVIDERS = listOf(LLMProvider.OPENAI_API, LLMProvider.OPENROUTER, LLMProvider.OTHER)

@Composable
internal fun LlmAuthSettingsPage(
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
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
    initialProvider: LLMProvider? = null,
    otherBaseUrl: String = "",
    otherModelId: String = "",
    onOtherBaseUrlChange: (String) -> Unit = {},
    onOtherModelIdChange: (String) -> Unit = {},
) {
    // Initial tab: explicit caller request wins; else derive from selected model's provider mode.
    // When the Local tab is hidden, any LOCAL landing target falls back to API_KEY.
    val modelMode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    var selectedTab by rememberSaveable(initialAuthTab, modelMode, llmBackend) {
        val raw = when {
            initialAuthTab != null -> initialAuthTab.toTab()
            modelMode == AuthMode.OAuth -> LlmAuthTab.SIGN_IN
            llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
            else -> LlmAuthTab.API_KEY
        }
        mutableStateOf(if (raw == LlmAuthTab.LOCAL && !LOCAL_TAB_ENABLED) LlmAuthTab.API_KEY else raw)
    }
    val activeTab = selectedTab.visibleOrFallback()
    LaunchedEffect(activeTab, selectedTab) {
        if (activeTab != selectedTab) selectedTab = activeTab
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authStore = remember(context) { AuthStoreHolder.get(context) }
    // Debounce + single-flight + FIFO mutex: cancel any pending write on each
    // keystroke; the mutex serializes writes that already passed the debounce
    // so the final keystroke wins even if an earlier write reached AuthStore.
    val pendingApiKeyPersist = remember { arrayOf<Job?>(null) }
    val apiKeyPersistMutex = remember { Mutex() }
    val pendingOtherBaseUrlPersist = remember { arrayOf<Job?>(null) }
    val pendingOtherModelIdPersist = remember { arrayOf<Job?>(null) }

    // Commit wrappers — called on real user actions inside tab content, NOT on tab tap.
    fun commitSignIn(action: () -> Unit) {
        onBackendChange(LLMBackendType.OPENAI)
        val target = resolveProviderForTab(LlmAuthTab.SIGN_IN, selectedModel, modelCatalog)
        canonicalizeMainModel(
            modelCatalog = modelCatalog,
            provider = target,
            api = null,
            selectedModel = selectedModel,
            onModelChange = onModelChange
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

        TabRow(selectedTabIndex = VISIBLE_TABS.indexOf(activeTab).coerceAtLeast(0)) {
            VISIBLE_TABS.forEach { tab ->
                Tab(
                    selected = activeTab == tab,
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
            when (activeTab) {
                LlmAuthTab.SIGN_IN -> SignInTabContent(
                    selectedModel = selectedModel,
                    onModelChange = { commitSignIn { onModelChange(it) } },
                    modelCatalog = modelCatalog,
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
                    authStore = authStore,
                    initialProvider = initialProvider,
                    otherBaseUrl = otherBaseUrl,
                    otherModelId = otherModelId,
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
                    },
                    onOtherBaseUrlPersist = { url ->
                        launchDebouncedPersist(scope, pendingOtherBaseUrlPersist) {
                            onOtherBaseUrlChange(url)
                        }
                    },
                    onOtherModelIdPersist = { modelId ->
                        launchDebouncedPersist(scope, pendingOtherModelIdPersist) {
                            onOtherModelIdChange(modelId)
                        }
                    },
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

/**
 * Debounce + single-flight launcher for non-AuthStore persistence (OTHER base URL,
 * custom model id). Writes hit a SharedPreferences-backed store; they're cheap and
 * already coalesce, so the per-keystroke cancel-the-pending-write pattern is enough
 * — no mutex needed. The persist action runs on the caller's scope dispatcher
 * (Main.immediate from `rememberCoroutineScope()`), which is required for the
 * downstream Compose `mutableStateOf` writes inside [AppSettingsState].
 */
internal fun launchDebouncedPersist(
    scope: CoroutineScope,
    pending: Array<Job?>,
    debounceMs: Long = API_KEY_PERSIST_DEBOUNCE_MS,
    action: suspend () -> Unit,
) {
    pending[0]?.cancel()
    pending[0] = scope.launch {
        delay(debounceMs)
        action()
    }
}

@Composable
private fun SignInTabContent(
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,
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
    authStore: AuthStore,
    onApiKeyPersist: (LLMProvider, String) -> Unit,
    onOtherBaseUrlPersist: (String) -> Unit,
    onOtherModelIdPersist: (String) -> Unit,
    otherBaseUrl: String,
    otherModelId: String,
    initialProvider: LLMProvider? = null,
) {
    // Canonical provider rule — re-derives whenever the external model changes.
    // Explicit deep-link target wins on first composition.
    val derivedProvider = resolveProviderForTab(LlmAuthTab.API_KEY, selectedModel, modelCatalog)
    var selectedProvider by rememberSaveable(derivedProvider, initialProvider) {
        val start = initialProvider?.takeIf { it in API_KEY_PROVIDERS } ?: derivedProvider
        mutableStateOf(start)
    }

    val modelOptions = catalogModelOptions(modelCatalog.modelsFor(selectedProvider))

    // Per-provider API key text, seeded from AuthStore. Process-transient for typing;
    // blur/change invokes onApiKeyPersist to write through to AuthStore.
    var apiKeyText by remember(selectedProvider) { mutableStateOf("") }
    LaunchedEffect(selectedProvider) {
        val cred = authStore.get(selectedProvider)
        apiKeyText = (cred as? AuthCredential.ApiKey)?.key.orEmpty()
    }

    // OTHER-specific text: editable in-place; debounced through onOtherBaseUrlPersist
    // and onOtherModelIdPersist, which write to AppSettingsStore and invalidate the
    // ModelCatalogRepository (so `modelCatalog` recomposes with the synth entry).
    var otherBaseUrlText by remember(otherBaseUrl) { mutableStateOf(otherBaseUrl) }
    var otherModelIdText by remember(otherModelId) { mutableStateOf(otherModelId) }

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

    if (selectedProvider == LLMProvider.OTHER) {
        SettingsSection(title = "Base URL") {
            OtherBaseUrlField(
                value = otherBaseUrlText,
                onValueChange = { value ->
                    otherBaseUrlText = value
                    onOtherBaseUrlPersist(value)
                },
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSection(title = "Custom Model Id") {
            OtherModelIdField(
                value = otherModelIdText,
                onValueChange = { value ->
                    otherModelIdText = value
                    onOtherModelIdPersist(value)
                },
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    SettingsSection(title = "Cloud Model") {
        CloudModelDropdown(
            selectedModel = selectedModel,
            modelOptions = modelOptions,
            onModelChange = onModelChange
        )
    }
    Spacer(modifier = Modifier.height(20.dp))

    // Provider-linked API key field — value backed by AuthStore.
    SettingsSection(title = "API Key") {
        val label = when (selectedProvider) {
            LLMProvider.OPENAI_API -> "OpenAI Key"
            LLMProvider.OPENROUTER -> "OpenRouter Key"
            LLMProvider.OTHER -> "API Key"
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

    // Auto-flip rule: when the user is in the OTHER sub-tab and all three fields
    // validate, flip selectedModel to "other-custom". Logic lives in
    // [shouldAutoFlipToOtherCustom] so it's exercised by a plain JVM unit test —
    // the @Composable wiring here is a thin recomputation harness.
    LaunchedEffect(
        selectedProvider,
        otherBaseUrlText,
        otherModelIdText,
        apiKeyText,
        modelCatalog,
        selectedModel,
    ) {
        if (shouldAutoFlipToOtherCustom(
                selectedProvider = selectedProvider,
                apiKeyText = apiKeyText,
                otherBaseUrlText = otherBaseUrlText,
                otherModelIdText = otherModelIdText,
                modelCatalog = modelCatalog,
                selectedModel = selectedModel,
            )
        ) {
            onModelChange(ModelCatalogRepository.OTHER_CUSTOM_NAME)
        }
    }
}

/**
 * Decide whether the OTHER auto-flip should fire right now. Pure function; exposed
 * `internal` so the JVM regression test can pin the stale-catalog race fix
 * (Sub 1c Codex review HIGH #1) without spinning up Compose.
 *
 * Gates the flip on the catalog entry MATCHING the current normalized UI values
 * — a stale `other-custom` row from a previous valid config would otherwise let
 * a mid-edit launch hit the old endpoint with the new key.
 */
internal fun shouldAutoFlipToOtherCustom(
    selectedProvider: LLMProvider,
    apiKeyText: String,
    otherBaseUrlText: String,
    otherModelIdText: String,
    modelCatalog: ModelCatalog,
    selectedModel: String,
): Boolean {
    if (selectedProvider != LLMProvider.OTHER) return false
    if (apiKeyText.isBlank()) return false
    if (otherBaseUrlText.isBlank() || otherModelIdText.isBlank()) return false
    val normalizedUrl = OtherBaseUrlValidator.validate(otherBaseUrlText).getOrNull() ?: return false
    val trimmedModelId = otherModelIdText.trim()
    val entry = modelCatalog.resolveOrNull(ModelCatalogRepository.OTHER_CUSTOM_NAME) ?: return false
    if (entry.baseUrl != normalizedUrl || entry.modelId != trimmedModelId) return false
    if (selectedModel == ModelCatalogRepository.OTHER_CUSTOM_NAME) return false
    return true
}

@Composable
private fun LocalTabContent(
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = "Experimental: local models are slow and underpowered, " +
                "and will not reliably drive the agent. Use a cloud model for real work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
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
 * Replace the current main model with the preferred one for [provider] if it isn't
 * valid for the new context.
 */
private fun canonicalizeMainModel(
    modelCatalog: ModelCatalog,
    provider: LLMProvider,
    api: ApiType?,
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    val validModels = modelCatalog.modelsFor(provider, api)
    val validNames = validModels.map { it.name }.toSet()
    if (selectedModel !in validNames) {
        val preferred = validModels.firstOrNull()
        if (preferred != null) onModelChange(preferred.name)
    }
}

@Composable
private fun OtherBaseUrlField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Base URL") },
        placeholder = {
            Text(
                "https://api.example.com/v1",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun OtherModelIdField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Custom Model Id") },
        placeholder = {
            Text(
                "vendor/model-id",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

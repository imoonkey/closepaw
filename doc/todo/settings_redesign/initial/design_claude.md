# Design: Settings Page Restructure

Date: 2026-04-03
Ref: UX spec `doc/todo/settings_redesign/ux_design.md`

---

## 1. Goal

Transform the flat 362-line `SettingsSheet` into a two-level navigation structure with three sub-pages. Add OAuth lifecycle management (status display, sign-out, re-auth) so OAuth users aren't trapped. Add provider-linked model catalogs in the API Key tab.

**Non-goals**: multi-account, search, import/export, tablet two-pane.

---

## 2. Approach

### Key design decisions

1. **Single `SettingsPage` enum drives `AnimatedContent`** — no navigation graph, no NavHost. The settings sheet is a bottom sheet, not a screen. A simple `var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }` inside `SettingsSheet` with `AnimatedContent(settingsPage)` handles all transitions. Keep it composable-local — no ViewModel state for navigation.

2. **Reuse OAuth machinery from onboarding, don't reuse the ViewModel.** The `OnboardingViewModel` couples OAuth with step progression, demo lifecycle, and permission checks — all irrelevant here. Instead, extract the raw OAuth operations (PKCE generation, callback server, token exchange, validation) into callbacks that `MainActivity` wires up. The OAuth composable functions (`startOAuth`, `cancelOAuth`) and credential storage (`OAuthCredentialStore`) already exist as standalone units — they just need to be called from a different entrypoint.

3. **Provider-linked model catalogs via `ModelCatalog.all().filter { it.provider == X }`.** The catalog already has `LLMProvider` on every entry. The API Key tab filters the dropdown list based on the selected provider sub-selector. No new data structure needed.

4. **`SettingsSheet` parameter list stays flat** — no settings "state object" wrapper. The current pattern of individual value+callback pairs matches the codebase convention. We add 5 new parameters (3 OAuth callbacks + authMethod + oauthEmail) and remove the `llmBackend`/`onBackendChange` pair (backend is now implicit from which tab the user is in). Net change is small.

5. **Tab selection auto-selects the active auth method and backend.** When user switches to "Sign In" tab, `llmBackend` becomes `OPENAI` and `authMethod` becomes `"oauth"`. When user switches to "API Key" tab, `authMethod` becomes `null` (manual). When user switches to "Local" tab, `llmBackend` becomes `LOCAL`. This removes the need for a separate backend selector. The tab *is* the backend/auth selector.

---

## 3. Components

### 3.1 New Types

```kotlin
// ui/settings/SettingsNavigation.kt (new file, ~20 lines)

/** Two-level settings navigation. Local to SettingsSheet. */
enum class SettingsPage {
    HOME, LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED
}

/** Which tab is active in the LLM & Auth sub-page. */
enum class LlmAuthTab {
    SIGN_IN, API_KEY, LOCAL
}

/** Provider sub-selector within API Key tab. */
enum class ApiKeyProvider(val label: String, val llmProvider: LLMProvider) {
    OPENAI("OpenAI", LLMProvider.OPENAI),
    OPENROUTER("OpenRouter", LLMProvider.OPENROUTER),
    NOVITA("Novita", LLMProvider.NOVITA)
}
```

**Rationale**: `ApiKeyProvider` replaces `OnboardingProvider` for the settings context. Onboarding only had OPENAI/OPENROUTER; settings needs all three including NOVITA. We don't reuse `OnboardingProvider` to avoid coupling. `ApiKeyProvider` maps directly to `LLMProvider` for catalog filtering.

### 3.2 OAuth State for Settings

```kotlin
// ui/settings/SettingsOAuthState.kt (new file, ~15 lines)

/** OAuth state within Settings Sign In tab. */
sealed interface SettingsOAuthState {
    data class Active(val email: String) : SettingsOAuthState
    data object NotSignedIn : SettingsOAuthState
    data object InProgress : SettingsOAuthState
    data class Error(val message: String) : SettingsOAuthState
}
```

This is separate from `ApiKeyStepState` (onboarding) because:
- No `OAuthReady` state — we fold that into `NotSignedIn`
- No `OAuthSuccess` state — success transitions to `Active`
- Doesn't extend `OnboardingStepState`

### 3.3 AppSettingsState Changes

Add one observable field:

```kotlin
// AppSettingsState.kt — add:
var oauthEmail by mutableStateOf<String?>(null)
    private set

fun updateOAuthEmail(email: String?) {
    oauthEmail = email
}
```

`oauthEmail` is loaded from `OAuthCredentialStore.load()?.email` at startup (in `MainActivity.onCreate`, alongside existing `authMethod` loading). Not persisted in `AppSettingsStore` — `OAuthCredentialStore` is the source of truth.

No other `AppSettingsState` changes needed. `authMethod` and its update method already exist.

### 3.4 SettingsSheet — New Signature

```kotlin
@Composable
fun SettingsSheet(
    // ── LLM & Auth ──
    selectedModel: String,
    onModelChange: (String) -> Unit,
    modelCatalog: ModelCatalog,          // NEW: full catalog, filtered by tab
    selectedExecutorModel: String?,
    onExecutorModelChange: (String?) -> Unit,
    agentMode: AgentMode,                // needed for executor model visibility
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    // API keys (per-provider)
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    novitaApiKey: String,
    onNovitaApiKeyChange: (String) -> Unit,
    // OAuth
    authMethod: String?,                 // NEW: "oauth" or null
    oauthEmail: String?,                 // NEW: email from OAuth creds
    oauthState: SettingsOAuthState,      // NEW: current OAuth UI state
    onSignOut: () -> Unit,               // NEW
    onStartOAuth: () -> Unit,            // NEW
    onOAuthCancel: () -> Unit,           // NEW
    // Backend (still needed for initial tab selection)
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    onAuthMethodChange: (String?) -> Unit, // NEW: update authMethod
    // ── Agent Behavior ──
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    onAgentModeChange: (AgentMode) -> Unit,
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    // ── Permissions & Advanced ──
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    // ── Shell ──
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Removed**: `modelOptions: List<Pair<String, String>>` — replaced by `modelCatalog: ModelCatalog` (we filter locally).

**Why pass `modelCatalog` instead of pre-filtered options?** Because the API Key tab needs to re-filter when the user switches providers. With the full catalog, the composable does `modelCatalog.all().filter { it.provider == selectedProvider.llmProvider }` locally — no callback round-trip.

### 3.5 File Layout

```
ui/settings/
├── SettingsSheet.kt          # REWRITE: AnimatedContent scaffold, ~100 lines
├── SettingsNavigation.kt     # NEW: SettingsPage, LlmAuthTab, ApiKeyProvider enums
├── SettingsOAuthState.kt     # NEW: SettingsOAuthState sealed interface
├── SettingsHome.kt           # NEW: Level 1 navigation rows
├── LlmAuthPage.kt           # NEW: Level 2 LLM & Auth with TabRow
├── AgentBehaviorPage.kt      # NEW: Level 2 Agent Behavior
├── PermissionsAdvancedPage.kt# NEW: Level 2 Permissions & Advanced
├── OpenAiAuthCard.kt         # NEW: OAuth status card (Sign In tab)
├── SettingsWidgets.kt        # KEEP: SettingsHeader, SettingsSection, SettingsRow, ModelLoadingStatusIndicator
├── SettingsDropdown.kt       # KEEP: generic dropdown
├── SettingsDropdowns.kt      # MODIFY: remove BackendSelector, keep rest
├── SettingsModels.kt         # KEEP: LocalModelOption, ModelLoadingStatus, etc.
├── ApiKeyFields.kt           # MODIFY: single ApiKeyField (remove ApiKeysSection)
```

### 3.6 Removed Components

- `BackendSelector` in `SettingsDropdowns.kt` — no longer needed, tab selection replaces it
- `ApiKeysSection` in `ApiKeyFields.kt` — the three-key section disappears; the API Key tab shows one key field linked to the selected provider
- `PerceptionModeSelector` in `SettingsSheet.kt` — moves into `AgentBehaviorPage.kt` as-is (copy, not refactor)

---

## 4. Interactions

### 4.1 Settings Navigation State Machine

```
States: HOME | LLM_AUTH | AGENT_BEHAVIOR | PERMISSIONS_ADVANCED
Initial: HOME

Transitions:
  HOME → LLM_AUTH               : tap "LLM & Authentication" row
  HOME → AGENT_BEHAVIOR         : tap "Agent Behavior" row
  HOME → PERMISSIONS_ADVANCED   : tap "Permissions & Advanced" row
  {LLM_AUTH, AGENT_BEHAVIOR, PERMISSIONS_ADVANCED} → HOME : tap back arrow
  Any → (sheet dismissed)       : tap ✕ or swipe down
```

Implementation: `var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }` inside `SettingsSheet`. The `AnimatedContent` uses `slideInHorizontally`/`slideOutHorizontally` for level transitions.

```kotlin
// SettingsSheet.kt scaffold (simplified)
AnimatedContent(
    targetState = settingsPage,
    transitionSpec = {
        if (targetState == SettingsPage.HOME) {
            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        } else {
            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
        }
    }
) { page ->
    when (page) {
        SettingsPage.HOME -> SettingsHome(...)
        SettingsPage.LLM_AUTH -> LlmAuthPage(...)
        SettingsPage.AGENT_BEHAVIOR -> AgentBehaviorPage(...)
        SettingsPage.PERMISSIONS_ADVANCED -> PermissionsAdvancedPage(...)
    }
}
```

### 4.2 LLM & Auth Tab State Machine

```
States: SIGN_IN | API_KEY | LOCAL
Initial: derived from current (llmBackend, authMethod)
  - authMethod == "oauth" → SIGN_IN
  - llmBackend == LOCAL   → LOCAL
  - else                  → API_KEY

Transitions:
  Any → SIGN_IN  : tap Sign In tab
  Any → API_KEY  : tap API Key tab
  Any → LOCAL    : tap Local tab

Side effects on tab switch:
  → SIGN_IN : onAuthMethodChange("oauth"), onBackendChange(OPENAI)
  → API_KEY : onAuthMethodChange(null), onBackendChange(OPENAI)
  → LOCAL   : onAuthMethodChange(null), onBackendChange(LOCAL)
```

**Important**: Tab switching changes the active backend/auth method immediately. This is intentional — the tab *is* the auth method selector. The UX spec says "Tab switching does not change saved settings" but that referred to not losing entered-but-unsaved API keys. The backend and authMethod are always live.

### 4.3 Initial Tab Selection Logic

```kotlin
val initialTab = remember {
    when {
        authMethod == "oauth" -> LlmAuthTab.SIGN_IN
        llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
        else -> LlmAuthTab.API_KEY
    }
}
var selectedTab by remember { mutableStateOf(initialTab) }
```

### 4.4 OAuth State Machine (Sign In Tab)

```
States: Active(email) | NotSignedIn | InProgress | Error(msg)
Initial: derived from (authMethod, oauthEmail)
  - authMethod == "oauth" && oauthEmail != null → Active(oauthEmail)
  - authMethod == "oauth" && oauthEmail == null → Active("") // signed in but no email
  - else → NotSignedIn

Transitions:
  Active → NotSignedIn
    trigger: user taps "Sign Out"
    callback: onSignOut()
    side effect (in MainActivity):
      oauthCredentialStore.clear()
      settingsState.updateApiKey("")
      settingsState.updateAuthMethod(null)
      settingsState.updateOAuthEmail(null)
      onboardingStore.saveAuthMethod(null)

  NotSignedIn → InProgress
    trigger: user taps "Sign in with OpenAI"
    callback: onStartOAuth()
    side effect (in MainActivity):
      Same flow as OnboardingViewModel.startOAuth() but without step advancement
      On success: save tokens, update apiKey, authMethod="oauth", email
      On failure: set oauthState to Error

  InProgress → Active(email)
    trigger: OAuth callback success

  InProgress → Error(msg)
    trigger: OAuth callback failure/timeout

  Error → InProgress
    trigger: user taps "Try Again"
    callback: onStartOAuth()

  InProgress → NotSignedIn
    trigger: user taps "Cancel"
    callback: onOAuthCancel()
```

### 4.5 API Key Tab — Provider-Linked Model Catalog

When user selects a provider in the API Key tab:

```kotlin
val providerModels = remember(selectedProvider, modelCatalog) {
    catalogModelOptions(
        modelCatalog.all().filter { it.provider == selectedProvider.llmProvider }
    )
}
```

The cloud model dropdown shows only models from the selected provider. When switching providers:
1. Filter model catalog to provider's models
2. If current `selectedModel` isn't in the filtered list, auto-select the last entry (highest/newest model for that provider)
3. Show only the API key field for the selected provider

```kotlin
val (apiKey, onApiKeyChange) = when (selectedProvider) {
    ApiKeyProvider.OPENAI -> openAiApiKey to onOpenAiApiKeyChange
    ApiKeyProvider.OPENROUTER -> openRouterApiKey to onOpenRouterApiKeyChange
    ApiKeyProvider.NOVITA -> novitaApiKey to onNovitaApiKeyChange
}
```

### 4.6 Provider Sub-Selector Widget

Use Material 3 `SingleChoiceSegmentedButtonRow`:

```kotlin
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    ApiKeyProvider.entries.forEachIndexed { index, provider ->
        SegmentedButton(
            selected = selectedProvider == provider,
            onClick = { selectedProvider = provider },
            shape = SegmentedButtonDefaults.itemShape(index, ApiKeyProvider.entries.size)
        ) {
            Text(provider.label)
        }
    }
}
```

### 4.7 SettingsHome Navigation Row

```kotlin
@Composable
internal fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
)
```

Renders as a `Surface(surfaceVariant)` card with icon, title, subtitle, and trailing chevron. Three instances in `SettingsHome`.

Subtitle computation (in `SettingsHome`):

```kotlin
val llmSubtitle = remember(selectedModel, authMethod, llmBackend, ...) {
    when {
        llmBackend == LLMBackendType.LOCAL -> selectedLocalModelDisplayName
        authMethod == "oauth" -> "$modelDisplayName · OpenAI OAuth"
        else -> "$modelDisplayName · API key"
    }
}
```

### 4.8 Sub-Page Header

```kotlin
@Composable
internal fun SettingsSubPageHeader(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit
)
```

Back arrow (‹) + centered title + close (✕). Reused by all three sub-pages.

---

## 5. Data Flow: OAuth in Settings

### 5.1 MainActivity Wiring

`MainActivity` already has `oauthCredentialStore` and `onboardingStore`. It needs to:

1. Load `oauthEmail` at startup alongside `authMethod`:
```kotlin
// In onCreate, after settingsState.load():
val tokens = oauthCredentialStore.load()
settingsState.updateOAuthEmail(tokens?.email)
settingsState.updateAuthMethod(onboardingStore.loadAuthMethod())
```

2. Manage `oauthState: SettingsOAuthState` as mutableState:
```kotlin
var settingsOAuthState by mutableStateOf<SettingsOAuthState>(
    deriveOAuthState(settingsState.authMethod, settingsState.oauthEmail)
)
```

3. Provide callbacks to `SettingsSheet`:
```kotlin
onSignOut = {
    oauthCredentialStore.clear()
    settingsState.updateApiKey("")
    settingsState.updateAuthMethod(null)
    settingsState.updateOAuthEmail(null)
    onboardingStore.saveAuthMethod(null)
    settingsOAuthState = SettingsOAuthState.NotSignedIn
}

onStartOAuth = {
    settingsOAuthState = SettingsOAuthState.InProgress
    scope.launch {
        // Reuse same OAuth primitives as OnboardingViewModel
        val pkce = generatePkce()
        val state = generateOAuthState()
        val server = OAuthCallbackServer(state)
        // ... same flow, but update settingsOAuthState instead of stepState
    }
}

onOAuthCancel = {
    // Cancel running job, stop server
    settingsOAuthState = SettingsOAuthState.NotSignedIn
}
```

### 5.2 Extract OAuth Flow to Reusable Function

To avoid duplicating the ~70 lines of OAuth flow logic between `OnboardingViewModel.startOAuth()` and the settings callback, extract to a suspending helper:

```kotlin
// auth/OAuthFlowRunner.kt (new file, ~60 lines)

/**
 * Runs the full OAuth flow: PKCE → browser → callback → token exchange → validation.
 * Returns tokens on success, error message on failure.
 * Caller handles UI state transitions and credential persistence.
 */
suspend fun runOAuthFlow(
    onLaunchBrowser: (url: String) -> Unit
): OAuthFlowResult

sealed interface OAuthFlowResult {
    data class Success(val tokens: OAuthTokens) : OAuthFlowResult
    data class Error(val message: String) : OAuthFlowResult
}
```

Both `OnboardingViewModel.startOAuth()` and the settings OAuth callback call `runOAuthFlow()`. This eliminates the duplication without coupling the two callers.

---

## 6. Tasks

### Task 1: `settings-navigation-scaffold`
**Scope**: `ui/settings/SettingsSheet.kt`, `ui/settings/SettingsNavigation.kt` (new), `ui/settings/SettingsHome.kt` (new)
**What**:
- Create `SettingsPage` enum
- Create `SettingsNavigationRow` composable
- Create `SettingsSubPageHeader` composable
- Rewrite `SettingsSheet` body as `AnimatedContent(settingsPage)` scaffold
- Create `SettingsHome` with three navigation rows (subtitles can be placeholder strings)
- Create stub sub-pages that just show existing content grouped by category
**Acceptance**: Build succeeds. Opening settings shows Home with three clickable rows. Tapping a row slides to a sub-page. Back arrow returns to Home. Close (✕) dismisses sheet from any page.
**Dependencies**: none

### Task 2: `agent-behavior-page`
**Scope**: `ui/settings/AgentBehaviorPage.kt` (new), `ui/settings/SettingsSheet.kt`
**What**:
- Create `AgentBehaviorPage` composable with: MaxTurnsDropdown, AgentModeDropdown, PerceptionModeSelector
- Move `PerceptionModeSelector` from `SettingsSheet.kt` (currently a private function) to `AgentBehaviorPage.kt`
- Wire into `SettingsSheet` scaffold
**Acceptance**: Agent Behavior sub-page shows all three controls. Changes persist immediately (same behavior as today).
**Dependencies**: `settings-navigation-scaffold`

### Task 3: `permissions-advanced-page`
**Scope**: `ui/settings/PermissionsAdvancedPage.kt` (new), `ui/settings/SettingsSheet.kt`
**What**:
- Create `PermissionsAdvancedPage` composable with: Accessibility row, Overlay row, Debug toggle, version info
- Reuse existing `SettingsRow` for permission rows
- Wire into scaffold
**Acceptance**: Permissions & Advanced sub-page shows all controls. Permission taps open system settings. Debug toggle works.
**Dependencies**: `settings-navigation-scaffold`

### Task 4: `llm-auth-page-tabs`
**Scope**: `ui/settings/LlmAuthPage.kt` (new), `ui/settings/SettingsNavigation.kt`
**What**:
- Create `LlmAuthPage` composable with `TabRow` (Sign In / API Key / Local)
- Create `LlmAuthTab` enum
- Tab content: Sign In → placeholder, API Key → existing model+key UI, Local → existing local model UI
- Initial tab derived from `(llmBackend, authMethod)`
- Tab switch calls `onBackendChange` and `onAuthMethodChange`
**Acceptance**: Three tabs render. Switching tabs changes backend. API Key tab shows model dropdown + key field. Local tab shows local model dropdown + status.
**Dependencies**: `settings-navigation-scaffold`

### Task 5: `api-key-provider-selector`
**Scope**: `ui/settings/LlmAuthPage.kt`, `ui/settings/SettingsNavigation.kt`, `ui/settings/ApiKeyFields.kt`
**What**:
- Create `ApiKeyProvider` enum
- Add `SingleChoiceSegmentedButtonRow` provider sub-selector to API Key tab
- Filter model catalog by selected provider
- Show only the matching API key field
- Auto-select appropriate model when provider changes
**Acceptance**: Switching provider (OpenAI/OpenRouter/Novita) updates model dropdown to show only that provider's models and shows the matching API key field.
**Dependencies**: `llm-auth-page-tabs`

### Task 6: `oauth-flow-runner-extraction`
**Scope**: `auth/OAuthFlowRunner.kt` (new), `onboarding/OnboardingViewModel.kt`
**What**:
- Extract the OAuth flow sequence (PKCE gen, server start, browser launch, callback wait, token exchange, Codex validation) from `OnboardingViewModel.startOAuth()` into `runOAuthFlow()`
- Refactor `OnboardingViewModel.startOAuth()` to call `runOAuthFlow()`
- Verify onboarding OAuth still works identically
**Acceptance**: `OnboardingViewModel.startOAuth()` delegates to `runOAuthFlow()`. Onboarding OAuth flow unchanged.
**Dependencies**: none (can run in parallel with tasks 1-5)

### Task 7: `settings-oauth-sign-in-tab`
**Scope**: `ui/settings/OpenAiAuthCard.kt` (new), `ui/settings/SettingsOAuthState.kt` (new), `ui/settings/LlmAuthPage.kt`, `app/AppSettingsState.kt`, `app/MainActivityContent.kt`, `app/MainActivity.kt`
**What**:
- Create `SettingsOAuthState` sealed interface
- Add `oauthEmail` field to `AppSettingsState`
- Create `OpenAiAuthCard` composable showing Active/NotSignedIn/InProgress/Error states
- Wire `onSignOut`, `onStartOAuth`, `onOAuthCancel` from `MainActivity` through `MainActivityContent` to `SettingsSheet`
- Sign In tab shows model dropdowns + OAuth card
- `onStartOAuth` calls `runOAuthFlow()`, manages `settingsOAuthState`
**Acceptance**: Sign In tab shows OAuth status. Sign Out clears creds. Sign In launches browser flow. On success, card shows email.
**Dependencies**: `llm-auth-page-tabs`, `oauth-flow-runner-extraction`

### Task 8: `settings-home-subtitles`
**Scope**: `ui/settings/SettingsHome.kt`
**What**:
- Compute dynamic subtitles for each navigation row per UX spec
- LLM & Auth: `"{model} · OpenAI OAuth"` or `"{model} · API key"` or `"{localModel}"`
- Agent Behavior: `"{mode} · {maxTurns} turns · {perception}"`
- Permissions: `"{permStatus} · Debug {on/off}"`
**Acceptance**: Subtitles reflect current settings. Changing a setting and returning to Home shows updated subtitle.
**Dependencies**: `settings-navigation-scaffold`

---

## 7. Trade-offs

### Composable-local nav state vs. ViewModel state
**Chosen**: `remember { mutableStateOf(SettingsPage.HOME) }` in `SettingsSheet`.
**Alternative**: Add `settingsPage` to `AppSettingsState`.
**Why this wins**: Navigation state is transient — it resets when the sheet reopens. No persistence needed. Keeping it composable-local avoids polluting `AppSettingsState` with UI-only state. The ViewModel pattern is overkill for a bottom sheet's internal navigation.

### Full ModelCatalog vs. pre-filtered options
**Chosen**: Pass `ModelCatalog` to `SettingsSheet`, filter in composable.
**Alternative**: Pass `modelOptions: List<Pair<String, String>>` (current approach) and add per-provider option lists.
**Why this wins**: One parameter instead of three. Filtering is a one-liner. The composable already needs the catalog for provider-linked behavior. Current `catalogModelOptions()` helper still works on the filtered list.

### New `OAuthFlowRunner` vs. duplicating flow
**Chosen**: Extract shared suspending function.
**Alternative**: Copy the ~70 lines from `OnboardingViewModel` into `MainActivity`.
**Why this wins**: Single source of truth for OAuth sequence. Changes to the flow (e.g., adding a new validation step) apply everywhere. The extraction is minimal — just lift the linear sequence into a suspend function, no new abstractions.

### `SettingsOAuthState` vs. reusing `ApiKeyStepState`
**Chosen**: New `SettingsOAuthState` sealed interface.
**Alternative**: Reuse `ApiKeyStepState` from onboarding.
**Why this wins**: `ApiKeyStepState` mixes OAuth states with manual key states (`Empty`, `Editing`, `Validating`, `Invalid`, `TransientError`, `Valid`) and extends `OnboardingStepState`. Settings doesn't need any of the manual key states in the Sign In tab. Using it would mean the Settings UI imports onboarding types and ignores half the variants. A clean 4-state sealed interface is simpler and decoupled.

### Tab switches persist immediately vs. "save on exit"
**Chosen**: Tab switch calls `onBackendChange`/`onAuthMethodChange` immediately.
**Alternative**: Buffer changes and commit on sheet dismiss.
**Why this wins**: Matches existing behavior where every control change persists immediately. Buffering adds undo/discard complexity. The UX spec's "tab switching does not change saved settings" means API key text fields keep their values across tab switches — not that the backend selection is deferred.

---

## 8. Self-Review

Checked against UX spec sections:

| Spec Section | Covered? | Notes |
|---|---|---|
| Two-level navigation with AnimatedContent | Yes | Task 1 |
| Settings Home with navigation rows + subtitles | Yes | Tasks 1, 8 |
| LLM & Auth three-tab structure | Yes | Task 4 |
| Sign In tab with OAuth card | Yes | Task 7 |
| API Key tab with provider sub-selector | Yes | Task 5 |
| Local tab | Yes | Task 4 |
| Agent Behavior sub-page | Yes | Task 2 |
| Permissions & Advanced sub-page | Yes | Task 3 |
| OAuth state machine | Yes | Section 4.4 |
| Provider-linked model catalog | Yes | Section 4.5 |
| New AppSettingsState fields | Yes | Section 3.3 |
| New callbacks | Yes | Section 3.4 |
| Edge cases from spec | Yes | Covered by state machines |

Gaps found and resolved during review:
- **Executor model visibility**: The executor model dropdown should appear in both Sign In and API Key tabs when `agentMode == PRO`. Added `agentMode` to the new signature.
- **Model auto-selection on provider switch**: When switching providers in API Key tab, if current model isn't from that provider, auto-select the last model in the filtered list. Addressed in section 4.5.
- **Sign In tab model dropdowns**: The Sign In tab needs model selection too (for choosing between gpt-5.2 and gpt-5.4). The tab shows models filtered to `LLMProvider.OPENAI` only.

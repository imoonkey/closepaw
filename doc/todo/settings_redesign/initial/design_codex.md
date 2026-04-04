# Settings Page Restructure Design

Date: 2026-04-03
Input: `doc/todo/settings_redesign/ux_design.md`

## Goal

Restructure the settings bottom sheet into a scalable two-level UI, expose auth recovery/switching after onboarding, and keep the implementation simple enough to maintain.

Success means:

- One `ModalBottomSheet`, with a Home page and three level-2 sub-pages.
- `LLM & Authentication` handles OpenAI OAuth, manual API-key providers, and local models without mixing them together.
- Switching between OpenAI OAuth and a manual OpenAI API key no longer overwrites one credential with the other.
- Settings reuses the onboarding OAuth flow instead of duplicating PKCE/browser/token-exchange logic.

## Current Constraints

- `SettingsSheet.kt` is already a flat, monolithic composable with unrelated controls mixed into one scroll container.
- OAuth start/cancel/callback/token-exchange logic only exists inside `OnboardingViewModel`.
- `AppSettingsState.apiKey` is currently used as both the manual OpenAI key and the active OAuth access token. That makes the approved UX impossible: the API Key tab cannot show a stable manual OpenAI key while OAuth is active.
- `ModelCatalog` already knows provider and API shape for every cloud model. Settings should reuse that instead of hardcoding provider/model lists in UI.

## Design Decisions

### 1. Keep Navigation Local to the Sheet

Use one local page enum and `AnimatedContent` inside the existing sheet:

```kotlin
enum class SettingsPage {
    HOME,
    LLM_AUTH,
    AGENT_BEHAVIOR,
    PERMISSIONS_ADVANCED,
}
```

Why:

- Only one level of navigation exists.
- A `NavController` inside a bottom sheet is unnecessary complexity.
- The sheet can stay stateless with respect to app navigation; only the open page matters.

`SettingsSheet` becomes a shell:

- shared padding/insets
- close button
- `AnimatedContent(targetState = page)`
- per-page composables

Each page owns its own scroll state. Home and sub-pages do not share a single scroll container.

### 2. Split the Sheet by Page, Not by Control Type

Break the UI into focused page composables:

- `SettingsSheet.kt`: shell + page switching
- `SettingsHomePage.kt`: three navigation rows + version footer
- `LlmAuthSettingsPage.kt`: Sign In / API Key / Local tabs
- `AgentBehaviorSettingsPage.kt`: max turns, agent mode, perception mode
- `PermissionsAdvancedSettingsPage.kt`: permission rows + debug
- `OpenAiAuthCard.kt`: signed-out / in-progress / signed-in / error card

This keeps each file under the project’s 400-line rule and avoids another mega-composable replacing the current one.

### 3. Top-Level LLM Tabs Are Access-Mode Selectors

The approved UX text says tab switching is view-only, but the current product shape does not give Local any other clean activation path. The design therefore treats the top-level tabs as configuration selectors:

```kotlin
enum class SettingsLlmTab {
    SIGN_IN,
    API_KEY,
    LOCAL,
}
```

Behavior:

- `SIGN_IN`
  - sets `llmBackend = OPENAI`
  - uses OpenAI OAuth when a stored OAuth session exists
  - constrains model selection to OpenAI `RESPONSE` models
- `API_KEY`
  - sets `llmBackend = OPENAI`
  - uses manual provider keys
  - keeps stored OAuth credentials intact, but deactivates them for cloud requests
- `LOCAL`
  - sets `llmBackend = LOCAL`
  - leaves the last cloud provider/model choice intact for later return

This is the smallest design that avoids a hidden second “Apply” control.

### 4. `LLMProvider` and `ModelCatalog` Stay the Source of Truth

Do not add a new settings-only provider enum. The provider selector in the API Key tab should use `LLMProvider` directly, limited to:

```kotlin
listOf(LLMProvider.OPENAI, LLMProvider.OPENROUTER, LLMProvider.NOVITA)
```

Add small catalog helpers:

- `modelsFor(provider: LLMProvider, api: ApiType? = null): List<ModelEntry>`
- `preferredModelFor(provider: LLMProvider, api: ApiType? = null): ModelEntry?`

Settings should receive `ModelCatalog`, not a single precomputed `modelOptions` list. The old `List<Pair<String, String>>` input is too weak for:

- Sign In vs API Key filtering
- provider-specific model lists
- response-only OpenAI OAuth filtering
- home-page subtitle resolution

### 5. Separate Manual OpenAI Key from OAuth Access Token

This is the most important state change.

Today:

- `AppSettingsState.apiKey` stores the manual OpenAI key
- then onboarding overwrites it with the OAuth access token

That means the API Key tab cannot reliably show or preserve a manual OpenAI key.

New state shape in `AppSettingsState`:

- `openAiApiKey: String`
  - persisted in `AppSettingsStore`
  - manual OpenAI key only
- `openAiOAuthAccessToken: String`
  - transient
  - sourced from `OAuthCredentialStore`
- `oauthEmail: String?`
  - transient, display-only
- `authMethod: String?`
  - active OpenAI credential source
  - `"oauth"` when OAuth is active
  - `null` otherwise

`buildApiKeys()` changes to:

- use `openAiOAuthAccessToken` for `OPENAI_API_KEY` when `authMethod == "oauth"`
- otherwise use `openAiApiKey`
- keep `OPENROUTER_API_KEY` and `NOVITA_API_KEY` unchanged

This lets the user:

- stay signed into OpenAI
- keep a separate manual OpenAI key
- switch between them without destroying either credential

`OnboardingStore.saveAuthMethod(...)` must accept `null`, or gain a `clearAuthMethod()` helper, because settings sign-out and API-key mode need to deactivate OAuth cleanly.

One-time cleanup on load is acceptable here: if the old persisted OpenAI key exactly matches the stored OAuth access token, clear the persisted manual OpenAI key so the API Key tab does not display an OAuth bearer token as if it were a manual API key.

## Component Design

### Settings Home

Home is a pure navigation hub. It owns no persistent state.

Rows:

- `LLM & Authentication`
- `Agent Behavior`
- `Permissions & Advanced`

Footer:

- `Android Agent vX.Y (Z)`

Subtitle rules:

- `LLM & Authentication`
  - Local backend: local model display name
  - Cloud + active OAuth: `{modelDisplay} · OpenAI OAuth`
  - Cloud + manual key: `{modelDisplay} · API key`
- `Agent Behavior`
  - `{modeLabel} · {maxTurns} turns · {perceptionLabel}`
- `Permissions & Advanced`
  - `{permissionSummary} · Debug {on/off}`

### LLM & Authentication

Local UI state inside the page:

- `selectedTab: SettingsLlmTab`
- `selectedApiKeyProvider: LLMProvider`

Both are `rememberSaveable`, initialized from durable app state when the sheet opens.

#### Sign In Tab

Purpose:

- OpenAI OAuth only
- OpenAI `RESPONSE` models only

Content:

- cloud model dropdown
- executor model dropdown when `agentMode == PRO`
- `OpenAiAuthCard`

Card states:

```kotlin
sealed interface OpenAiAuthUiState {
    data object SignedOut : OpenAiAuthUiState
    data object InProgress : OpenAiAuthUiState
    data class SignedIn(val email: String?) : OpenAiAuthUiState
    data class Error(val message: String) : OpenAiAuthUiState
}
```

Actions:

- `SignedOut` -> `Sign in with OpenAI`
- `InProgress` -> `Cancel`
- `SignedIn` -> `Sign Out`
- `Error` -> `Try Again`

Canonicalization:

- entering `SIGN_IN` forces the main model to an OpenAI `RESPONSE` entry if the current main model is not valid for this mode
- executor model is reset to `null` when it is not an OpenAI `RESPONSE` model

#### API Key Tab

Purpose:

- manual cloud credentials
- provider-specific model catalogs

Content:

- provider selector: OpenAI / OpenRouter / Novita
- provider-filtered cloud model dropdown
- provider-filtered executor dropdown when `agentMode == PRO`
- one API key field bound to the selected provider

Rules:

- selecting a provider changes the filtered model catalog
- if the current main model is from a different provider, switch it to `preferredModelFor(provider)`
- if the current executor model is from a different provider, reset it to `null`
- entering `API_KEY` deactivates OAuth for requests by setting `authMethod = null`
- stored OAuth credentials are not cleared here; only `Sign Out` clears them

This intentionally removes hidden cross-provider main/executor combinations from the settings UI. If cross-provider execution is ever needed, it should come with explicit UX and validation, not as an invisible edge case.

#### Local Tab

Purpose:

- on-device inference only

Content:

- existing local model dropdown
- existing `ModelLoadingStatusIndicator`

Rules:

- entering `LOCAL` sets `llmBackend = LOCAL`
- it does not modify the last cloud model/provider selection

### Agent Behavior

This page is mostly a straight move of existing controls:

- `MaxTurnsDropdown`
- `AgentModeDropdown`
- `PerceptionModeSelector`

No new state object is needed. `AppSettingsState` remains the source of truth.

### Permissions & Advanced

This page contains:

- Accessibility service status row
- Overlay permission status row
- Debug mode toggle

`Shizuku` should stay a reserved slot in code structure, not a rendered dead-end control. A disabled “future” row adds UI noise without behavior.

Version information stays on Home, not here.

## OAuth Reuse Strategy

Extract the OAuth transport/state work from onboarding into a reusable manager, for example:

- `auth/OpenAiOAuthManager.kt`

Responsibilities:

- PKCE generation
- localhost callback server lifecycle
- browser URL generation
- auth-code exchange
- Codex token validation
- refresh-if-needed
- credential-store save/load/clear
- syncing `AppSettingsState` OAuth snapshot (`openAiOAuthAccessToken`, `oauthEmail`, `authMethod`)

Not its job:

- onboarding step completion
- settings page navigation
- Compose rendering

Usage split:

- `OnboardingViewModel`
  - delegates `startOAuth()` / `cancelOAuth()` to the manager
  - keeps onboarding-specific `ApiKeyStepState`
  - still decides when the wizard step becomes `Done`
- `MainActivity`
  - owns one settings-facing manager instance after onboarding
  - passes new callbacks into `MainActivityContent` / `SettingsSheet`
  - calls `refreshIfNeeded()` before session creation, replacing the current inline refresh logic

This keeps the complicated auth code in one place without dragging the full onboarding VM into settings.

## App State and Callback Changes

### `AppSettingsState`

Change:

- rename ambiguous `apiKey` usage to `openAiApiKey`
- add transient OAuth snapshot fields
- keep manual provider keys persisted exactly once

Add helper methods:

- `updateOpenAiApiKey(key: String)`
- `updateProviderApiKey(provider: LLMProvider, key: String)`
- `providerApiKey(provider: LLMProvider): String`
- `syncOpenAiOAuth(accessToken: String?, email: String?)`
- `clearOpenAiOAuth()`
- `setOAuthActive(active: Boolean)`

No new settings ViewModel is needed. Durable state belongs in `AppSettingsState`; page/tab/provider selection stays local to the sheet.

### New Cross-Layer Callbacks

Add only the callbacks that cross the UI/activity boundary:

- `onStartOAuth: () -> Unit`
- `onCancelOAuth: () -> Unit`
- `onSignOut: () -> Unit`

No callback is needed for Home/sub-page navigation. That state stays internal to `SettingsSheet`.

### `MainActivityContent` / `SettingsSheet` Signature Changes

Add:

- `modelCatalog: ModelCatalog`
- `openAiAuthUiState: OpenAiAuthUiState`
- `onStartOAuth`
- `onCancelOAuth`
- `onSignOut`

Remove:

- `modelOptions: List<Pair<String, String>>`

The page now needs the full catalog to filter models by provider and auth mode.

## Interaction State Machines

### Sheet Navigation

```text
HOME -> LLM_AUTH
HOME -> AGENT_BEHAVIOR
HOME -> PERMISSIONS_ADVANCED
<subpage> -> HOME via back arrow
ANY -> dismissed via close or swipe-down
```

### LLM Access Mode

```text
SIGN_IN:
  backend = OPENAI
  authMethod = oauth when stored OAuth credentials exist
  main/executor models constrained to OpenAI RESPONSE entries

API_KEY(provider):
  backend = OPENAI
  authMethod = null
  main/executor models constrained to selected provider

LOCAL:
  backend = LOCAL
```

### OpenAI Auth Card

```text
SignedOut -> InProgress -> SignedIn
SignedOut -> InProgress -> Error
InProgress -> SignedOut via Cancel
SignedIn -> SignedOut via Sign Out
SignedIn -> Error when refresh/validation fails
Error -> InProgress via Try Again
```

`Sign Out` clears:

- `OAuthCredentialStore`
- transient OAuth token/email in `AppSettingsState`
- persisted active auth method

It does not clear the manual OpenAI API key.

## Tasks

### `settings-shell-navigation`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/*Page.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsWidgets.kt`

Acceptance criteria:

- Home page renders the three navigation rows and version footer.
- Sub-pages use a shared back/close header.
- `AnimatedContent` drives page transitions.
- No settings UI file grows past 400 lines.

Dependencies:

- none

### `openai-credential-state-split`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity*.kt`

Acceptance criteria:

- Manual OpenAI key and OAuth access token are stored separately.
- `buildApiKeys()` uses the active OpenAI credential source.
- Sign-out preserves the manual OpenAI key.
- Old persisted OAuth access tokens are not shown in the API Key tab as if they were manual keys.

Dependencies:

- none

### `shared-openai-oauth-manager`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/auth/*.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`

Acceptance criteria:

- PKCE/browser/callback/exchange/refresh logic lives in one reusable manager.
- Onboarding and settings both use the shared manager.
- Browser launch remains activity-owned side effect.

Dependencies:

- `openai-credential-state-split`

### `llm-auth-subpage`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/*.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ModelCatalog.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt`

Acceptance criteria:

- `Sign In / API Key / Local` tabs render and switch correctly.
- Sign In uses OpenAI `RESPONSE` models only.
- API Key provider selector switches model catalogs for OpenAI, OpenRouter, and Novita.
- Invalid executor-provider combinations are canonicalized to `null`.
- OpenAI auth card supports signed-out, in-progress, signed-in, and error states.

Dependencies:

- `settings-shell-navigation`
- `openai-credential-state-split`
- `shared-openai-oauth-manager`

### `behavior-and-advanced-pages`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/*.kt`

Acceptance criteria:

- Existing max-turns, agent-mode, and perception controls move into `Agent Behavior`.
- Existing permission rows and debug toggle move into `Permissions & Advanced`.
- Home subtitles reflect live state from those pages.

Dependencies:

- `settings-shell-navigation`

### `validation-and-tests`

Scope:

- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityModelValidation.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/**`

Acceptance criteria:

- Tests cover manual-vs-OAuth OpenAI key selection.
- Tests cover sign-out preserving manual OpenAI key.
- Tests cover provider-filtered model selection and executor canonicalization.
- Existing cloud/local launch validation still works with the new state shape.

Dependencies:

- all prior tasks

## Trade-offs

- Shared OAuth manager vs reusing the full onboarding VM:
  - Manager wins. Settings needs OAuth after onboarding, but it does not need the onboarding step machine.
- Local page state vs new settings VM:
  - Local state wins. Page/tab/provider selection is ephemeral UI state, not domain state.
- Same-provider main/executor settings vs preserving hidden cross-provider combos:
  - Same-provider wins. The new UX presents one provider at a time; the code should enforce the same invariant.
- Full `ModelCatalog` in settings vs precomputed `modelOptions`:
  - `ModelCatalog` wins. Filtering by provider and auth mode belongs next to the UI using it.

## Self-Review

This design covers all requested areas:

- two-level scaffold with `AnimatedContent`
- three-tab `LLM & Authentication` page
- `Agent Behavior` page
- `Permissions & Advanced` page
- OAuth reuse from onboarding
- `AppSettingsState` and callback changes
- provider-linked model catalog switching

It also resolves the main hidden blocker in the current code: the single OpenAI `apiKey` field cannot represent both a manual key and an OAuth token at the same time. Without fixing that state model first, the approved UX would look correct but behave incorrectly.

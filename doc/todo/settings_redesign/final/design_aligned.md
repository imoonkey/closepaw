# Aligned Design: Settings Page Restructure

Date: 2026-04-03
Sources:
- `doc/todo/settings_redesign/ux_design.md`
- `doc/todo/settings_redesign/initial/design_claude.md`
- `doc/todo/settings_redesign/initial/design_codex.md`
- `doc/todo/settings_redesign/initial/design_review_claude.md`
- `doc/todo/settings_redesign/initial/design_review_codex.md`

## Goal

Restructure the flat settings sheet into a two-level settings UI that scales, gives OAuth users a full recovery path after onboarding, and keeps the runtime configuration unambiguous.

Success means:

- the sheet opens to a Home page with three navigation rows
- `LLM & Authentication` exposes `Sign In`, `API Key`, and `Local`
- OAuth users can see status, sign out, and re-authenticate in settings
- users can switch between OAuth and manual API keys without destroying either credential
- provider-linked cloud model selection stays valid for the active credential source

## Current Constraints

- `SettingsSheet.kt` is currently one flat page that mixes unrelated controls.
- `OnboardingViewModel` currently owns the only implemented OpenAI OAuth flow.
- `AppSettingsState.apiKey` is currently overloaded: onboarding writes the OAuth access token into the same field used for the manual OpenAI API key.
- `ModelCatalog` already carries the information needed for provider-linked filtering and API-shape filtering.

## Agreed Design

### 1. Two-Level Sheet Navigation

The existing `ModalBottomSheet` stays. Internal navigation is local to the sheet and uses `AnimatedContent`, not a nested `NavHost`.

```kotlin
enum class SettingsPage {
    HOME,
    LLM_AUTH,
    AGENT_BEHAVIOR,
    PERMISSIONS_ADVANCED,
}
```

Page and tab state uses `remember`, not `rememberSaveable`. The sheet is gated by `if (showSettings)` in `MainActivityContent`, where `showSettings` is a non-restorable `var` on `MainActivity` (no `configChanges` declared in the manifest). On configuration change the Activity recreates, `showSettings` resets to `false`, and the sheet leaves composition — so `rememberSaveable` would never restore.

The sheet contains:

- a shared shell with insets and close handling
- a Home page
- three level-2 sub-pages
- a shared sub-page header with back + close

`AnimatedContent` transitions use directional horizontal slides:

```kotlin
AnimatedContent(
    targetState = settingsPage,
    transitionSpec = {
        if (targetState == SettingsPage.HOME) {
            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        } else {
            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
        }
    }
) { page -> ... }
```

Home is a pure navigation hub with these rows:

- `LLM & Authentication`
- `Agent Behavior`
- `Permissions & Advanced`

The version footer stays on Home, matching the approved UX.

### 2. Split by Page

The settings UI should be decomposed into page-focused composables rather than another large monolithic file.

Target structure:

- `SettingsSheet.kt`: shell + page switching (~100 lines)
- `SettingsHomePage.kt`: navigation rows + version footer
- `LlmAuthSettingsPage.kt`: Sign In / API Key / Local tabs
- `AgentBehaviorSettingsPage.kt`: max turns, agent mode, perception mode
- `PermissionsAdvancedSettingsPage.kt`: permission rows + debug
- `OpenAiAuthCard.kt`: signed-out / in-progress / signed-in / error card

Existing reusable widgets such as `SettingsDropdown`, `SettingsRow`, and `ModelLoadingStatusIndicator` stay and are reused.

Removed components:

- `BackendSelector` in `SettingsDropdowns.kt` — replaced by tab selection
- `ApiKeysSection` in `ApiKeyFields.kt` — replaced by single provider-linked key field in API Key tab (the private `ApiKeyField` composable stays)
- `PerceptionModeSelector` in `SettingsSheet.kt` — moves to `AgentBehaviorSettingsPage.kt` (currently `private`, needs visibility change or copy)

### 3. LLM & Authentication Page

The page has three top-level tabs:

```kotlin
enum class LlmAuthTab { SIGN_IN, API_KEY, LOCAL }
```

**Tabs are immediate config selectors.** Switching tabs mutates the durable backend and auth method right away. Every existing settings control persists on change — there is no batch/apply pattern anywhere in the codebase. Deferring tab changes would require an "Apply" button that doesn't exist and would be inconsistent with everything else. The UX spec's "tab switching does not change saved settings" refers to preserving API key text field values across tab visits, not deferring backend selection.

Tab side effects:

- `SIGN_IN`: sets `llmBackend = OPENAI`, `authMethod = "oauth"`
- `API_KEY`: sets `llmBackend = OPENAI`, `authMethod = null`
- `LOCAL`: sets `llmBackend = LOCAL`, `authMethod = null`

Initial tab is derived from current durable state:

```kotlin
val initialTab = remember {
    when {
        authMethod == "oauth" -> LlmAuthTab.SIGN_IN
        llmBackend == LLMBackendType.LOCAL -> LlmAuthTab.LOCAL
        else -> LlmAuthTab.API_KEY
    }
}
```

The API Key tab owns a provider sub-selector. It uses `LLMProvider` directly — no wrapper enum. `LLMProvider` already carries `defaultApiKeyEnv` and `defaultBaseUrl`; a display label is a one-liner extension:

```kotlin
val LLMProvider.displayLabel: String get() = when (this) {
    LLMProvider.OPENAI -> "OpenAI"
    LLMProvider.OPENROUTER -> "OpenRouter"
    LLMProvider.NOVITA -> "Novita"
}
```

Consensus behavior:

- `Sign In` is the OpenAI OAuth surface; shows only OpenAI `RESPONSE` models (Codex backend requirement)
- `API Key` is the manual cloud credential surface; shows models filtered to the selected provider
- `Local` is the on-device model surface
- the old flat `BackendSelector` in `SettingsDropdowns.kt` is removed
- the old flat three-key `ApiKeysSection` in `ApiKeyFields.kt` is removed; the API Key tab shows one key field bound to the selected provider
- the cloud model source is linked to the current tab/provider context

### 4. Provider-Linked Cloud Model Selection

`SettingsSheet` should receive the full `ModelCatalog`, not a single flattened `modelOptions` list.

The UI filters models by context:

- `Sign In`: OpenAI only, and only models compatible with the OpenAI OAuth runtime path
- `API Key`: models filtered to the selected provider
- `Local`: no cloud model list shown

When the active cloud context changes, invalid selections are canonicalized instead of preserved as hidden edge cases:

- if the current main model is invalid for the selected provider/auth mode, replace it with that context’s preferred model
- if the current executor model is invalid for the selected provider/auth mode, reset it to `null`

For the current codebase, the OpenAI OAuth path must use OpenAI `RESPONSE` models, not OpenAI `CHAT` models.

### 5. Agent Behavior Page

This page is a direct regrouping of the current behavior settings:

- max turns
- agent mode
- perception mode

No new durable state is needed here.

### 6. Permissions & Advanced Page

This page contains:

- Accessibility service status row
- Overlay permission status row
- Debug mode toggle

`Shizuku` is future work and should not be rendered as a dead-end control unless there is real behavior behind it.

### 7. Dynamic Home Subtitles

Home row subtitles remain live summaries of the current durable settings:

- `LLM & Authentication`
  - local backend: local model display name
  - active OpenAI OAuth: `{model} · OpenAI OAuth`
  - manual cloud credentials: `{model} · API key`
- `Agent Behavior`
  - `{mode} · {maxTurns} turns · {perception}`
- `Permissions & Advanced`
  - `{permissionSummary} · Debug {on/off}`

## State and Data Flow

### 1. Split Manual OpenAI Key from OAuth Token

This is required. The current single-field approach cannot support the approved UX.

Aligned state shape:

- persisted manual OpenAI key
- persisted OpenRouter key
- persisted Novita key
- transient OpenAI OAuth access token
- transient OAuth display email
- durable active auth method marker for OpenAI

The important invariant is:

- the manual OpenAI key remains intact when the user signs in with OAuth
- sign-out clears OAuth credentials and deactivates OAuth
- sign-out does not clear the manual OpenAI key

`buildApiKeys()` must select the active OpenAI credential source:

- if OpenAI auth method is OAuth, expose the OAuth access token as `OPENAI_API_KEY`
- otherwise expose the persisted manual OpenAI key as `OPENAI_API_KEY`

This keeps the existing session/bootstrap path working while fixing the product behavior.

### 2. Migration

The aligned design includes a one-time cleanup for existing installs created before the credential split:

- if the legacy persisted manual OpenAI key exactly matches the stored OAuth access token, treat that persisted value as an old OAuth artifact and clear it from the manual-key slot during migration

Without this, older users can land in the new API Key tab with a bearer token displayed as if it were a manual API key.

### 3. Shared OpenAI OAuth Reuse

Settings should reuse onboarding’s OpenAI OAuth implementation, but not by reusing `OnboardingViewModel`.

Resolved shape: use shared suspend helpers under `auth/`, not a stateful manager class.

Why this wins in the current codebase:

- the auth module already follows a stateless helper style: `generatePkce`, `generateOAuthState`, `buildAuthorizeUrl`, `OAuthTokenExchange`, and `OAuthCodexValidator`
- the current sign-in flow is one linear coroutine, not a reusable long-lived state machine
- cancellation is already naturally expressed as coroutine cancellation; server cleanup can live in `try/finally`
- refresh is a short separate path and does not need to be bundled into a long-lived object

The shared auth layer should provide:

- a suspend helper that runs the OpenAI sign-in sequence:
  - PKCE generation
  - callback server startup
  - browser launch callback
  - callback wait
  - auth-code exchange
  - Codex validation
  - cleanup on success, failure, or cancellation
- a small refresh helper, or equivalent shared function, for refresh-if-needed

Host responsibilities stay host-owned:

- onboarding maps success/failure to `ApiKeyStepState`, stores step outcomes, and advances the wizard
- settings maps success/failure to `OpenAiAuthUiState` and updates sheet-visible auth state
- browser launch remains an activity-owned side effect
- credential persistence and `AppSettingsState` sync happen in the caller after a successful result

### 4. Settings OAuth UI State

The settings page should use its own small OAuth UI state model, separate from onboarding’s `ApiKeyStepState`.

```kotlin
sealed interface OpenAiAuthUiState {
    data object SignedOut : OpenAiAuthUiState
    data object InProgress : OpenAiAuthUiState
    data class SignedIn(val email: String?) : OpenAiAuthUiState
    data class Error(val message: String) : OpenAiAuthUiState
}
```

Initial state is derived from durable settings at sheet open time:

```
authMethod == "oauth" && oauthEmail != null  → SignedIn(oauthEmail)
authMethod == "oauth" && oauthEmail == null  → SignedIn(null)
else                                         → SignedOut
```

The Sign In tab card renders those states and exposes:

- `SignedOut` → `Sign in with OpenAI`
- `InProgress` → `Cancel`
- `SignedIn` → `Sign Out`
- `Error` → `Try Again`

### 5. Cross-Layer Callbacks

The sheet needs these new cross-layer callbacks:

- `onStartOAuth`
- `onCancelOAuth`
- `onSignOut`

Navigation between Home and sub-pages stays internal to the sheet and does not need activity callbacks.

## Implementation Notes

### MainActivity / MainActivityContent

`MainActivity` remains the place that owns:

- `AppSettingsState`
- `OnboardingStore`
- `OAuthCredentialStore`
- the shared OpenAI OAuth helper calls and their host-side result handling

`MainActivityContent` should pass the full `ModelCatalog` and the new OAuth-related values/callbacks into settings.

### AppSettingsState

`AppSettingsState` remains the durable settings surface, but it must stop using one field for two incompatible OpenAI credential sources.

It should expose:

- distinct update methods for manual provider keys
- methods to sync/clear transient OAuth state
- `buildApiKeys()` based on the active OpenAI auth mode

### ModelCatalog

The aligned design assumes small helper methods on `ModelCatalog` are acceptable if they simplify repeated filtering and preferred-model selection.

Examples:

- models for a provider
- models for a provider + API shape
- preferred model for a provider + API shape

These helpers are optional convenience, not a new abstraction layer.

## State Machines

### Sheet Navigation

```text
HOME -> LLM_AUTH
HOME -> AGENT_BEHAVIOR
HOME -> PERMISSIONS_ADVANCED
<subpage> -> HOME via back arrow
ANY -> dismissed via close or swipe-down
```

### OpenAI OAuth Card

```text
SignedOut -> InProgress -> SignedIn
SignedOut -> InProgress -> Error
InProgress -> SignedOut via Cancel
SignedIn -> SignedOut via Sign Out
Error -> InProgress via Try Again
```

Side effects:

- `Sign In` success saves OAuth credentials, updates the active OpenAI auth mode, and updates the settings-facing OAuth snapshot
- `Sign Out` clears OAuth credentials and deactivates OAuth, but preserves the manual OpenAI key

### Cloud Model Canonicalization

```text
Context changes (auth mode / provider / local-vs-cloud):
  if main model invalid for new context -> replace with preferred valid model
  if executor model invalid for new context -> reset to null
```

This avoids hidden invalid configurations surviving behind the new UI.

## Tasks

### 1. Settings Scaffold

Scope:

- split `SettingsSheet.kt` into a shell plus page composables
- add Home rows, sub-page header, and `AnimatedContent`

Acceptance:

- Home and three sub-pages render
- back/close behavior works
- no settings UI file becomes another oversized monolith

### 2. OpenAI Credential Split and Migration

Scope:

- split manual OpenAI key and OpenAI OAuth token
- update `buildApiKeys()`
- add migration/cleanup for legacy installs

Acceptance:

- manual OpenAI key survives OAuth sign-in/sign-out
- active runtime credential selection remains correct

### 3. Shared OAuth Reuse

Scope:

- extract the reusable OpenAI OAuth flow out of onboarding-specific control flow into shared suspend helpers under `auth/`
- wire those helpers into both onboarding and settings
- move token refresh under the same shared helper layer

Acceptance:

- onboarding behavior remains unchanged
- settings can sign in, cancel, sign out, and refresh through the shared helpers

### 4. LLM & Auth Tab Structure

Scope:

- `LlmAuthSettingsPage.kt` with `TabRow` (Sign In / API Key / Local)
- `LlmAuthTab` enum
- Tab content: Sign In → model dropdowns + placeholder auth card, API Key → model dropdown + key field, Local → local model dropdown + status
- Tab switching calls `onBackendChange` and `onAuthMethodChange`

Acceptance:

- three tabs render and switch correctly
- tab selection persists backend/authMethod immediately
- initial tab reflects current durable state

### 5. API Key Provider Selector and Model Filtering

Scope:

- `SingleChoiceSegmentedButtonRow` for provider sub-selector in API Key tab
- provider-linked model catalog filtering
- provider-linked single API key field
- `ModelCatalog` helpers: `modelsFor(provider, api?)`, `preferredModelFor(provider, api?)`
- model/executor canonicalization on provider switch

Acceptance:

- switching provider updates model dropdown to that provider's models
- shows only the matching API key field
- invalid main/executor models are canonicalized

### 6. OpenAI Auth Card (Sign In Tab)

Scope:

- `OpenAiAuthCard.kt` composable for all four OAuth UI states
- wire `onStartOAuth`, `onCancelOAuth`, `onSignOut` from `MainActivity` through `MainActivityContent`
- Sign In tab shows OpenAI `RESPONSE` models only + auth card

Acceptance:

- auth card shows correct state (SignedOut/InProgress/SignedIn/Error)
- sign out clears OAuth but preserves manual key
- sign in launches browser OAuth flow
- on success, card shows email

### 7. Agent Behavior and Permissions Pages

Scope:

- regroup the existing controls into the two sub-pages
- move `PerceptionModeSelector` from `SettingsSheet.kt` to `AgentBehaviorSettingsPage.kt`
- wire Home subtitles to the new structure

Acceptance:

- existing behavior is preserved
- Home subtitle summaries update from live state

### 8. Validation and Tests

Scope:

- tests for OpenAI credential selection
- tests for sign-out preserving the manual OpenAI key
- tests for provider-linked model filtering and canonicalization
- tests for any migration added during the credential split

Acceptance:

- the new state model is covered
- existing cloud/local validation still behaves correctly

## Open Questions

None.

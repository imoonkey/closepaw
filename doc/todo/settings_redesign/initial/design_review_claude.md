# Cross-Review: Codex Design for Settings Restructure

Reviewer: Claude
Date: 2026-04-03

---

## 1. Where Both Designs Agree

These areas are settled — no debate needed:

- **`SettingsPage` enum + `AnimatedContent`** for two-level navigation. No NavController.
- **Composable-local nav state** (`remember`/`rememberSaveable`). No ViewModel for page/tab selection.
- **Tabs are config selectors**, not view-only. Switching tabs changes backend/authMethod immediately.
- **Full `ModelCatalog` passed to SettingsSheet**, replacing the pre-filtered `modelOptions: List<Pair<String, String>>`.
- **Separate OAuth sealed class** for settings UI state (not reusing `ApiKeyStepState` from onboarding).
- **Same three cross-layer callbacks**: `onStartOAuth`, `onCancelOAuth`, `onSignOut`.
- **OAuth extraction** from `OnboardingViewModel` into a shared unit.
- **File-per-page** structure, each under 400 lines.

---

## 2. Correctness Findings

### 2.1 CRITICAL — Codex Identifies the Credential Split; Claude Misses It

Codex's most important contribution is section 5: "Separate Manual OpenAI Key from OAuth Access Token."

The current code has `AppSettingsState.apiKey` serving dual duty — it stores the manual OpenAI key AND gets overwritten with the OAuth access token by `OnboardingViewModel.startOAuth()` (line 170: `settingsState.updateApiKey(tokens.accessToken)`). This means:

- If a user has a manual OpenAI key, authenticates via OAuth, then switches to the API Key tab, their manual key is gone — replaced by a bearer token.
- Sign Out would clear the OAuth token, but the old manual key is already destroyed.

**The Claude design completely misses this.** It adds `oauthEmail` to `AppSettingsState` but continues using the single `apiKey` field for both purposes. The UX spec's requirement that users can "switch auth methods without re-onboarding" is impossible without the credential split.

**Codex's fix is correct**: split into `openAiApiKey` (persisted, manual-only) and `openAiOAuthAccessToken` (transient, from `OAuthCredentialStore`), then select the active one in `buildApiKeys()` based on `authMethod`. The one-time migration logic (clearing persisted key if it matches the OAuth token) is a thoughtful detail.

**Verdict**: Codex is right. This is the most important state change and must be in the aligned design.

### 2.2 Version Info Placement

The UX spec (lines 56-57) places the version footer on the Home page. Claude's design puts it in Permissions & Advanced. Codex correctly places it on Home.

**Verdict**: Codex matches the spec.

### 2.3 Sign In Tab — RESPONSE-Only Model Filtering

Codex explicitly constrains the Sign In tab to OpenAI `RESPONSE` models. This is correct — OAuth tokens go through the Codex backend, which only supports the Responses API (see `OnboardingViewModel.findModelForProvider()`, line 543: `models.lastOrNull { it.api == ApiType.RESPONSE }`). Claude's design mentions filtering to `LLMProvider.OPENAI` but doesn't call out the `RESPONSE` constraint.

The catalog currently has `gpt-5.2`, `gpt-5.2-chat`, `gpt-5.4`, `gpt-5.4-chat` for OpenAI. The `-chat` variants use `api: "chat"`. Showing them in the Sign In tab would let users select a model that can't work with their OAuth token.

**Verdict**: Codex is correct. Sign In tab must filter to `provider == OPENAI && api == RESPONSE`.

### 2.4 Executor Model Canonicalization

Codex explicitly states: when switching providers, if the executor model is from a different provider, reset it to `null`. Claude's design doesn't address this edge case. An executor model from OpenRouter running against an OpenAI provider-keyed session would fail silently.

**Verdict**: Codex is more thorough. The canonicalization rule should be in the aligned design.

---

## 3. Design Trade-off Differences

### 3.1 Provider Enum: New `ApiKeyProvider` vs. Reuse `LLMProvider`

- **Claude**: Creates `ApiKeyProvider` enum with `label` and `llmProvider` mapping.
- **Codex**: Reuses `LLMProvider` directly, limited to `listOf(OPENAI, OPENROUTER, NOVITA)`.

Codex's approach is simpler. `ApiKeyProvider` is a wrapper that maps 1:1 to `LLMProvider` — it adds a `label` field, but that's a one-liner `when` expression. Creating a parallel enum means two places to update when adding a provider.

**Recommendation**: Use `LLMProvider` directly. Add a display label helper if needed:

```kotlin
val LLMProvider.displayLabel: String get() = when (this) {
    LLMProvider.OPENAI -> "OpenAI"
    LLMProvider.OPENROUTER -> "OpenRouter"
    LLMProvider.NOVITA -> "Novita"
}
```

### 3.2 OAuth Extraction: Suspend Function vs. Manager Class

- **Claude**: `OAuthFlowRunner` — a single `suspend fun runOAuthFlow(onLaunchBrowser: (String) -> Unit): OAuthFlowResult`. Minimal, stateless.
- **Codex**: `OpenAiOAuthManager` — a class that owns PKCE state, callback server lifecycle, credential store sync, and refresh-if-needed logic.

Claude's approach is simpler for the immediate need. But Codex's manager also absorbs:
- `refreshIfNeeded()` (currently inline in `MainActivity`)
- Credential store save/load/clear (currently scattered across `OnboardingViewModel` and the caller)
- `AppSettingsState` sync (setting `oauthAccessToken`, `oauthEmail`, `authMethod`)

The manager approach consolidates all OAuth lifecycle in one place. The suspend function approach leaves the caller responsible for credential persistence, state sync, and server cleanup on cancellation — which means both `OnboardingViewModel` and `MainActivity` each need ~20 lines of post-flow bookkeeping.

**Recommendation**: Manager class is the better fit. The OAuth lifecycle has more surface area than just the flow — it includes refresh, cleanup, and state sync. A stateless function forces the caller to reimplement those concerns.

### 3.3 Catalog Helpers: Inline Filtering vs. `ModelCatalog` Methods

- **Claude**: `modelCatalog.all().filter { it.provider == X }` inline in composables.
- **Codex**: Add `modelsFor(provider, api?)` and `preferredModelFor(provider, api?)` to `ModelCatalog`.

The filter appears in at least three places: Sign In tab (provider + RESPONSE), API Key tab (provider), and subtitle computation. Codex's helpers avoid repeating the filter predicate and centralize the "preferred model" logic (currently `models.lastOrNull()` in `OnboardingViewModel`).

**Recommendation**: Add the helpers to `ModelCatalog`. They're small, testable, and used in multiple places.

### 3.4 `rememberSaveable` vs. `remember`

Claude uses `remember` for tab/provider state. Codex uses `rememberSaveable`. In a `ModalBottomSheet`, configuration changes (rotation, dark mode toggle) destroy and recreate the sheet content. `rememberSaveable` survives that. `remember` doesn't — the user would be bounced back to the Home page on rotation.

**Recommendation**: `rememberSaveable` for navigation page and LLM tab selection.

---

## 4. Gaps in the Codex Design

### 4.1 AnimatedContent Transition Spec

Codex says "AnimatedContent drives page transitions" but doesn't specify the transition animation. Claude explicitly defines `slideInHorizontally`/`slideOutHorizontally` with directional awareness (Home→sub-page slides right, sub-page→Home slides left). This is a minor but useful implementation detail.

### 4.2 Task Granularity

Codex's `llm-auth-subpage` task combines tabs, provider selector, model filtering, OAuth card, and Sign In/API Key/Local content into one task. That's a lot of surface area. Claude breaks this into four separate tasks (tabs structure, provider selector, OAuth card, subtitles). Finer granularity makes review and parallel execution easier.

### 4.3 `PerceptionModeSelector` Relocation

Claude explicitly calls out that `PerceptionModeSelector` is currently a private function inside `SettingsSheet.kt` and needs to move to `AgentBehaviorPage.kt`. Codex lists it as "a straight move" but doesn't note the visibility change.

### 4.4 OAuth State Derivation

Claude specifies exactly how to derive the initial OAuth state from existing settings:

```
authMethod == "oauth" && oauthEmail != null → Active(oauthEmail)
authMethod == "oauth" && oauthEmail == null → Active("")
else → NotSignedIn
```

Codex's `OpenAiAuthUiState` doesn't specify the derivation logic — it just lists the states. Both need this, but Claude is more explicit.

---

## 5. Minor Issues

| Item | Claude | Codex | Note |
|------|--------|-------|------|
| Shizuku slot | Not mentioned | Reserved in code, not rendered | Codex is right — spec lists it as "future" |
| OAuth `onAuthMethodChange` callback | Adds as explicit callback | Implicit via manager | Manager approach is cleaner |
| `BackendSelector` removal | Explicit | Implicit (replaced by tabs) | Both correct, Claude more explicit |
| Test task | Not included | Included as `validation-and-tests` | Codex is more thorough |

---

## 6. Verdict: Better Base for Aligned Draft

**CODEX is the better base.**

The credential split (`openAiApiKey` vs. `openAiOAuthAccessToken`) is the single most important design decision in this restructure, and Codex identifies and solves it while Claude misses it entirely. Without this fix, the UX spec's core promise — switching between OAuth and API key without destroying either — is broken.

Codex also gets right:
- RESPONSE-only filtering for Sign In tab
- Version info on Home (matching spec)
- Reusing `LLMProvider` instead of a redundant enum
- Executor model canonicalization on provider switch
- `rememberSaveable` for surviving configuration changes
- OAuth manager with refresh lifecycle

The aligned draft should start from Codex and incorporate from Claude:
- Transition animation spec (`slideInHorizontally`/`slideOutHorizontally`)
- Finer task granularity (split `llm-auth-subpage` into 3-4 tasks)
- Explicit OAuth state derivation logic
- Explicit `BackendSelector` / `ApiKeysSection` removal list

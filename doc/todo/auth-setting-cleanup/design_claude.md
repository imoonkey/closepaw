# Auth Setting Cleanup — Design (v2)

**Status:** proposed (v4 — KISS: no migration, one-time re-auth on upgrade)
**Date:** 2026-04-17
**Review:** v1 + v2 Codex reviews incorporated. v4 simplifies by eliminating migration entirely (pre-release; one-time re-auth is cheaper than migration code + tests).
**Trigger:** Onboarding "Run Demo" after Codex OAuth login fails with *"openai_api_key not found for model gpt-5.4"*. Root cause: OAuth token is transient in-memory state, and `LLMClientFactory` requires `OPENAI_API_KEY` before it detects OAuth mode. The deeper issue is that auth mode, provider, and credential are three concepts tangled across `AppSettingsState` (fallback chains), `OnboardingStore` (duplicate `auth_method` + `api_key_draft`), and `LLMClientFactory` (`__AUTH_METHOD_OPENAI` magic key, `isOAuth()` sniff).

## Goal

One rule for auth in the whole app: **the selected model (which carries a flat provider) determines exactly which credential is loaded, where from, and which client class runs.** One credential store. No fallback chains. No magic signal keys. No duplicate persistence.

## Key Decisions

### 1. Flatten `mode × provider` at the client layer, keep the 3-level UI

UI hierarchy stays: **Mode (OAuth / API Key / Local) → Provider → Model.** Presentation only.

Internally, `LLMProvider` is a flat enum. Each entry encodes mode + backend:

```
OPENAI_API       (mode=ApiKey,  client=OpenAIResponseClient|OpenAIChatClient)
OPENAI_CODEX     (mode=OAuth,   client=CodexResponseClient)
OPENROUTER       (mode=ApiKey,  client=OpenAIChatClient @openrouter)
NOVITA           (mode=ApiKey,  client=OpenAIChatClient @novita)
LOCAL_LFM        (mode=Local,   client=LFMLLMClient)
```

`LLMProvider.mode: AuthMode` is a trivial accessor driving UI grouping.

### 2. One `AuthStore` for all cloud credentials (OAuth and API key)

Reference: `.reference/code_agent/pi-mono/packages/coding-agent/src/core/auth-storage.ts`.

```kotlin
sealed class AuthCredential {
  data class ApiKey(val key: String) : AuthCredential()
  data class OAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val email: String?,
    val idToken: String?,
  ) : AuthCredential()
}

class AuthStore(context: Context) {
  suspend fun get(provider: LLMProvider): AuthCredential?
  suspend fun set(provider: LLMProvider, cred: AuthCredential)
  suspend fun clear(provider: LLMProvider)
  fun has(provider: LLMProvider): Boolean
  /** Returns a fresh OAuth header bundle for `OPENAI_CODEX`; refreshes under mutex if near expiry. */
  suspend fun codexHeaders(): CodexHeaders
  /** Monotonically increasing per-provider generation; bumps on any set/clear. */
  fun generation(provider: LLMProvider): Long
}

data class CodexHeaders(
  val accessToken: String,
  val chatgptAccountId: String?,
  val email: String?,
)
```

- Backed by `EncryptedSharedPreferences` (reusing the crypto-degraded-fallback pattern from the current `OAuthCredentialStore`).
- Keyed by flat `LLMProvider` enum name. One credential per provider. Wrong type → clear + re-auth.
- **All cloud providers migrate into `AuthStore`:** `OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`. `LOCAL_LFM` has no credential.
- No cross-provider fallback, no env-var sniff, no `__AUTH_METHOD_OPENAI`.
- Internal `Mutex` guards OAuth refresh — concurrent coroutines (sessions, recompositions, eval runs) can race.

Replaces:
- `OAuthCredentialStore` (deleted)
- `AppSettingsState.openAiOAuthAccessToken`, `openAiManualApiKey`, `apiKey`, `openRouterApiKey`, `novitaApiKey`, `authMethod`, `buildApiKeys()`
- `OnboardingStore.KEY_AUTH_METHOD`, `KEY_API_KEY_DRAFT` (see §6)

### 3. Factory routes purely by provider; CODEX client uses a token supplier

```kotlin
fun create(modelKey: String): LLMClient {
  val entry = catalog.resolve(modelKey)
  return when (entry.provider) {
    OPENAI_API    -> when (entry.api) {
      RESPONSE -> OpenAIResponseClient(key = authStore.requireApiKey(OPENAI_API), baseUrl = entry.baseUrl)
      CHAT     -> OpenAIChatClient(key = authStore.requireApiKey(OPENAI_API), baseUrl = entry.baseUrl)
    }
    OPENAI_CODEX  -> CodexResponseClient(headerSupplier = { authStore.codexHeaders() })
    OPENROUTER    -> OpenAIChatClient(key = authStore.requireApiKey(OPENROUTER), baseUrl = entry.baseUrl)
    NOVITA        -> OpenAIChatClient(key = authStore.requireApiKey(NOVITA),     baseUrl = entry.baseUrl)
    LOCAL_LFM     -> LFMLLMClient()
  }
}
```

**Cache strategy (resolves review H3 + v2 M5 identity):** factory keeps its client cache, but `CodexResponseClient` takes a **`suspend () -> CodexHeaders` supplier** instead of capturing any OAuth state. `CodexHeaders` bundles everything the client puts on the wire: `accessToken`, `chatgptAccountId`, `email` (for telemetry). Each request asks the supplier, which calls `AuthStore.codexHeaders()` — under the store's mutex, refreshing near expiry. Cached client stays valid across token rotations **and account switches** automatically, because it never caches identity locally. Store returns the cached bundle when not near expiry; no per-request heavy work.

ApiKey providers rotate via settings edit: `AuthStore.set(provider, ApiKey)` bumps a per-provider generation counter; factory invalidates that provider's cached clients on mismatch. Simple `Map<LLMProvider, Long>` of generations, read on every `create()`. `AuthStore.clear(OPENAI_CODEX)` / `set(OPENAI_CODEX, ...)` also bumps the counter so any cached Codex client is rebuilt on next `create()` (belt-and-suspenders with the supplier).

Removed:
- `apiKeyResolver` map parameter
- `resolveApiKey()` throw-before-route
- `isOAuth(entry)` / `__AUTH_METHOD_OPENAI`
- `AppSettingsState.buildApiKeys()`
- `CodexResponseClient`'s captured `accessToken` and `chatgptAccountId` fields

Typed errors:
- `MissingCredential(provider)` — store has no credential.
- `OAuthRefreshFailed(provider, cause)` — refresh attempted, failed.
- `WrongCredentialType(provider, expected, actual)` — corrupted store entry.

### 4. Model catalog: one registry entry per flat provider

`llm_models.json`:

```json
"gpt-5.4":         { "provider": "OPENAI_API",   "api": "response", "model_id": "gpt-5.4" },
"gpt-5.4-chat":    { "provider": "OPENAI_API",   "api": "chat",     "model_id": "gpt-5.4" },
"gpt-5.4-codex":   { "provider": "OPENAI_CODEX", "api": "response", "model_id": "gpt-5.4" },
// gpt-5.2 and gpt-5.2-chat mirrored the same way
// OPENROUTER, NOVITA entries unchanged except for explicit provider name
```

Selected-model key encodes provider. "Mode" is derivable from `provider.mode`.

### 5. State flows

**Onboarding OAuth tab commit:**
1. User completes OAuth → `authStore.set(OPENAI_CODEX, OAuth(...))`.
2. `selectedModel = catalog.defaultModel(OPENAI_CODEX)` (e.g. `gpt-5.4-codex`).
3. "Run Demo" → `LLMClientFactory.create("gpt-5.4-codex")` → pattern-matches to `CodexResponseClient` with live token supplier. ✅

**Onboarding API Key tab commit:**
1. User picks provider (OpenAI / OpenRouter / Novita) and types key.
2. Typed text is ViewModel-transient (process death → retype — no encrypted draft).
3. On "Next" or Run Demo → `authStore.set(<chosen provider>, ApiKey(text))`, `selectedModel = catalog.defaultModel(<chosen provider>)`.

**Settings tab switch — canonicalization state machine** (resolves review M5 + v2 M4):
- Tab switch itself is view-only: no settings writes until a model is committed in the new tab.
- Each tab derives `selectedProviderForTab` by:
  1. If `selectedModel.provider.mode == tab.mode` → use `selectedModel.provider`.
  2. Else → tab's default provider (OAuth → `OPENAI_CODEX`; API Key → `OPENAI_API`; Local → `LOCAL_LFM`).
- Model dropdown shows models where `entry.provider == selectedProviderForTab`. No "OAuth provider in ApiKey tab" state is reachable.
- **Executor model canonicalization** (PRO mode only): on any commit that changes `selectedModel.provider`, if `executorModel.provider != selectedModel.provider`, `executorModel` is reset to `catalog.defaultExecutor(selectedModel.provider)` (same provider as main) — or cleared if the provider has no executor-capable model. This preserves the existing contract in `LlmAuthSettingsPage` that provider/auth canonicalization covers both main and executor.
- Committing a model pick writes `selectedModel` (and possibly `executorModel`). This is the only mutation from tab interaction.

**Main app credential error:** factory throws `MissingCredential` / `OAuthRefreshFailed` → surfaced via existing `ChatViewModel.reportStartupFailure()` banner → tap opens `SettingsSheet` with `initialPage=LlmAuth, initialAuthTab=<provider.mode>` (new props on `SettingsSheet`).

**OAuth token expired mid-session:** supplier call triggers refresh → persists → returns fresh token. On failure throws `OAuthRefreshFailed` → banner → user re-signs-in from settings.

### 6. OnboardingStore cleanup

`OnboardingStore` currently mixes wizard progress with auth data. Split:

| Key | Action | Rationale |
|-----|--------|-----------|
| `KEY_COMPLETED` | keep | wizard progress |
| `KEY_STEP_*` | keep | wizard progress |
| `KEY_SCHEMA_VERSION` | keep | onboarding migration |
| `KEY_AUTH_METHOD` | **delete** | duplicate of `authMethod`; mode derives from `selectedModel.provider.mode` |
| `KEY_API_KEY_DRAFT` | **delete** | second source of truth for API key; onboarding typed text becomes ViewModel-transient |
| encrypted prefs file | **delete** | whole `onboarding_secure_prefs` goes away |

Migration reads both keys once (legacy input) before deletion.

### 7. Debug intent overrides

Current debug build accepts intent extras `api_key`, `openrouter_api_key`, `novita_api_key`, `openai_base_url` via `MainActivityIntentPayload` → `MainActivityIntentApplier`.

New behavior (resolves review M6):
- `api_key` / `openrouter_api_key` / `novita_api_key` → debug-only code path calls `authStore.set(<provider>, ApiKey(value))` directly. Scoped to `BuildConfig.DEBUG`.
- `openai_base_url` is not a credential — move to `AppSettingsState.openAiBaseUrlOverride` (new field, debug-only semantics); catalog `effectiveBaseUrl` consults it.
- No separate "session override layer". Debug writes pass through the same store as production, keeping one code path.

### 8. Upgrade path (no migration code)

Product is pre-release. One-time break on upgrade is acceptable. **Zero migration code.**

What happens to a tester's device on upgrade:
1. New build ships with empty `AuthStore`. Legacy SharedPreferences files (`oauth_credentials`, `onboarding_secure_prefs`) stay on disk, harmless — no code reads them anymore.
2. First session start → `LLMClientFactory` hits empty `AuthStore` → throws `MissingCredential` → startup banner (§5) → tap → Settings → user signs in or pastes key.
3. Pre-upgrade session checkpoints are invalidated by bumping `SessionRuntimeSnapshot.schemaVersion` from 1 → 2. `AgentSession.reload` already rejects mismatched schema → UI shows "Session from previous version — start a new session."
4. Release notes: "This update requires re-authenticating — sign in again in Settings."

That's the whole upgrade story. No `migrated_v1` sentinel, no legacy readers, no `codexVariant` helper, no dual-store bookkeeping.

## Components

### New
- `app/src/main/kotlin/ai/closepaw/auth/AuthStore.kt`
- `app/src/main/kotlin/ai/closepaw/auth/AuthCredential.kt`
- `app/src/main/kotlin/ai/closepaw/auth/AuthErrors.kt` (`MissingCredential`, `OAuthRefreshFailed`, `WrongCredentialType`)

### Changed — factory & catalog
- `app/src/main/assets/llm_models.json`
- `app/src/main/kotlin/ai/closepaw/llm/LLMProvider.kt` (+ enum rename + `mode` accessor)
- `app/src/main/kotlin/ai/closepaw/llm/LLMClientFactory.kt`
- `app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt` (token supplier)

### Changed — runtime wiring (review H1 + v2 M6)
- `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt` (route Settings open requests)
- `app/src/main/kotlin/ai/closepaw/app/MainActivityModelValidation.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt`
- `app/src/main/kotlin/ai/closepaw/app/MainActivityIntentPayload.kt`
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt` (schemaVersion 1→2; checkpoint reload rejects v1)
- `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt` (writes schemaVersion=2)
- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt`
- `app/src/main/kotlin/ai/closepaw/session/SessionLlmBootstrapper.kt`
- `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt` (banner → SettingsSheet deep-link plumbing)
- `app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt` (banner payload includes `openSettingsAt` action)
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt` (banner becomes tappable)

### Changed — settings & onboarding
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsState.kt` (delete 6 fields + `buildApiKeys`; add `openAiBaseUrlOverride`)
- `app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt` (persistence changes)
- `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingStore.kt` (delete `KEY_AUTH_METHOD`, `KEY_API_KEY_DRAFT`, encrypted prefs)
- `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt` (transient typed text; AuthStore writes)
- `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingDemoController.kt`
- `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt` (inline error card)
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt` (tab canonicalization)
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt` (+ `initialPage` / `initialAuthTab`)
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt`

### Removed
- `app/src/main/kotlin/ai/closepaw/auth/OAuthCredentialStore.kt`
- `AppSettingsState.{authMethod, openAiOAuthAccessToken, openAiManualApiKey, apiKey, openRouterApiKey, novitaApiKey, buildApiKeys}`
- `OnboardingStore.{KEY_AUTH_METHOD, KEY_API_KEY_DRAFT, securePrefs}`
- `LLMClientFactory.{apiKeyResolver, resolveApiKey, isOAuth}`
- `__AUTH_METHOD_OPENAI` constant

## Tasks

Each task: scope → acceptance criteria. Sequence reflects dependencies.

1. **auth-store-core** — `AuthCredential`, `AuthStore`, `AuthErrors` + JVM tests. AC: encrypted prefs get/set/clear/has; `codexHeaders()` refreshes near expiry under mutex; encryption-degraded memory fallback; typed errors on missing/wrong-type.
2. **provider-enum-split** — `LLMProvider`, `llm_models.json`. AC: enum compiles; catalog loads `OPENAI_API` + `OPENAI_CODEX` + `OPENROUTER` + `NOVITA` + `LOCAL_LFM`; `provider.mode` correct; `catalog.defaultModel(provider)` helper added.
3. **factory-rewrite** — `LLMClientFactory`, `CodexResponseClient` (header supplier constructor). AC: routes by flat provider; Codex client pulls full `CodexHeaders` per request via supplier (no cached OAuth state); generation-based cache invalidation for ApiKey providers + Codex on OAuth set/clear; typed errors propagated.
4. **runtime-wiring** — `MainActivity`, `MainActivityContent`, `MainActivityModelValidation`, `MainActivityIntentApplier`, `MainActivityIntentPayload`, `AgentSession` (schemaVersion 1→2 + reject v1), `SessionCheckpointCoordinator` (writes v2), `SessionServices`, `SessionLlmBootstrapper`, `ChatViewModel.reportStartupFailure` (banner payload carries `openSettingsAt(page=LLM_AUTH, tab=<mode>)` action), `ChatScreen` (banner → Settings route plumbing), `SmartCapsuleSurface` (banner becomes tappable). AC: all callsites that read legacy fields/env-vars resolve via `AuthStore`; main-flow session boot uses factory + AuthStore; v1 checkpoints refuse reload with user-visible message; startup failure banner tap opens `SettingsSheet` on the correct LLM auth tab; debug intents write to AuthStore (+ `openAiBaseUrlOverride` for base url).
5. **settings-ui-align** — `LlmAuthSettingsPage`, `SettingsSheet`, `SettingsHomePage`. AC: tabs drive `selectedProviderForTab` via canonicalization rule; switching tabs is inert until a model commit; **on commit, `executorModel` is reset/remapped if its provider no longer matches**; `SettingsSheet(initialPage, initialAuthTab)` deep-link works; existing androidTest `SettingsLlmAuthTest` updated and passing.
6. **onboarding-rewrite** — `OnboardingViewModel`, `OnboardingState` (align cloud provider enum with flat `LLMProvider`), `OnboardingStore` (key removal), `OnboardingDemoController`, `OnboardingSteps`, `OnboardingScreen`. AC: ApiKey tab text transient; OAuth success writes `AuthStore.OPENAI_CODEX`; step-resume derives mode from `selectedModel.provider.mode` + `AuthStore.has()`; Run Demo succeeds on device for both paths; `MissingCredential` / `OAuthRefreshFailed` → inline error card with re-auth action.
7. **settings-state-shrink** — `AppSettingsState`, `AppSettingsStore`. Runs **after** 5 and 6 have removed all legacy-field consumers. AC: legacy fields removed from state + prefs read/write; `openAiBaseUrlOverride` added; project compiles; no dangling refs.
8. **qa-device** — `/ux-visual-debug`. AC: fresh install OAuth flow → Run Demo succeeds; fresh install API key flow (OpenAI + OpenRouter — onboarding currently exposes these two) → Run Demo succeeds; Novita via Settings → chat Run succeeds; settings tab switch preserves credentials; upgrade from prior build → empty AuthStore → banner → tap → Settings → re-auth → Run Demo succeeds; v1 checkpoint refuses reload with correct message; startup-failure banner tap opens correct Settings tab.

**Dependency graph:**
```
1 auth-store-core
  └→ 2 provider-enum-split
       └→ 3 factory-rewrite
            └→ 4 runtime-wiring
                 ├→ 5 settings-ui-align ──┐
                 └→ 6 onboarding-rewrite ─┴→ 7 settings-state-shrink ──→ 8 qa-device
```
Rationale: task 7 deletes legacy fields, so it must run after 5 and 6 stop reading them.

## Test plan (consolidated)

- **AuthStore**: all providers get/set/clear/has; wrong-type clear; `codexHeaders()` refresh under mutex; account-switch invalidates cached headers; encryption-degraded memory fallback.
- **Factory**: each provider routes to right client; `CodexResponseClient` re-reads full headers per request via supplier (no cached access token or account id); ApiKey cache invalidates on `authStore.set` via generation counter; Codex cache invalidates on OAuth set/clear; typed errors for missing / refresh failure / wrong type.
- **Runtime**: `MainActivityModelValidation` validates main + executor via provider; `SessionLlmBootstrapper` no env-var leftover; debug intent injection writes to AuthStore; `AgentSession.reload` refuses v1-schema snapshots with correct user-visible message.
- **Settings UI**: tab switch inert; canonicalization prevents OAuth-provider-in-ApiKey-tab; **provider change resets/remaps `executorModel` when out-of-domain**; `SettingsSheet(initialAuthTab=OAUTH)` deep-link opens correct tab.
- **Onboarding**: OAuth success → AuthStore write; process-death mid-typing → user retypes (no encrypted draft); demo with missing credential → inline card; resume uses `selectedModel.provider.mode` + `AuthStore.has()`.
- **Device QA (`/ux-visual-debug`)**: OAuth + 2 onboarding providers × Run Demo; Novita via Settings; settings sign-in/out/tab-switch; **upgrade from prior build** → empty AuthStore → banner → Settings → re-auth → Run Demo succeeds; v1 checkpoint refuses reload; startup failure banner tap → correct Settings tab.

## Not in scope

- No local-backend refactor beyond enum alignment; `LLMBackendType.LOCAL` path stays.
- No fallback chain, catalog inheritance, or provider alias reintroductions.
- No onboarding funnel / settings IA redesign; 3-level hierarchy and "tab switch inert" contract preserved.

## Trade-offs

**Flat provider vs `(provider, authMode)` pair** — flat removes an axis at every callsite, UI gets grouping via `provider.mode` (1-line derivation). Cheaper.

**Single `AuthStore` vs per-provider stores** — single store is pi-mono's shape, one mutex policy. Per-provider = N files, N locks, no gain.

**Zero migration vs auto-migrate testers** — pre-release product. Writing a migration + associated idempotency/durability/crash-point test surface costs more engineering than asking testers to re-sign-in once. Upgrade becomes a one-liner in release notes. The mode→provider→model separation plus empty-AuthStore → banner → Settings re-auth path already produces this UX for free.

## Self-Review

- Original bug (Codex OAuth → Run Demo → missing `openai_api_key`): fixed by §5 OAuth flow; factory routes on provider, header supplier pulls from `AuthStore`.
- v1 H1 (runtime wiring) + v2 M6 (banner deep-link): §5 + task 4 cover runtime files + `MainActivityContent`, `ChatScreen`, `SmartCapsuleSurface` for the tappable banner.
- v1 H2 (all cloud providers): §2 — `OPENROUTER`/`NOVITA` credentials live in `AuthStore` alongside OpenAI.
- v1 H3 (client cache) + v2 M5 (OAuth identity cache): §3 full `CodexHeaders` supplier so `CodexResponseClient` caches no OAuth state; generation counter bumps on OAuth set/clear.
- v1 H4 + v2 H1/H2/H3 (migration complexity): **eliminated**. No migration code. Upgrade breaks cleanly; testers re-auth. See §8.
- v2 M4 (executor canonicalization in settings): §5 adds explicit executor reset/remap rule.
- v2 M6 (dep graph): reordered to `4 → {5,6} → 7 → 8` so field deletion runs after all consumers stop reading.
- OnboardingStore cleanup: §6 + task 6 (adds `OnboardingState`, `OnboardingScreen` to scope).
- Edge cases: encryption-degraded → in-memory same as today; revoked OAuth → refresh fails → banner → re-login; no-credential → banner → Settings; debug intent in release build → no-op; upgrade from prior build → empty AuthStore → banner → re-auth; v1 checkpoint → rejected cleanly.
- Anything simpler? Not without reintroducing a fallback chain or keeping dual credential storage.

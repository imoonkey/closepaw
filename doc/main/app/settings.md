# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-05-16 (Local LLM tab gated behind `LOCAL_TAB_ENABLED` flag; auto-prewarm on local-model selection)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (plain `SharedPreferences`). **Credentials live in a separate `AuthStore`** (`EncryptedSharedPreferences`, app-scoped singleton via `AuthStoreHolder`) keyed by flat `LLMProvider` — not in `AppSettingsState`. If `EncryptedSharedPreferences` init throws (e.g. broken Keystore), the exception bubbles up — no silent in-memory fallback. JVM tests inject a fake via `AuthStore`'s `prefsProvider` constructor parameter.

> See: [infra/llm.md](../infra/llm.md) for the flat `LLMProvider` enum and `AuthStore` integration with the factory.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | `OPENAI` (Cloud) or `LOCAL` (On-device) |
| `selectedModel` | `String` | `"glm-5"` | Main agent model, resolved via `ModelCatalog` (carries flat `LLMProvider`) |
| `subagentModel` | `String?` | `null` | Delegated subagent model (falls back to main; canonicalized to selectedModel.provider on commit) |
| `localModel` | `LocalModelOption` | `AVAILABLE_LOCAL_MODELS.first()` | Local model selection (id-only persistence; rehydrated via catalog lookup) |
| `openaiBaseUrl` | `String` | `""` | Base URL override for OpenAI provider (set via `openai_base_url` intent extra; persisted in SharedPreferences so it survives process restarts) |

**Credentials are NOT in `AppSettingsState`.** They live in `AuthStore`, keyed by flat `LLMProvider` (`OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`). The selected model encodes the provider, which determines exactly which credential is loaded and which client class runs — no fallback chains, no `__AUTH_METHOD` signal keys.

### Execution

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `debugMode` | `Boolean` | `false` | Verbose logging + debug artifacts |
| `browserScriptEnabled` | `Boolean` | `false` | Enables experimental `browser_script` execution gate |
| `termuxShellEnabled` | `Boolean` | `true` | Allows `termux_shell` exposure when Termux is installed and bridge-ready |

There is no Max Turns setting. Production runs are bounded by context-window
auto-compaction (see [agent/loop.md](../agent/loop.md#auto-compaction)), not by a
turn count. The eval bridge has its own `eval_turn_budget` safety net wired
through `SessionConfig.evalTurnBudget` (`null` in production).

### Platform

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `platformMode` | `PlatformMode` | `ACCESSIBILITY` | `ACCESSIBILITY` (standard) or `VIRTUAL_DISPLAY` (Shizuku-based) |

> See: [infra/platform.md](../infra/platform.md) for `VirtualDisplayPlatform` and `PlatformFactory` details.

### Perception

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `perceptionMode` | `String` | `"accessibility_only"` | One of: `accessibility_only`, `screenshot_only`, `hybrid` |

> See: [infra/platform.md](../infra/platform.md) for `PerceptionConfig` variants and capture behavior.

---

## SessionConfig Compilation

> See: `protocol/SessionConfig.kt`

Settings are compiled into `SessionConfig` when creating a session:

```kotlin
data class SessionConfig(
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val llm: SessionLlmConfig = SessionLlmConfig(),
    val mainModel: String = "glm-5",
    val subagentModel: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    val evalTurnBudget: Int? = null,   // eval-only safety net
    // ...
)
```

- `perceptionConfig` is built from `perceptionMode` string: `accessibility_only` → `AccessibilityOnly`, `screenshot_only` → `ScreenshotOnly`, `hybrid` → `Hybrid`
- `subagentModel` falls back to `mainModel` when null
- `platformMode` selects `AndroidPlatform` implementation via `PlatformFactory`

---

## Settings UI

> See: `ui/settings/SettingsSheet.kt`

The settings UI is a full-screen page overlay with sub-page navigation (system back: sub-page → HOME, HOME → dismiss):

| Section | Contents |
|---------|----------|
| LLM Backend | Cloud/Local toggle |
| Cloud Model | Main model dropdown (visible when Cloud) |
| Subagent Model | Optional subagent model dropdown (visible when Cloud) |
| Local Model | Model selection + loading status indicator (visible when Local) |
| Termux Shell | Enable toggle plus install/setup/restart state row for `termux_shell` |
| Perception Mode | 3-button toggle: Accessibility Only, Hybrid, Screenshot Only |
| API Keys | LLM & Authentication page (provider → model hierarchy: Sign In / API Key tabs; Local tab hidden behind `LOCAL_TAB_ENABLED` const) — see Auth Section below |
| Permissions | Accessibility Service + Overlay permission status indicators |
| Permissions & Advanced | Platform mode, debug mode, browser automation experimental toggle, trace/data controls, app version |
| About → Open Source Licenses | Lists all runtime deps + their licenses, generated by `com.jaredsburrows.license` plugin into `assets/open_source_licenses.json` and rendered by `OpenSourceLicensesPage`. Leap SDK shows as proprietary "Leap Terms of Use" — see `NOTICE` |

### Local Model Loading Status

| Status | UI |
|--------|----|
| `Idle` | No indicator |
| `Downloading(progress)` | Progress bar with percentage |
| `Loading` | Indeterminate progress |
| `Ready` | Green checkmark |
| `Error(message)` | Red error text |

`ModelLoadingStatusHolder` (`app/ModelLoadingStatusHolder.kt`) owns this status outside Compose. Picking a model in the (currently hidden) Local tab calls `updateLocalModel(model)`, which kicks off a background prewarm: a disposable `LFMLLMClient` runs `loadModel` to warm the on-disk Leap cache, then `cleanup()` releases the runner so tensor memory isn't held until the real session boots. A monotonic generation token gates every status write so stale callbacks from a cancelled prewarm can't clobber a newer selection or the real session's status. Cancellation rethrows `CancellationException`; cleanup runs under `NonCancellable`. The "already warmed" short-circuit keys on `modelSlug/quantizationSlug` so different quants of the same slug re-download.

The Local tab itself is hidden by `LOCAL_TAB_ENABLED = false` in `LlmAuthSettingsPage.kt`. LFM 1.2B Q4 on phone CPU takes 1–3 min to emit the first tool call against the 12-tool agent schema — works but unusable. The rest of the local stack (`LFMLLMClient`, prewarm, `LocalTabContent`, `AVAILABLE_LOCAL_MODELS`) stays wired; flip the flag to one line re-expose. A `LaunchedEffect` repairs a process-restored `selectedTab = LOCAL` to `API_KEY` when the flag is off, so saved state can't bypass the gate.

### Settings Files

```
ui/settings/
├── SettingsSheet.kt         # Main composable (full-screen page + BackHandler)
├── SettingsModels.kt        # Data models (LocalModelOption, ModelLoadingStatus)
├── SettingsDropdowns.kt     # Backend/model dropdowns
├── SettingsDropdown.kt      # Generic reusable dropdown composable
├── SettingsWidgets.kt       # Shared widgets (Header, Section, Row, StatusIndicator)
└── ApiKeyFields.kt          # API key input fields (masked + visibility toggle)
```

---

## Persistence

`AppSettingsState` (non-secret) is persisted in plain `SharedPreferences` via `AppSettingsStore`. Credentials are persisted separately in `AuthStore` (`EncryptedSharedPreferences`).

`AppSettingsStore` keys:
- `llm_backend` — `OPENAI` or `LOCAL`
- `debug_mode` — boolean
- `trace_enabled` — boolean
- `browser_script_enabled` — boolean, gates `browser_script` before Shizuku/Chrome preflight
- `termux_shell_enabled` — boolean, gates `termux_shell` exposure at the next session snapshot
- `perception_mode` — `accessibility_only`, `screenshot_only`, `hybrid`
- `platform_mode` — `ACCESSIBILITY`, `VIRTUAL_DISPLAY`
- `model` — model name string

`openaiBaseUrl` is transient state set from the `openai_base_url` intent extra; it is not persisted.

The browser automation toggle lives under **Permissions & Advanced → Experimental**. Turning it on
only permits the runtime capability gate to continue; `browser_script` still requires Shizuku
availability, Shizuku permission, Chrome DevTools socket preflight, and the normal tool policy
approval flow.

The Termux Shell row lives under **Agent Behavior → Execution**. The row can render
`NotInstalled`, `NeedsSetup(reason)`, `SetupInProgress`, `Ready`, or `Disabled`; full bootstrap runs
only from an explicit row action. Setting changes apply to the next session because
`SessionServices.create(...)` captures an immutable Termux capability snapshot.

→ See: [termux_shell.md](termux_shell.md) for setup states, lifecycle invariants, and OEM limits.

`AuthStore` keys (`EncryptedSharedPreferences`, file `auth_store.xml`):
- One entry per `LLMProvider.name` (e.g. `OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`), value is a JSON-encoded `AuthCredential` (`ApiKey` or `OAuth`).
- Per-provider generation counter for cache invalidation in `LLMClientFactory`.

### Security

- Credentials are persisted only in `EncryptedSharedPreferences` (AES256-GCM via `MasterKey`). No plaintext fallback file. If KeyStore init throws, the exception propagates — `AuthStore` does not silently degrade to memory. Caller decides whether to surface a "secure storage unavailable" error. JVM tests inject `FakeSharedPreferences` via the `prefsProvider` constructor parameter.
- API keys are masked in UI input fields and not emitted in normal debug logs.
- `CodexResponseClient` never captures OAuth state; every request reads fresh `CodexHeaders` from `AuthStore.codexHeaders()` (mutex-guarded refresh near 5-min expiry).

---

## Onboarding

> See: `onboarding/` package, `ui/onboarding/` package

First-launch onboarding wizard gates chat behind required permissions and a validated API key. Steps: Accessibility (hard) → Overlay (hard) → Battery (soft/skippable) → API Key (validated) → Demo → Complete.

- `OnboardingStore` manages its own prefs file (`onboarding_prefs`), separate from settings. Schema v2 — legacy `auth_method` and encrypted `api_key_draft` keys removed; auth state derives from `selectedModel.provider.mode + AuthStore.has(provider)`.
- Typed API-key text in onboarding is ViewModel-transient (no encrypted draft persistence) — process death means re-type.
- Legacy users detected via AuthStore presence, session history, or non-default settings → auto-skip onboarding
- Eval/debug bypass: `EXTRA_FRESH_SESSION + EXTRA_GOAL` intent skips onboarding
- Post-onboarding: `PermissionRepairCard` shows targeted repair if a permission is later revoked
- API-key validator base URL resolution mirrors `LLMClientFactory.build()` — for `OPENAI_API` entries, `AppSettingsState.openaiBaseUrl` (set from intent extra `openai_base_url`, debug-only) wins over `entry.effectiveBaseUrl`. Required so debug builds talking to the in-house proxy validate forward-versioned mock model IDs (`gpt-5.4`, etc.) instead of hitting `api.openai.com`.

---

## Settings Deep-Link

> See: `ui/chat/SettingsDeepLink.kt`, `app/MainActivityContent.kt`, `app/MainActivity.kt`

Two paths open Settings with a target page/tab pre-selected:

1. **Banner tap** — session bootstrap throws `MissingCredential` / `OAuthRefreshFailed` / `WrongCredentialType` → `ChatViewModel.reportStartupFailure(deepLink)` stores the link in `_startupErrorDeepLink` → tapping the banner calls `onOpenSettings(viewModel.startupErrorDeepLink.value)`.
2. **Pre-flight check** — `MainActivity.validateCloudKeysForSelectedModels()` runs before send; on missing credentials, it sets `pendingSettingsDeepLink` (passed as `initialSettingsDeepLink` to `MainActivityContent`) and flips `showSettings = true`.

Both paths converge on `MainActivityContent.pendingDeepLink`, forwarded as `initialPage` / `initialAuthTab` to `SettingsSheet` → `LlmAuthSettingsPage`. The auth tab is derived from the missing provider's `mode` (OAuth → Sign In, ApiKey → API Key).

---

## Related Docs

- [Protocol](../protocol/overview.md) - `SessionConfig` contract
- [Session](../infra/session.md) - Runtime wiring of config
- [LLM](../infra/llm.md) - Model catalog and client factory
- [UI Tech Design](../ui/tech_design.md) - Settings UI components

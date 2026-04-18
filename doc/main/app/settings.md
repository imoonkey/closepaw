# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-04-18 (auth-setting-cleanup: credentials moved to AuthStore)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (plain `SharedPreferences`). **Credentials live in a separate `AuthStore`** (`EncryptedSharedPreferences`, app-scoped singleton via `AuthStoreHolder`) keyed by flat `LLMProvider` — not in `AppSettingsState`. If encryption is unavailable, `AuthStore` falls back to in-memory storage for the current process.

> See: [infra/llm.md](../infra/llm.md) for the flat `LLMProvider` enum and `AuthStore` integration with the factory.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | `OPENAI` (Cloud) or `LOCAL` (On-device) |
| `selectedModel` | `String` | `"glm-5"` | Main agent model, resolved via `ModelCatalog` (carries flat `LLMProvider`) |
| `executorModel` | `String?` | `null` | Executor agent model (PRO mode override; canonicalized to selectedModel.provider on commit) |
| `selectedLocalModel` | `String` | `"LFM2.5-1.2B-Instruct"` | Local model selection |
| `openaiBaseUrl` | `String` | `""` | Debug-only base URL override (set via `openai_base_url` intent) |

**Credentials are NOT in `AppSettingsState`.** They live in `AuthStore`, keyed by flat `LLMProvider` (`OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`). The selected model encodes the provider, which determines exactly which credential is loaded and which client class runs — no fallback chains, no `__AUTH_METHOD` signal keys.

### Execution

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `agentMode` | `AgentMode` | `PRO` | Main-agent orchestration mode (`BASIC` or `PRO`) |
| `maxTurns` | `Int` | `20` | Max turns per task (UI default; protocol default is 50) |
| `debugMode` | `Boolean` | `false` | Verbose logging + debug artifacts |

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
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val agentMode: AgentMode = AgentMode.PRO,
    val llm: SessionLlmConfig,
    val mainModel: String = "glm-5",
    val executorModel: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    // ...
)
```

- `perceptionConfig` is built from `perceptionMode` string: `accessibility_only` → `AccessibilityOnly`, `screenshot_only` → `ScreenshotOnly`, `hybrid` → `Hybrid`
- `executorModel` falls back to `mainModel` when null (resolved at runtime by `AgentModelResolver`)
- `platformMode` selects `AndroidPlatform` implementation via `PlatformFactory`

---

## Agent Modes

| Mode | Behavior |
|------|----------|
| `BASIC` | Standalone main agent executes UI tools directly |
| `PRO` | Planner main agent delegates grounded steps to executor via `delegate_task` |

Mode can be set from:
- Settings UI (`Execution Mode` dropdown)
- Activity intent extra `agent_mode`
- Dev scripts (`--basic` / `--pro`, or `AGENT_MODE` env var)

---

## Settings UI

> See: `ui/settings/SettingsSheet.kt`

The settings sheet is a modal bottom sheet with sectioned layout:

| Section | Contents |
|---------|----------|
| LLM Backend | Cloud/Local toggle |
| Cloud Model | Main model dropdown (visible when Cloud) |
| Executor Model | Executor model dropdown (visible when PRO mode + Cloud) |
| Local Model | Model selection + loading status indicator (visible when Local) |
| Max Turns | Dropdown: 10, 20, 50 |
| Execution Mode | Basic/Pro dropdown (`AgentModeDropdown`) |
| Perception Mode | 3-button toggle: Accessibility Only, Hybrid, Screenshot Only |
| API Keys | LLM & Authentication page (mode → provider → model hierarchy: Sign In / API Key / Local tabs) — see Auth Section below |
| Permissions | Accessibility Service + Overlay permission status indicators |
| About & Debug | App version + debug mode switch |

### Local Model Loading Status

| Status | UI |
|--------|----|
| `Idle` | No indicator |
| `Downloading(progress)` | Progress bar with percentage |
| `Loading` | Indeterminate progress |
| `Ready` | Green checkmark |
| `Error(message)` | Red error text |

### Settings Files

```
ui/settings/
├── SettingsSheet.kt         # Main composable (modal bottom sheet)
├── SettingsModels.kt        # Data models (LocalModelOption, ModelLoadingStatus)
├── SettingsDropdowns.kt     # Backend/model/mode/turns dropdowns
├── SettingsDropdown.kt      # Generic reusable dropdown composable
├── SettingsWidgets.kt       # Shared widgets (Header, Section, Row, StatusIndicator)
└── ApiKeyFields.kt          # API key input fields (masked + visibility toggle)
```

---

## Persistence

`AppSettingsState` (non-secret) is persisted in plain `SharedPreferences` via `AppSettingsStore`. Credentials are persisted separately in `AuthStore` (`EncryptedSharedPreferences`).

`AppSettingsStore` keys:
- `agent_mode` — `BASIC` or `PRO`
- `llm_backend` — `OPENAI` or `LOCAL`
- `max_turns` — integer
- `debug_mode` — boolean
- `perception_mode` — `accessibility_only`, `screenshot_only`, `hybrid`
- `platform_mode` — `ACCESSIBILITY`, `VIRTUAL_DISPLAY`
- `main_model` / `executor_model` — model name strings
- `openai_base_url` — debug-only proxy override

`AuthStore` keys (`EncryptedSharedPreferences`, file `auth_store.xml`):
- One entry per `LLMProvider.name` (e.g. `OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`), value is a JSON-encoded `AuthCredential` (`ApiKey` or `OAuth`).
- Per-provider generation counter for cache invalidation in `LLMClientFactory`.

### Security

- Credentials are persisted only in `EncryptedSharedPreferences` (AES256-GCM via `MasterKey`). No plaintext fallback file. If KeyStore is unavailable, `AuthStore` falls back to in-memory storage for the current process — credentials are lost on restart.
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

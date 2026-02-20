# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-02-20 (commit: 2493be6)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (SharedPreferences-backed), and compiles them into immutable `SessionConfig` when starting a task.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | `OPENAI` (Cloud: OpenAI/OpenRouter/Novita) or `LOCAL` (On-device) |
| `model` | `String` | `"glm-5"` | Main agent model (cloud), resolved via `ModelCatalog` |
| `executorModel` | `String?` | `null` | Executor agent model (cloud, optional override — falls back to main) |
| `localModel` | `String` | `"LFM2.5-1.2B-Instruct"` | Local model selection |
| `openAiApiKey` | `String` | `""` | API key for OpenAI |
| `openRouterApiKey` | `String` | `""` | API key for OpenRouter |
| `novitaApiKey` | `String` | `""` | API key for Novita |

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
| API Keys | OpenAI, OpenRouter, Novita key fields (password masked, toggle visibility) |
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

Settings are persisted in SharedPreferences via `AppSettingsStore`.

Key preference keys:
- `agent_mode` — `BASIC` or `PRO`
- `llm_backend` — `OPENAI` or `LOCAL`
- `max_turns` — integer
- `debug_mode` — boolean
- `perception_mode` — `accessibility_only`, `screenshot_only`, `hybrid`
- `platform_mode` — `ACCESSIBILITY`, `VIRTUAL_DISPLAY`
- `main_model` — model name string
- `executor_model` — model name string (nullable)
- `api_key` / `openrouter_api_key` / `novita_api_key` — provider API keys

### Security

- API keys are persisted in app `SharedPreferences` (not encrypted by `AppSettingsStore`)
- Keys are masked in UI input fields and not emitted in normal debug logs
- Optional legacy bootstrap: if `api_key` is empty, app can import `/sdcard/api_key.txt` once

---

## Related Docs

- [Protocol](../protocol/protocol.md) - `SessionConfig` contract
- [Session](../infra/session.md) - Runtime wiring of config
- [LLM](../infra/llm.md) - Model catalog and client factory
- [UI Tech Design](../ui/tech_design.md) - Settings UI components

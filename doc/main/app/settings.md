# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-02-10 (commit: 04cecbd)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (SharedPreferences-backed), and compiles them into immutable `SessionConfig` when starting a task.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | `OPENAI` (Cloud: OpenAI/OpenRouter/Novita) or `LOCAL` (On-device) |
| `model` | `String` | `"gpt-5.2"` | Main agent model (cloud) |
| `executorModel` | `String` | `null` | Executor agent model (cloud, optional override) |
| `localModel` | `String` | `"LFM2.5-1.2B-Instruct"` | Local model selection |
| `openAiApiKey` | `String` | `""` | API key for OpenAI |
| `openRouterApiKey` | `String` | `""` | API key for OpenRouter |
| `novitaApiKey` | `String` | `""` | API key for Novita |

### Execution

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `agentMode` | `AgentMode` | `PRO` | Main-agent orchestration mode (`BASIC` or `PRO`) |
| `maxTurns` | `Int` | `20` | Max turns per task (UI setting default) |
| `debugMode` | `Boolean` | `false` | Verbose logging + debug artifacts |

### Platform

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `platformMode` | `PlatformMode` | `ACCESSIBILITY` | `ACCESSIBILITY` (standard) or `VIRTUAL_DISPLAY` (Shizuku-based, runs apps on a virtual display) |

→ See: [infra/platform.md](../infra/platform.md) for `VirtualDisplayPlatform` and `PlatformFactory` details.

### Perception

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `perceptionMode` | `String` | `"accessibility_only"` | One of: `accessibility_only`, `screenshot_only`, `hybrid` |

→ See: [infra/platform.md](../infra/platform.md) for `PerceptionConfig` variants and capture behavior.

---

## SessionConfig

→ See: `protocol/Op.kt`

Settings are compiled into `SessionConfig` when creating a session:

```kotlin
data class SessionConfig(
    // ...
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
)
```

`SessionConfig.maxTurns` has a protocol default of `50`, while UI settings currently initialize the user-facing value to `20`. `perceptionConfig` is built from `perceptionMode` when creating a session (`accessibility_only` → `AccessibilityOnly`, etc.). `platformMode` controls which `AndroidPlatform` implementation is used (via `PlatformFactory`).

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

→ See: `ui/settings/SettingsSheet.kt`

The settings sheet is a modal bottom sheet with:
- sectioned layout (LLM, Execution, Perception, Debug, Permissions)
- backend/model selectors
- execution mode selector (`AgentModeDropdown`)
- max-turn selector
- perception mode selector (`PerceptionModeSelector`): Accessibility Only, Screenshot Only, Hybrid (A11y + Screenshot)
- debug toggle
- permission status indicators (Accessibility, Overlay)

### Settings Files

```
ui/settings/
├── SettingsSheet.kt         # Main composable
├── SettingsModels.kt        # Data models + defaults
├── SettingsDropdowns.kt     # Backend/model/mode dropdowns
└── SettingsWidgets.kt       # Shared UI widgets
```

---

## Persistence

Settings are persisted in SharedPreferences via `AppSettingsStore`.

Key examples:
- `agent_mode`
- `llm_backend`
- `max_turns`
- `debug_mode`
- `perception_mode` (values: `accessibility_only`, `screenshot_only`, `hybrid`; migrated from legacy `screenshot_input` boolean)
- `platform_mode` (values: `ACCESSIBILITY`, `VIRTUAL_DISPLAY`)

### Security

- API key is stored using encrypted preference handling in app code paths.
- Avoid logging secrets; keep sensitive output out of debug logs.

---

## Local Model Management

When `llmBackend = LOCAL`:

| State | Description |
|-------|-------------|
| `NOT_DOWNLOADED` | Model not available |
| `DOWNLOADING` | Download in progress |
| `READY` | Model loaded and ready |
| `ERROR` | Download or load failed |

→ See: [infra/llm.md](../infra/llm.md) for LFM client details

---

## Related Docs

- [Protocol](../protocol/protocol.md) - `SessionConfig` contract
- [Session](../infra/session.md) - Runtime wiring of config
- [UI Tech Design](../ui/tech_design.md) - Settings UI components

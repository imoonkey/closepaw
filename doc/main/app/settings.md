# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-02-05 (commit: af73f8a3ac945a077707b2774adc8f6ac8221c5e)

## Overview

The app manages user preferences through `AppSettingsState` + `AppSettingsStore` (SharedPreferences-backed), and compiles them into immutable `SessionConfig` when starting a task.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | Cloud or local inference |
| `model` | `String` | `"gpt-5.2"` | Cloud model name |
| `localModel` | `String` | `"LFM2.5-1.2B-Instruct"` | Local model selection |
| `apiKey` | `String` | `""` | OpenAI API key |

### Execution

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `agentMode` | `AgentMode` | `PRO` | Main-agent orchestration mode (`BASIC` or `PRO`) |
| `maxTurns` | `Int` | `20` | Max turns per task (UI setting default) |
| `debugMode` | `Boolean` | `false` | Verbose logging + debug artifacts |

### Perception

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `enableScreenshotInput` | `Boolean` | `false` | Attach screenshots to perception |
| `screenshotMaxDimension` | `Int` | `1024` | Max long edge dimension |
| `screenshotJpegQuality` | `Int` | `70` | JPEG quality (0-100) |

---

## SessionConfig

→ See: `protocol/Op.kt`

Settings are compiled into `SessionConfig` when creating a session:

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val model: String = "gpt-5.2",
    val llmBackend: LLMBackendType = LLMBackendType.OPENAI,
    val agentMode: AgentMode = AgentMode.PRO,
    val localLLMConfig: LocalLLMConfig? = null,
    val debugMode: Boolean = false,
    val enableScreenshotInput: Boolean = false,
    val screenshotMaxDimension: Int = 1024,
    val screenshotJpegQuality: Int = 70
)
```

`SessionConfig.maxTurns` has a protocol default of `50`, while UI settings currently initialize the user-facing value to `20`.

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
- screenshot input and debug toggles
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
- `screenshot_input`

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

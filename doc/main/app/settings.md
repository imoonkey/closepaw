# Settings & Configuration

> User settings, preferences, and configuration persistence.
> Last updated: 2026-02-06

## Overview

The app manages user preferences through Android's SharedPreferences with type-safe accessors. Settings are organized into categories matching the Settings UI.

---

## Settings Categories

### LLM Backend

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `llmBackend` | `LLMBackendType` | `OPENAI` | Cloud or local inference |
| `model` | `String` | `"gpt-5.2"` | Cloud model name |
| `localModel` | `String` | `"lfm-1.2b"` | Local model selection |
| `apiKey` | `String` | `""` | OpenAI API key (encrypted) |

### Agent Behavior

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `maxTurns` | `Int` | `50` | Maximum turns per task |
| `actionDelayMs` | `Long` | `2000` | Delay after actions (UI settle) |
| `approvalMode` | `ApprovalMode` | `SMART` | Tool approval strategy |

### Perception

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `enableScreenshotInput` | `Boolean` | `false` | Attach screenshots to perception |
| `screenshotMaxDimension` | `Int` | `1024` | Max long edge dimension |
| `screenshotJpegQuality` | `Int` | `70` | JPEG quality (0-100) |

### Debug

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `debugMode` | `Boolean` | `false` | Verbose logging |

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
    val localLLMConfig: LocalLLMConfig? = null,
    val debugMode: Boolean = false,
    val enableScreenshotInput: Boolean = false,
    val screenshotMaxDimension: Int = 1024,
    val screenshotJpegQuality: Int = 70
)
```

---

## Approval Modes

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Prompt user before every tool |
| `AUTO_APPROVE` | Auto-approve all tools |
| `SMART` | Auto-approve low-risk, ask for high-risk |

---

## Settings UI

→ See: `ui/settings/SettingsSheet.kt`

The settings sheet is a modal bottom sheet with:
- Custom header (title + close button, no drag handle)
- Sectioned layout (LLM, Behavior, Perception, Debug, Permissions)
- Real-time validation (API key format, model availability)
- Permission status indicators (Accessibility, Overlay)

### Settings Files

```
ui/settings/
├── AppSettingsState.kt      # Mutable UI-backed settings state
├── AppSettingsStore.kt      # SharedPreferences persistence
├── SettingsSheet.kt         # Main composable
├── SettingsModels.kt        # Data models + defaults
├── SettingsDropdowns.kt     # Model/backend dropdowns
└── SettingsWidgets.kt       # Shared UI widgets
```

---

## Persistence

Settings are persisted using Android SharedPreferences:

```kotlin
// In MainActivity or SettingsManager
private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)

// Read
val maxTurns = prefs.getInt("max_turns", 50)

// Write
prefs.edit { putInt("max_turns", newValue) }
```

### Security

- API key stored with Android EncryptedSharedPreferences
- No sensitive data in logs when `debugMode = false`

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

- [Protocol](../protocol/protocol.md) - SessionConfig in session creation
- [LLM](../infra/llm.md) - LLM backend details
- [UI Tech Design](../ui/tech_design.md) - Settings UI components

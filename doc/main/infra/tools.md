# Tool System

> ToolRegistry, ToolRouter, and tool execution lifecycle.
> Last updated: 2026-02-04

## Overview

Tools are the agent's interface to the Android device. Every tool execution follows a state machine lifecycle with validation, policy checking, and observation capture.

---

## Tool Execution Lifecycle

```
┌──────────────┐
│  VALIDATING  │ ─── Invalid ──► ERROR
└──────┬───────┘
       │ Valid
       ▼
┌──────────────┐
│ POLICY_CHECK │ ─── Deny ────► ERROR
└──────┬───────┘
       │ Allow or AskUser
       ▼
┌──────────────┐
│   AWAITING   │ ─── Denied/Timeout ──► CANCELLED
│   APPROVAL   │
└──────┬───────┘
       │ Approved
       ▼
┌──────────────┐
│  EXECUTING   │ ─── Exception ──► ERROR
└──────┬───────┘
       │ Success
       ▼
┌──────────────┐
│   SUCCESS    │
└──────────────┘
```

---

## Core Components

### ToolRegistry

→ See: `tool/ToolRegistry.kt`

Discovery and schema generation:

```kotlin
class ToolRegistry {
    fun register(tool: ToolSpec)
    fun get(name: String): ToolSpec?
    fun getAll(): List<ToolSpec>
    fun generateResponsesApiTools(): List<FunctionTool>
}
```

### ToolRouter

→ See: `tool/ToolRouter.kt`

Executes tool calls with state machine lifecycle:
- Validates tool exists and parameters are correct
- Checks policy for approval requirements
- Waits for user approval if needed (60s timeout)
- Executes tool and returns result

### PolicyEngine

→ See: `tool/PolicyEngine.kt`

Determines whether tools need user approval:

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Prompt user before every tool |
| `AUTO_APPROVE` | Never ask, auto-approve all |
| `SMART` | Auto-approve low-risk, ask for high-risk |

---

## Built-in Tools

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `mobile_action` | UI interactions | `action`, `element_index`, `resource_id`, `text`, `x`, `y`, `direction` |
| `app_control` | App discovery/launch | `action` (`list_apps`, `open_app`), `package_name` |
| `complete_task` | Signal completion | `status`, `answer` |
| `write_todos` | Todo list management | `todos` array |
| `scratchpad` | Key-value memory | `action`, `key`, `value` |
| `delegate_task` | Sub-agent delegation | `agent_name`, `query` |

### mobile_action Actions

| Action | Description |
|--------|-------------|
| `click` | Tap on element |
| `long_press` | Long tap |
| `type` | Input text |
| `swipe` | Direction or coordinate-based swipe |
| `system_button` | back, home, enter, recents |
| `wait` | Pause execution |

### Targeting

Tools use multi-selector fallback for targeting:
1. Bounds (x, y coordinates)
2. `resource_id` + `resource_id_index`
3. `text` + `text_index`
4. `element_index`

---

## Tool Abstraction Hierarchy

```
ToolSpec (interface)
├── BaseTool (abstract) - Single-action UI tools
└── MultiActionTool (abstract) - Action dispatch
    └── ActionHandler - Per-action validation + invocation
```

### Tool Invocation Types

| Type | Purpose |
|------|---------|
| `UIActionInvocation` | UIAction-backed tool invocation |
| `ClickTargetInvocation` | Click with multi-selector fallback |
| `SwipeTargetInvocation` | Direction-based swipe with targeting |
| `DataQueryInvocation` | Data-only (no UI action) |

---

## Tool Observation

Every successful tool execution captures post-action screen state:

```kotlin
// 300ms delay for UI settle, then capture
private suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? {
    delay(UI_SETTLE_DELAY_MS)
    val snapshot = context.platform.captureScreen()
    val tree = Perceptor.toPromptJson(snapshot)
    return ToolObservation.ScreenState(tree, snapshot.elements.size, snapshot)
}
```

---

## Adding New Tools

1. Implement `ToolSpec` in `tool/impl/`
   - For single UI actions: extend `BaseTool`
   - For grouped actions: extend `MultiActionTool`

2. Implement required members:
   - `name`, `description`, `parameterSchema`
   - `validate(params)`, `createInvocation(params)`

3. Register in `SessionServices.registerBuiltInTools()`

### Example

```kotlin
class PingTool : ToolSpec {
    override val name = "ping"
    override val description = "Return a health-check response"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
    }

    override fun validate(params: JSONObject) = ValidationResult.Valid

    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "Health check"
        override suspend fun execute(context: ToolExecutionContext) =
            ToolExecutionResult.Success(output = "pong")
    }
}
```

---

## File Structure

```
tool/
├── ToolSpec.kt               # Tool interface + types
├── ToolCallState.kt          # State definitions
├── ToolCallResult.kt         # Result types
├── BaseTool.kt               # Single-action UI tools
├── MultiActionTool.kt        # Action dispatch
├── ToolRegistry.kt           # Discovery/registration
├── ToolRouter.kt             # Execution state machine
├── PolicyEngine.kt           # Approval logic
├── handlers/
│   ├── ActionHandler.kt
│   ├── ClickTargetInvocation.kt
│   ├── SwipeTargetInvocation.kt
│   ├── UIActionInvocation.kt
│   └── DataQueryInvocation.kt
└── impl/
    ├── MobileActionTool.kt
    ├── AppControlTool.kt
    ├── CompleteTaskTool.kt
    ├── WriteTodosTool.kt
    ├── ScratchpadTool.kt
    └── DelegateTaskTool.kt
```

---

## Related Docs

- [Session](session.md) - SessionServices registration
- [Platform](platform.md) - AndroidPlatform execution
- [Protocol](../protocol/protocol.md) - Action events
- [Planning](../agent/planning.md) - Planning tools

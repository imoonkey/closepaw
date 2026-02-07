# Tool System

> ToolRegistry, ToolRouter, and tool execution lifecycle.
> Last updated: 2026-02-04 (commit: 767f577844825c4db4d8d30dc2084b94d44737a2)

## Overview

Tools are the agent's interface to the Android device. Every tool execution follows a state machine lifecycle with validation, policy checks, and observation capture.

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

Executes tool calls with lifecycle handling:
- Validates tool exists and parameters are correct
- Checks policy for approval requirements
- Waits for user approval if needed (60s timeout)
- Executes tool and returns result

### PolicyEngine

→ See: `tool/PolicyEngine.kt`

Determines whether tools need user approval.

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Prompt user before every tool |
| `AUTO_APPROVE` | Never ask, auto-approve all |
| `SMART` | Auto-approve low-risk, ask for high-risk |

---

## Built-in Tools

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `mobile_action` | Screen-targeted touch interactions | `action`, selectors (`element_index`, `text`, bounds, coordinates) |
| `open_app` | Launch app by name | `app_name` |
| `system_button` | Press Android system key | `button` (`back`, `home`, `enter`, `recents`) |
| `wait` | Pause for UI settle | `duration_ms` |
| `complete_task` | Signal completion | `status`, `answer` |
| `write_todos` | Todo list management | `todos` array |
| `scratchpad` | Key-value memory | `action`, `key`, `value` |
| `delegate_task` | Sub-agent delegation (PRO mode) | `agent_name`, `query` |

`delegate_task` is registered lazily only when the selected agent definition requires delegation.

### mobile_action Actions

| Action | Description |
|--------|-------------|
| `click` | Tap target |
| `long_press` | Long tap target |
| `type` | Type into focused or targeted field (`input_text`) |
| `swipe` | Directional or coordinate swipe |

### Targeting Order

For `click`, `long_press`, and `type`, fallback order is:
1. Bounds (`x1`, `y1`, `x2`, `y2`)
2. Coordinates (`x`, `y`)
3. Text selector (`text` + optional `text_index`)
4. Element selector (`element_index`)

→ See: `tool/handlers/MultiSelectorTargeting.kt`

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
| `SwipeTargetInvocation` | Direction-based swipe with optional selector grounding |
| `TypeTargetInvocation` | Type with optional focus targeting |

---

## Tool Observation

Successful tool execution can include post-action screen context:

```kotlin
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

3. Register in `SessionServices.registerBuiltInTools()` (or conditional runtime wiring)

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
│   ├── TypeTargetInvocation.kt
│   └── UIActionInvocation.kt
└── impl/
    ├── MobileActionTool.kt
    ├── OpenAppTool.kt
    ├── SystemButtonTool.kt
    ├── WaitTool.kt
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

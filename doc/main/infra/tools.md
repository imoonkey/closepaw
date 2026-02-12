# Tool System

> ToolRegistry, ToolRouter, and tool execution lifecycle.
> Last updated: 2026-02-12 (Smart Capsule V2: ask_user tool)

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
| `mobile_action` | Screen-targeted touch interactions | `action`, targeting (`element_index`, `text`, coordinates) |
| `open_app` | Launch app by name | `app_name` |
| `system_button` | Press Android system key | `button` (`back`, `home`, `enter`, `recents`) |
| `wait` | Pause for UI settle | `duration_ms` |
| `complete_task` | Signal completion | `status`, `answer` |
| `write_todos` | Todo list management | `todos` array |
| `scratchpad` | Key-value memory | `action`, `key`, `value` |
| `delegate_task` | Sub-agent delegation (PRO mode) | `agent_name`, `query` |
| `ask_user` | Request user help mid-task | `type` (`question`/`action`), `message` |

`delegate_task` is registered lazily only when the selected agent definition requires delegation.

`ask_user` is registered lazily in `SessionAgentRunner.start()`. It suspends the agent coroutine via `UserResponseChannel` (CompletableDeferred) until the user responds through the capsule UI, or times out after 5 minutes. See [session.md](session.md) for `UserResponseChannel` details.

### mobile_action Actions

| Action | Description |
|--------|-------------|
| `click` | Tap target |
| `long_press` | Long tap target |
| `type` | Type into focused or targeted field (`input_text`) |
| `swipe` | Directional or coordinate swipe |

### Single Targeting Constraint

Each `mobile_action` call accepts **exactly one** targeting method:
- `element_index` — index from current screen state (preferred)
- `text` + optional `text_index` — visible text on screen
- `x`, `y` — absolute pixel coordinates (last resort)

Multiple targeting methods in a single call → validation error. No implicit priority or cross-target fallback.

`type` allows no target (types into the currently focused field).

---

## mobile_action Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: TOOL CONTRACT                                       │
│                                                               │
│  MobileActionTool.kt  — ToolSpec, validation, target parsing  │
│  MobileActionInvocation.kt — thin glue: routes to executor,   │
│    maps ActionOutcome → ToolExecutionResult                   │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│  Layer 2: ACTION EXECUTORS (the single smart layer)           │
│                                                               │
│  ClickExecutor       — ACTION_CLICK → gesture_tap fallback    │
│  LongPressExecutor   — ACTION_LONG_CLICK → gesture hold       │
│  TypeExecutor        — SetTextOnNodeAt → tap-to-focus fallback│
│  SwipeExecutor       — direction/distance computation         │
│  TargetResolver      — Target → Point resolution              │
│  UiChangeDetector    — snapshot fingerprinting                │
│  ObservationBuilder  — post-action ToolObservation builder    │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│  Layer 3: ATOMIC PLATFORM                                     │
│                                                               │
│  AccessibilityPlatform — each UIAction = one Android API call │
└──────────────────────────────────────────────────────────────┘
```

**Key properties:**
- Platform is pure mechanism (no fallback, no target resolution)
- Executors are the single smart layer (fallback chains, UI change verification)
- MobileActionInvocation is thin glue (~60 lines)
- Each executor ~80-130 lines, linear and testable

### Executor Fallback Chains

| Action | Attempt 1 | Attempt 2 | On All Fail |
|--------|-----------|-----------|-------------|
| click | `ClickNodeAt(x,y)` | `TapAt(x,y)` | Failed with trail |
| long_press | `LongClickNodeAt(x,y)` | `LongPressAt(x,y,ms)` | Failed with trail |
| type (with target) | `SetTextOnNodeAt(x,y)` | `TapAt` → `SetTextOnFocused` | Failed with trail |
| type (no target) | `SetTextOnFocused` | — | Failed |
| swipe | `Swipe(start,end)` | — | Failed |

Each attempt: dispatch → settle delay (300ms) → verify UI change via `UiChangeDetector`.

**TypeExecutor note:** Attempt 2 (TapAt → SetTextOnFocused) is guarded by `platform.allowTapToFocus()` and skipped when the platform returns false (Virtual Display mode).

### ActionOutcome

→ See: `tool/action/ActionOutcome.kt`

Executor return type, richer than `ActionResult`:

| Outcome | Meaning |
|---------|---------|
| `Success(verified=true)` | UI change confirmed |
| `Success(verified=false)` | Dispatched but snapshot unavailable |
| `Failed(attemptTrail)` | All attempts exhausted |
| `Cancelled` | Cancelled between attempts |

---

## Tool Abstraction

All tools implement `ToolSpec` directly:

```kotlin
interface ToolSpec {
    val name: String
    val description: String
    val parameterSchema: JSONObject
    fun validate(params: JSONObject): ValidationResult
    fun createInvocation(params: JSONObject): ToolInvocation
}
```

### Invocation Types

| Type | Used By |
|------|---------|
| `MobileActionInvocation` | `MobileActionTool` — routes to executors |
| `UIActionInvocation` | `SystemButtonTool`, `WaitTool` — direct UIAction execution |
| `DataQueryInvocation` | Data query tools |
| Custom invocations | `OpenAppTool`, `WriteTodosTool`, `ScratchpadTool`, etc. |

---

## Tool Observation

Successful tool execution can include post-action screen context:

→ See: `tool/action/ObservationBuilder.kt`

```kotlin
internal suspend fun buildObservation(
    snapshot: ScreenSnapshot, platform: AndroidPlatform
): ToolObservation.ScreenState
```

Used by executors (ClickExecutor, LongPressExecutor, TypeExecutor, SwipeExecutor) to capture post-action screen state for the LLM.

---

## Adding New Tools

1. Implement `ToolSpec` in `tool/impl/`
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
├── ToolName.kt               # Canonical tool/action names
├── ToolRegistry.kt           # Discovery/registration
├── ToolRouter.kt             # Execution state machine
├── PolicyEngine.kt           # Approval logic
├── action/                   # Executor layer (mobile_action)
│   ├── Target.kt             # Targeting sealed interface
│   ├── ActionOutcome.kt      # Executor result type
│   ├── ClickExecutor.kt      # Click fallback chain
│   ├── LongPressExecutor.kt  # Long press fallback chain
│   ├── TypeExecutor.kt       # Focus-then-type flow
│   ├── SwipeExecutor.kt      # Direction/distance computation
│   ├── TargetResolver.kt     # Target → Point resolution
│   ├── UiChangeDetector.kt   # Snapshot fingerprinting
│   └── ObservationBuilder.kt # Post-action observation
├── handlers/
│   ├── UIActionInvocation.kt # Used by SystemButtonTool, WaitTool
│   └── DataQueryInvocation.kt
└── impl/
    ├── MobileActionTool.kt
    ├── MobileActionInvocation.kt
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

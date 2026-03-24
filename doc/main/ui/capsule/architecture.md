# Smart Capsule Architecture

> Internal architecture of the Smart Capsule: modes, rendering, state transitions, and integration.
> -> See: [overlay.md](../overlay.md) for overlay system overview and mode-aware branching.
> -> See: [state_machine.md](state_machine.md) for formal state vector and transition rules.
> -> See: [user_flows.md](user_flows.md) for location x platform interaction matrix.
> Last updated: 2026-02-20 (commit: 2493be6)

## Architecture

- **CapsuleStateHolder** — single source of truth. Holds `CapsuleMode`, `CapsuleContext`, `PlatformMode`, `turnPhase`, `isAgentMidTurn`, `isStopPending` as `StateFlow`s.
- **CapsuleOverlayHost** — Compose overlay via `OverlayComposeHost`. Dynamic touchability (only `Hidden` sets `FLAG_NOT_TOUCHABLE`). Debounces button callbacks (300ms). Touch gate for agent gesture injection.
- **SmartCapsuleSurface** — 3-row Compose layout consuming `CapsuleMode` and `CapsuleRenderSpec`.
- **SmartCapsuleCompose** — Compose version for main app (`Scaffold.bottomBar`). Same surface, same callbacks.

## CapsuleMode

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

### Mode Rendering

| Mode | Dot | Row 1 | Row 2 Primary | Row 3 |
|------|-----|-------|---------------|-------|
| **Running** | Blue (pulsing) | Thought text | [Takeover] | Input + "Add note" |
| **TakeoverPending** | Amber | "Handing over..." | [Handing over] (disabled) | Input + "Add note" |
| **Takeover** | Amber | Last thought (60% alpha) | [Resume] | Input + "Add note" |
| **WaitingForInput** | Hidden | "Awaiting response" + body | [Stop] only | Input + "Send" |
| **WaitingForAction** | Hidden | "Action needed" + body | [Done] | Hidden |
| **Done** | Teal | "message" | Hidden | Hidden |
| **Error** | Red | "message" | [Close] | Hidden |
| **Hidden** | Hidden | — | Hidden | Input + "Send" |

### Layout

```
┌──────────────────────────────────────────┐
│ [●] Thought text...                      │  ← Row 1: status dot + thought
│──────────────────────────────────────────│
│ [Takeover] [Stop]           [⊖] [📱] [👁]│  ← Row 2: controls + nav icons
│──────────────────────────────────────────│
│ [Got ideas? Add a note...    ] [Add note]│  ← Row 3: input + action button
└──────────────────────────────────────────┘
```

## CapsuleRenderSpec & NavSpec

> See: `ui/overlay/model/CapsuleRenderSpec.kt`

`CapsuleRenderSpec` — pure rendering spec derived from `CapsuleMode`: `dot`, `thought`, `expandedBody`, `buttons`, `row3`.

`NavSpec` — separate from render spec (depends on `CapsuleContext` + `PlatformMode`, not mode): `showMinimize` (VD only), `showApp` (not in main app), `showWatch` (VD, not already viewing).

## State Transitions

> See: `ui/overlay/CapsuleStateHolder.kt`

| Method | Guard | Transition |
|--------|-------|------------|
| `onTaskStarted(taskId, input)` | Any | → `Running(sanitized input)` |
| `onThoughtUpdate(thought)` | Must be `Running` | → `Running(thought)` |
| `onTakeoverRequested()` | Must be `Running` | → `TakeoverPending(thought)` |
| `onTakeoverConfirmed()` | `TakeoverPending` or `Running` | → `Takeover(thought)` |
| `onResumed()` | `Takeover` or `TakeoverPending` | → `Running("Thinking...")` |
| `onAskUser(type, message, callId)` | Any active | → `WaitingForInput` or `WaitingForAction` |
| `onUserResponseSent(callId)` | `WaitingFor*` + callId match | → `Running("Processing response...")` |
| `onTaskCompleted(reason, message?)` | Not `Hidden`/`Done`/`Error` | → `Done` or `Error` |
| `onSessionEnded(reason)` | Any | → `Done`/`Hidden`/`Error` per reason |
| `onError(message)` | Any | → `Error(message)` |
| `onDismissError()` | Must be `Error` | → `Hidden` |

Auto-hide: `Done` → `Hidden` after 3000ms.

## Thought Pipeline

1. LLM returns tool call with `agent_thought` parameter
2. `AgentTurnRunner` sanitizes thought (≤40 chars via `sanitizeThought`)
3. `AgentEvent.ThoughtUpdate` → `CapsuleStateHolder.onThoughtUpdate()` → `Running(thought)`
4. `SmartCapsuleSurface` recomposes via `stateHolder.mode` StateFlow

## Supplement & Stop Feedback

**Supplement confirmation** (`flashSupplementConfirmation`): Between turns → "Received" (1500ms); mid-turn → "Received, will apply next step" (2000ms).

**Stop pending**: `isStopPending` drives immediate "Stopping..." disabled UI. Transient flag (not part of `CapsuleMode` state machine), cleared by next terminal event.

## Callbacks

| Callback | Dispatches |
|----------|------------|
| `onTakeover` | `CapsuleStateHolder.onTakeoverRequested()` → `Op.Takeover` |
| `onResume` | `Op.Resume` |
| `onSupplement` | `Op.Supplement(text)` |
| `onUserResponse` | `CapsuleStateHolder.onUserResponseSent()` → `Op.UserResponse(callId, response)` |
| `onStop` | `Op.Shutdown` |
| `onSend` | `Op.UserInput(text)` |
| `onOpenApp` | Opens main activity |
| `onDismissError` | `CapsuleStateHolder.onDismissError()` |
| `onMinimize` | Hides capsule, shows island |
| `onOpenViewer` | Launches VD viewer |

## Integration Flows

**Overlay:** `AgentSession` → `AgentEvent` → `AgentService.handleEvent()` → `ServiceOverlayController` → `CapsuleStateHolder` → `SmartCapsuleSurface`

**Compose (in-app):** `CapsuleStateHolder.mode` collected via `StateFlow` in `ChatScreen` → `SmartCapsuleCompose` → `SmartCapsuleSurface`

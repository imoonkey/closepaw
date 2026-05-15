# Smart Capsule Architecture

> Internal architecture of the Smart Capsule: modes, rendering, state transitions, and integration.
> -> See: [overlay.md](../overlay.md) for overlay system overview and mode-aware branching.
> -> See: [state_machine.md](state_machine.md) for formal state vector and transition rules.
> -> See: [user_flows.md](user_flows.md) for location x platform interaction matrix.
> Last updated: 2026-05-15

## Architecture

- **CapsuleStateHolder** — single source of truth. Holds `CapsuleMode`, `CapsuleContext`, `PlatformMode`, `hasIsland`, `turnPhase`, `isAgentMidTurn`, `isStopPending` as `StateFlow`s.
- **CapsuleOverlayHost** — Compose overlay via `OverlayComposeHost`. Reads mode, context, platformMode, and hasIsland from `CapsuleStateHolder`. Owns only host-specific state (focusability, touchability, interactionLocked, inputFocused). Dynamic touchability (only `Hidden` sets `FLAG_NOT_TOUCHABLE`). Debounces button callbacks (300ms). Touch gate for agent gesture injection.
- **SmartCapsuleSurface** — single Compose entry point used by both the overlay host (`CapsuleOverlayHost`) and `ChatScreen`'s `Scaffold.bottomBar`. Slim orchestrator: derives `CapsuleRenderSpec` + `NavSpec`, lays out the four slots (status line, optional detail body, control bar, optional input bar), and routes submit intent. Receives `previousMode` from `CapsuleStateHolder` for input-clearing.
- **CapsuleControlBar** — control-bar composable: action-button cluster (mode-driven Takeover / Resume / Done / Always / Session / Reject / Stop / Close) on the left, nav-button cluster (Minimize / OpenApp / OpenViewer, gated by `NavSpec`) on the right.
- **CapsuleInputBar** — text-field + send composable. Owns the draft state and the `pendingInputText` / `clearDraft` / `inputEnabled` lifecycle. Exposes a single `onSubmit(text)` callback; routing (Hidden → onSend / WaitingForInput → onUserResponse / else → onSupplement) lives in the orchestrator.
- **CapsuleBinding** — value type bridging the agent runtime and a UI host. Wraps the three StateFlows (`mode`, `platformMode`, `isStopPending`) and the two callbacks (`onStopRequested`, `onApprovalResolved`) the chat surface needs from `CapsuleStateHolder`. `InertCapsuleBinding` is the unbound-runtime fallback so `ChatScreen` can render its idle state without reaching for `AgentService.instance`. Activities (e.g. `MainActivity`) build the live binding from the service.

## CapsuleMode

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class WaitingForApproval(
        val callId: String,
        val description: String,
        val appLabel: String,
        val packageName: String,
        val reason: String
    ) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

### Mode Rendering

| Mode | Dot | Status line | Action button (primary) | Input bar |
|------|-----|-------------|-------------------------|-----------|
| **Running** | Blue (pulsing) | Thought text | [Takeover] | Input + "Add note" |
| **TakeoverPending** | Amber | "Handing over..." | [Handing over] (disabled) | Input + "Add note" |
| **Takeover** | Amber | Last thought (60% alpha) | [Resume] | Input + "Add note" |
| **WaitingForInput** | Hidden | "Awaiting response" + body | [Stop] only | Input + "Send" |
| **WaitingForAction** | Hidden | "Action needed" + body | [Done] | Hidden |
| **WaitingForApproval** | Amber | "Allow ClosePaw to operate {AppName}?" | [Always] [Session] [Reject] | Hidden |
| **Done** | Teal | "message" | Hidden | Hidden |
| **Error** | Red | "message" | [Close] | Hidden |
| **Hidden** | Hidden | — | Hidden | Input + "Send" |

### Layout

```
┌──────────────────────────────────────────┐
│ [●] Thought text...                      │  ← Status line (dot + thought)
│──────────────────────────────────────────│
│ [Takeover] [Stop]           [⊖] [📱] [👁]│  ← Control bar (actions + nav)
│──────────────────────────────────────────│
│ [Got ideas? Add a note...    ] [Add note]│  ← Input bar (text + send)
└──────────────────────────────────────────┘
```

## CapsuleRenderSpec & NavSpec

> See: `ui/overlay/model/CapsuleRenderSpec.kt`

`CapsuleRenderSpec` — pure rendering spec derived from `CapsuleMode`: `dot`, `thought`, `expandedBody`, `buttons`, `input` (input-bar `hint` / `submitLabel` / `clearDraft`).

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
| `onApprovalRequired(callId, description, appLabel, packageName, reason)` | Any active | → `WaitingForApproval` |
| `onUserResponseSent(callId)` | `WaitingForInput`/`WaitingForAction` + callId match | → `Running("Processing response...")` |
| `onApprovalResolved(callId)` | `WaitingForApproval` + callId match | → `Running("Processing...")` |
| `onTaskCompleted(reason, message?)` | Not `Hidden`/`Done`/`Error` | → `Done` or `Error` |
| `onSessionEnded(reason)` | Any | → `Done`/`Hidden`/`Error` per reason |
| `onError(message)` | Any | → `Error(message)` |
| `onDismissError()` | Must be `Error` | → `Hidden` |

Auto-hide: `Done` → `Hidden` after 3000ms.

## Thought Pipeline

1. LLM returns tool call with `agent_thought` parameter.
2. `TurnPlanningPhaseRunner.emitAgentThought` trims whitespace and emits
   `ThoughtUpdate(full, compact)` — `full` is the untouched text, `compact`
   is the ~80-char single-line preview produced via `compactThought()` (uxfb-1
   replaced the old 40-char `sanitizeThought` which silently dropped data).
3. `AgentEvent.ThoughtUpdate` →
   - `CapsuleStateHolder.onThoughtUpdate(full)` → `Running(full)` for the
     overlay surfaces.
   - `ChatEventReducer` stores `full` as `ContentBlock.Thought` for the chat.
   - `SessionRecordingService.recordThought(full)` for history.
4. Capsule renderers (`StatusIslandCompose`, `SmartCapsuleSurface`) display
   the full text via `Modifier.basicMarquee`. Reduced-motion users (per
   `ClosePawMotion.reducedMotion()`) get `compactThought(full)` with
   ellipsis instead. `StatusIslandCompose` pins width via `widthIn(max =
   220.dp)` in both branches so the overlay can't grow off-screen (uxfb-2).
5. `SmartCapsuleSurface` recomposes via `stateHolder.mode` StateFlow.

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
| `onApprovalResponse` | `CapsuleStateHolder.onApprovalResolved()` → `Op.Approve(callId, decision, scope, packageName)` |
| `onStop` | `Op.Shutdown` |
| `onSend` | `Op.UserInput(text)` |
| `onOpenApp` | Opens main activity |
| `onDismissError` | `CapsuleStateHolder.onDismissError()` |
| `onMinimize` | Hides capsule, shows island |
| `onOpenViewer` | Launches VD viewer |

## Integration Flows

**Overlay:** `AgentSession` → `AgentEvent` → `AgentService.handleEvent()` → `ServiceOverlayController` → `CapsuleStateHolder` → `SmartCapsuleSurface`

**Compose (in-app):** `CapsuleStateHolder.mode` collected via `StateFlow` in `ChatScreen` → `SmartCapsuleSurface`

# State Machine SOTA — Aligned Design (v2)

Date: 2026-02-20
Status: CODEX round update (code-truth baseline)

## 1. Canonical State Vector

UI behavior is determined by 4 dimensions:

1. `PlatformMode`: `ACCESSIBILITY | VIRTUAL_DISPLAY`
2. `OverlayUserLocation`: `MAIN_APP | VD_VIEWER | OTHER_APP`
3. `CapsuleMode`: `Hidden | Running | TakeoverPending | Takeover | WaitingForInput | WaitingForAction | Done | Error`
4. `ShowPreference`: `CAPSULE | ISLAND`

Ownership:
- `CapsuleMode` (+ `isStopPending`, `turnPhase`, `isAgentMidTurn`, `previousMode`) → `CapsuleStateHolder`
- `PlatformMode`, `OverlayUserLocation`, `ShowPreference` → `ServiceOverlayController`

## 2. CapsuleMode Transitions

### 2.1 Universal events (any source mode)

- `onTaskStarted(taskId, input)` -> `Running(sanitizeThought(input))`
- `onError(message)` -> `Error(sanitizeThought(message))`
- `onAskUser(QUESTION, msg, callId)` -> `WaitingForInput(msg, callId)`
- `onAskUser(ACTION, msg, callId)` -> `WaitingForAction(msg, callId)`

### 2.2 Guarded events

- `onThoughtUpdate(t)`: `Running` only -> `Running(t)`
- `onTakeoverRequested()`: `Running` only -> `TakeoverPending(lastThought)`
- `onTakeoverConfirmed()`: `Running|TakeoverPending` -> `Takeover(lastThought)`
- `onResumed()`: `Takeover|TakeoverPending` -> `Running("Thinking...")`
- `onUserResponseSent(callId)`: `WaitingForInput|WaitingForAction` + callId match -> `Running("Processing response...")`
- `onDismissError()`: `Error` only -> `Hidden`

Invalid source/callId are ignored.

### 2.3 Task completion path (`onTaskCompleted`)

Guard: ignore when already `Hidden|Done|Error`.

- `GOAL_ACHIEVED` -> `Done(message ?: "Task completed")`
- `MAX_TURNS` -> `Done("Max steps reached")`
- `TASK_IMPOSSIBLE` -> `Done("Task impossible")`
- `USER_STOPPED` -> `Done("Stopped")`
- `INTERRUPTED` -> `Done("Interrupted")`
- `ERROR` -> `Error("Error occurred")`

`Done` auto-hides to `Hidden` after 3s.

### 2.4 Session completion path (`onSessionEnded`)

This is distinct from task completion routing:

- `GOAL_ACHIEVED` -> `Done(preserve current done text or default)` -> auto-hide
- `MAX_TURNS` -> `Done("Max steps reached")` -> auto-hide
- `TASK_IMPOSSIBLE` -> `Done("Task impossible")` -> auto-hide
- `USER_STOPPED|INTERRUPTED` -> `Hidden` (immediate)
- `ERROR` -> `Error("Error occurred")` (if not already Error)

## 3. ShowPreference and Location Transitions

`ShowPreference` init is `ISLAND`.

Set to `CAPSULE` on:
- `onTaskStarted`
- `onAskUser`
- `onSessionError`
- `onViewerOpened`
- `onIslandTapped` in A11y or VD_VIEWER

Set to `ISLAND` on:
- `onMinimize`
- `onViewerClosed`

Forced normalization in visibility policy:
- mode in `WaitingForInput|WaitingForAction|Error` => effective preference = `CAPSULE`

Location transitions:
- `handleWindowStateChanged` via `resolveUserLocation`
- `onViewerOpened` -> `VD_VIEWER`
- `onViewerClosed` -> `OTHER_APP` (if previously VD_VIEWER)
- `onMainAppVisible` -> `MAIN_APP`

`resolveUserLocation` ignores:
- non-activity-like class names
- non-default display windows

## 4. Visibility Decision Machine

Single authority: `ServiceOverlayController.applyVisibility()`.

Derived:
- `isActive = hasActiveTask || mode is Done || mode is Error`

A11y:
- `MAIN_APP`: all system overlays hidden
- non-main + active: capsule/island by preference (mutually exclusive), glow visible

VD:
- `MAIN_APP` or inactive: all overlays hidden; compose capsule shows input only (no Row3 viewer icon)
- non-main + active: capsule/island by preference (mutually exclusive)
- glow only when `location=VD_VIEWER && hasActiveTask=true`

Invariant:
- never show capsule and island together

## 5. Non-Mode Transient State

- `isStopPending`: transient UI feedback (`Stopping...`), cleared by new task/terminal transitions
- `turnPhase`, `isAgentMidTurn`: drive glow and supplement flash wording
- `previousMode`: used for render transitions (e.g., input clear behavior)

## 6. Runtime Caveat (Current Build)

Current overlay capsule window includes `FLAG_NOT_TOUCHABLE`.
This does not change state-machine definitions, but it affects whether overlay-triggered transitions are practically reachable by user touch in runtime.

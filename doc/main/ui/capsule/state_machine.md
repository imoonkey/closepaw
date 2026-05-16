# Capsule State Machine

> Formal state vector, transition rules, and visibility decision machine.
> Last updated: 2026-04-20
>
> -> See: [`doc/main/state_machines/ui_capsule.md`](../../state_machines/ui_capsule.md) for the test-locked CapsuleMode transition reference (mirrors source + has corresponding unit tests). This doc covers the broader location/visibility decision machine on top of that.

## 1. Canonical State Vector

UI behavior is determined by 4 dimensions:

1. `PlatformMode`: `ACCESSIBILITY | VIRTUAL_DISPLAY`
2. `OverlayUserLocation`: `MAIN_APP | VD_VIEWER | OTHER_APP`
3. `CapsuleMode`: `Hidden | Running | TakeoverPending | Takeover | WaitingForInput | WaitingForAction | WaitingForApproval | Done | Error`
4. `ShowPreference`: `CAPSULE | ISLAND`

Ownership:
- `CapsuleMode` (+ `isStopPending`, `turnPhase`, `isAgentMidTurn`, `previousMode`, `context`, `platformMode`) → `CapsuleStateHolder`
- `OverlayUserLocation`, `ShowPreference` → `ServiceOverlayController` (drives `CapsuleStateHolder.setContext()` and `setPlatformMode()`)

> See: `ui/overlay/CapsuleStateHolder.kt`, `app/ServiceOverlayController.kt`, `app/OverlayLocationPolicy.kt`

## 2. CapsuleMode Transitions

### 2.1 Universal events (any source mode)

- `onTaskStarted(taskId, input)` → `Running(compactThought(input))` — also clears `isStopPending` and `turnPhase`. CapsuleStateHolder uses `compactThought` because the input echo here is for the bare title slot; live thought updates from `onThoughtUpdate(full)` carry the full text and override.
- `onError(message)` → `Error(compactThought(message))` — also clears `isStopPending`
- `onAskUser(QUESTION, msg, callId)` → `WaitingForInput(msg, callId)`
- `onAskUser(ACTION, msg, callId)` → `WaitingForAction(msg, callId)`
- `onApprovalRequired(callId, description, appLabel, packageName, reason)` → `WaitingForApproval(...)`

### 2.2 Guarded events

- `onThoughtUpdate(t)`: `Running` only → `Running(t)`
- `onTakeoverRequested()`: `Running` only → `TakeoverPending(lastThought)`
- `onTakeoverConfirmed()`: `Running|TakeoverPending` → `Takeover(lastThought)`
- `onResumed()`: `Takeover|TakeoverPending` → `Running("Thinking...")` — also clears `turnPhase` and `isAgentMidTurn`
- `onUserResponseSent(callId)`: `WaitingForInput|WaitingForAction` + callId match → `Running("Processing response...")` — returns `Boolean` (false on guard/callId mismatch)
- `onApprovalResolved(callId)`: `WaitingForApproval` + callId match → `Running("Processing...")` — returns `Boolean` (false on guard/callId mismatch)
- `onStopRequested()`: `Running|TakeoverPending|Takeover|WaitingForInput|WaitingForAction|WaitingForApproval` + not already stop-pending → sets `isStopPending = true`, returns `Boolean` — does **not** change mode
- `onDismissError()`: `Error` only → `Hidden`

Invalid source/callId are ignored.

### 2.3 Task completion path (`onTaskCompleted`)

Guard: ignore when already `Hidden|Done|Error`. Clears `isStopPending`.

- `GOAL_ACHIEVED` → `Done(message ?: "Task completed")`
- `TASK_IMPOSSIBLE` → `Done("Task impossible")`
- `USER_STOPPED` → `Done("Stopped")`
- `ERROR` → `Error("Error occurred")`

`Done` auto-hides to `Hidden` after 3s.

(`TaskOutcome` enum is exactly these four values; there is no `MAX_TURNS`, `INTERRUPTED`, or `IDLE_TIMEOUT` task outcome. Production runs are bounded by context-window auto-compaction, not a turn count — see [agent/loop.md](../../agent/loop.md#auto-compaction).)

### 2.4 Session completion path (`onSessionEnded`)

This is distinct from task completion routing. Cancels any pending auto-hide, clears `isStopPending`.

For all `SessionEndReason` values (`USER_STOPPED|INTERRUPTED|IDLE_TIMEOUT`) → `Hidden` (immediate).

The capsule does not render a session-level outcome; per-task outcome was already shown via `onTaskCompleted`.

## 3. ShowPreference and Location Transitions

`ShowPreference` init is `ISLAND`.

Set to `CAPSULE` on:
- `onTaskStarted`
- `onAskUser`
- `onSessionError`
- `onViewerOpened`
- `onIslandTapped` in A11y mode (always)
- `onIslandTapped` in VD + VD_VIEWER

Set to `ISLAND` on:
- `onMinimize`
- `onViewerClosed`

Forced normalization in visibility policy:
- mode in `WaitingForInput|WaitingForAction|WaitingForApproval|Error` ⇒ effective preference = `CAPSULE`

Location transitions:
- `handleWindowStateChanged` via `resolveUserLocation`
- `onViewerOpened` → `VD_VIEWER`
- `onViewerClosed` → `OTHER_APP` (if previously VD_VIEWER)
- `onMainAppVisible` → `MAIN_APP`
- `onMainAppHidden` → `OTHER_APP` (only if currently `MAIN_APP`; preserves `VD_VIEWER` set by `onViewerOpened`)

`resolveUserLocation` ignores:
- non-activity-like class names
- non-default display windows (prevents VD app windows from triggering transitions)
- null packageName

### Viewer Auto-Finish

When `(platform=VIRTUAL_DISPLAY ∧ location=VD_VIEWER ∧ !hasActiveTask ∧ mode=Hidden)` — i.e. agent went idle while user is in VD viewer — `VirtualDisplayViewerActivity` auto-finishes so the user lands back on MainActivity instead of a frozen VD surface. Pure rule: `OverlayLocationPolicy.shouldFinishViewerOnIdle`. Two trigger paths (SharedFlow signal from `applyVisibility` + synchronous query polled in `VirtualDisplayViewerActivity.onStart`) cover task-ends-while-in-viewer and viewer-opened-when-already-idle. -> See: [overlay.md#vd-viewer-auto-finish](../overlay.md#vd-viewer-auto-finish).

## 4. Visibility Decision Machine

Single authority: `ServiceOverlayController.applyVisibility()`.

**Inputs:** `platformMode`, `userLocation`, `mode` (from CapsuleStateHolder), `hasActiveTask`, `showPreference`

Derived:
- `isActive = hasActiveTask || mode is Done || mode is Error`

### A11y

- `MAIN_APP`: all system overlays hidden
- non-main + active: capsule/island by preference (mutually exclusive), glow visible

### VD

- `MAIN_APP` or inactive: all overlays hidden; compose capsule shows input bar only (no input-bar viewer icon)
- non-main + active: capsule/island by preference (mutually exclusive)
- glow only when `location=VD_VIEWER && hasActiveTask=true`

### Invariants

- Capsule and island never shown simultaneously
- `applyVisibility()` normalizes `showPreference` before applying (writes back the normalized value)
- Redundant show() calls avoided (checks `isShowing()` first)

## 5. Interaction Locking

`shouldLockUserInteraction()` determines whether the capsule overlay blocks touch pass-through:

- **ACCESSIBILITY + OTHER_APP**: locked (protects other apps from accidental touches)
- **VIRTUAL_DISPLAY + VD_VIEWER**: locked (prevents double-interaction with VD)
- **Unlocked** when: user has control (Takeover), non-interactive state (Hidden/Done/Error), or in MAIN_APP

When locked:
- Capsule layout expands to `MATCH_PARENT` with `TOP|START` gravity (full-screen touch shield)
- When unlocked: `WRAP_CONTENT` with `BOTTOM|CENTER_HORIZONTAL`

## 6. Touchability

Capsule overlay touchability is **dynamic** per `shouldCapsuleOverlayBeTouchable(mode)`:

- `Hidden` → `FLAG_NOT_TOUCHABLE` (touches pass through to underlying app)
- All other modes → touchable (buttons, input fields, and touch shield are interactive)

Additional touch gate: `OverlayTouchGate.beginGesturePassThrough()` temporarily makes capsule not-touchable during agent gesture injection, restoring baseline after gesture completes (depth-counted for nested gestures).

Focus management: capsule overlay is focusable (soft keyboard) when `mode is WaitingForInput` or (`mode is Takeover` and input is focused). Otherwise `FLAG_NOT_FOCUSABLE`.

## 7. Non-Mode Transient State

- `isStopPending`: transient UI feedback (`Stopping...`), cleared by new task/terminal transitions. Does not change CapsuleMode.
- `turnPhase`, `isAgentMidTurn`: drive glow state and supplement flash wording
- `previousMode`: used for render transitions (e.g., input clear behavior when entering WaitingForInput)

## Related Docs

- [Overlay](../overlay.md) — rendering, overlay hosts, visual specs
- [User Flows](user_flows.md) — location × platform interaction matrix
- [Session](../../infra/session.md) — session lifecycle, TaskOutcome / SessionEndReason

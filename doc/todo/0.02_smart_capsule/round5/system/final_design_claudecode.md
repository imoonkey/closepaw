# Smart Capsule Round 5 — Final Design

> Author: Claude Code (Opus 4)
> Date: 2026-02-14
> Synthesized from: review_design_claudecode.md, review_design_codex.md, review_comparison_claudecode.md, review_comparison_codex.md, qi_bug_note.md, and subsequent discussion
> Principle: KISS + Occam's Razor, incremental approach

---

## 1. Diagnosis Summary

The `CapsuleStateHolder` state machine (8 states, transition guards) is **fundamentally correct**. The bugs trace to three systemic issues:

1. **Visibility coordination** — no single authority decides which overlay windows are visible. Scattered show/hide logic in `ServiceOverlayController` + `StatusIslandManager` observer auto-showing independently → island/capsule appear simultaneously.
2. **Missing transition wiring** — `onTakeoverRequested()` and `onUserResponseSent()` are defined but never called, leaving `TakeoverPending` unreachable and `WaitingForInput` stuck.
3. **Wrong product semantics** — `performHandoff()` cross-launches VD apps to real screen; A11y overlay exposes "return to app" that disrupts agent; NavSpec shows buttons in contexts where they're useless.

## 2. What Stays (Do Not Touch)

| Component | Why |
|---|---|
| `CapsuleMode` sealed interface (8 states) | Clean state enum. Each state carries exactly its data. No rename needed. |
| `CapsuleStateHolder` transition guards | Correct per the state matrix. Invalid events silently ignored with logs. |
| `CapsuleRenderSpec.from()` | Single source of truth for rendering. Both View and Compose read from it. |
| `CapsuleColors` | Centralized palette. |
| `SmartCapsuleRenderer` | Pure spec-in, view-properties-out. ~188 lines. |
| `SmartCapsuleAnimator` | Focused, small, testable. |
| `UserResponseChannel` | Clean suspend-based channel for ask_user flow. |
| Observer pattern (SmartCapsuleManager observes stateHolder.mode) | Eliminates push-based rendering. |
| `SmartCapsuleManager` structure (394 lines) | Cohesive — all responsibilities relate to overlay capsule lifecycle. Not worth splitting. |

---

## 3. Changes

### 3.1 Visibility System — `applyVisibility()` (fixes 8 bugs)

**Problem**: No single function decides "given current state, what's visible?" Logic is scattered across `onTaskStarted()`, `onIslandTapped()`, `onViewerOpened()`, `handleWindowStateChangedA11y()`, and the island observer.

**Solution**: Add `ShowPreference` enum and `applyVisibility()` to `ServiceOverlayController`. Call it after every state change and context change. Remove all other show/hide calls.

```kotlin
// ServiceOverlayController.kt

enum class ShowPreference { CAPSULE, ISLAND }

private var showPreference = ShowPreference.ISLAND // default for VD background

private fun applyVisibility() {
    val mode = stateHolder.mode.value
    val isActive = stateHolder.hasActiveTask
        || mode is CapsuleMode.Done
        || mode is CapsuleMode.Error

    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            // A11y: never shows island. Capsule only when not in our app and task active.
            if (isAppInForeground || !isActive) {
                capsuleManager.hide()
                edgeGlowManager.hideImmediately()
            } else {
                capsuleManager.show()
                edgeGlowManager.show(stateHolder.derivedGlowState)
            }
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            if (isAppInForeground || !isActive) {
                // In our app or no task: Compose capsule handles everything.
                capsuleManager.hide()
                statusIslandManager?.hide()
            } else {
                // Background or viewer: show one of capsule/island per preference.
                when (showPreference) {
                    ShowPreference.CAPSULE -> {
                        capsuleManager.show()
                        statusIslandManager?.hide()
                    }
                    ShowPreference.ISLAND -> {
                        capsuleManager.hide()
                        statusIslandManager?.show()
                    }
                }
            }
        }
    }
}
```

**ShowPreference transitions**:

| User Action | ShowPreference Becomes |
|---|---|
| Minimize button on capsule | ISLAND |
| Island tapped | CAPSULE |
| Viewer opened | CAPSULE |
| Viewer closed | ISLAND |
| Task starts | Keep current (or ISLAND default if in background) |

**Files changed**: `ServiceOverlayController.kt`

**Bugs fixed**: 2.4, 2.5, 5.1 (mutual exclusivity), 1.4, 3.2, 3.2.1 (no island in MAIN_APP), 4.2 (island tap behavior)

### 3.2 VD MAIN_APP Context Tracking (fixes 3 bugs)

**Problem**: `handleWindowStateChanged()` ignores `TYPE_WINDOW_STATE_CHANGED` in VD mode (`ServiceOverlayController.kt:172-174`). The controller has no way to know when the user is on our main app vs another app in VD mode.

**Solution**: Track `isAppInForeground` in VD mode too. The `applyVisibility()` function already uses this boolean.

```kotlin
fun handleWindowStateChanged(packageName: String?, className: String?) {
    when (platformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> {
            handleWindowStateChangedVD(packageName, className)
        }
        PlatformMode.ACCESSIBILITY -> {
            handleWindowStateChangedA11y(packageName, className)
        }
    }
}

private fun handleWindowStateChangedVD(packageName: String?, className: String?) {
    // Same isActivity filtering as A11y, then:
    if (packageName != null) {
        val wasInForeground = isAppInForeground
        isAppInForeground = packageName == appPackage
        if (wasInForeground != isAppInForeground) {
            updateContext(ContextTrigger.A11Y_FOREGROUND_CHANGED) // reuse trigger
            applyVisibility()
        }
    }
}
```

**Files changed**: `ServiceOverlayController.kt`

**Bugs fixed**: 1.4, 3.2, 3.2.1

### 3.3 Island Observer — Display Only, No Visibility (fixes 3 bugs)

**Problem**: `StatusIslandManager.startObserving()` (lines 86-99) calls `show()` on every `mode != Hidden`, overriding the controller's visibility decisions.

**Solution**: Observer only updates display text and dot color when the island is already visible.

```kotlin
// StatusIslandManager.kt
fun startObserving(stateHolder: CapsuleStateHolder, scope: CoroutineScope) {
    if (observeJob != null) return
    observeJob = scope.launch {
        stateHolder.mode.collectLatest { mode ->
            // Only update display. Visibility managed by ServiceOverlayController.
            if (isShowing()) {
                updateDisplay(
                    text = modeText(mode),
                    dotColor = glowStateColor(stateHolder.derivedGlowState),
                )
            }
        }
    }
}
```

**Files changed**: `StatusIslandManager.kt`

**Bugs fixed**: 2.5, 5.1, 5.4

### 3.4 Call `onUserResponseSent()` From Controller (fixes stuck WaitingFor* state)

**Problem** (found by Codex): `stateHolder.onUserResponseSent()` is defined but never called. After the user submits a response in WaitingForInput/WaitingForAction, the mode stays stuck because:
- `onUserResponse` callback only submits `Op.UserResponse` — doesn't update state
- The subsequent `ThoughtUpdate` is rejected by the guard (`if (_mode.value !is CapsuleMode.Running) return`)

This is a real critical bug.

**Solution**: Call `onUserResponseSent()` optimistically from the controller's callback.

```kotlin
// ServiceOverlayController constructor, onUserResponse callback:
this.onUserResponse = { callId, response ->
    stateHolder.onUserResponseSent(callId)  // ← ADD THIS LINE
    this@ServiceOverlayController.onUserResponse(callId, response)
}
```

**Files changed**: `ServiceOverlayController.kt` (1 line)

**Bugs fixed**: WaitingFor* stuck state (contributes to 5.4)

### 3.5 Call `onTakeoverRequested()` From Controller (fixes pending feedback)

**Problem**: `TakeoverPending` state exists in the state machine with correct render spec ("Handing over...", disabled button), but nobody calls `stateHolder.onTakeoverRequested()`. The user clicks Takeover, sees no visual change until `SessionTakeover` event arrives seconds later.

**Solution**: Call `onTakeoverRequested()` optimistically on click.

```kotlin
// ServiceOverlayController constructor, onTakeover callback:
this.onTakeover = {
    stateHolder.onTakeoverRequested()  // ← ADD: immediate visual feedback
    this@ServiceOverlayController.onTakeover()
}
```

**Why optimistic is safe**: `AgentSession.handleTakeover()` has no rejection path. The session always accepts the takeover request. Desync is impossible. If a rejection path is added in the future, add `PendingCommand` type then (YAGNI).

**Files changed**: `ServiceOverlayController.kt` (1 line)

**Bugs fixed**: 1.3, 2.1, 3.1

### 3.6 A11y Overlay: Remove All "Return to App" (per Qi)

**Problem**: A11y overlay shows phone icon (navApp) and Row 1 tap navigates to main app. Both change the foreground, disrupting agent's screen perception and actions.

**Decision**: Per Qi's direct input — remove entirely. No phone icon, no Row1 tap in A11y overlay.

**Implementation**: Fix `NavSpec.from()`:

```kotlin
// CapsuleRenderSpec.kt — NavSpec.from()
fun from(
    context: CapsuleContext,
    platformMode: PlatformMode,
    hasIsland: Boolean,
): NavSpec = NavSpec(
    showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY
        && hasIsland
        && context != CapsuleContext.MAIN_APP,       // ← ADD guard
    showApp = context != CapsuleContext.MAIN_APP
        && platformMode != PlatformMode.ACCESSIBILITY, // ← ADD: hide in A11y
    showWatch = platformMode != PlatformMode.ACCESSIBILITY
        && context != CapsuleContext.SCREEN_VIEWING,
)
```

And disable Row1 tap in A11y mode:

```kotlin
// SmartCapsuleManager — in setupInteractivity or show():
onRow1Tap = if (platformMode == PlatformMode.ACCESSIBILITY) null else { onOpenApp?.invoke() }
```

**Files changed**: `CapsuleRenderSpec.kt`, `SmartCapsuleManager.kt`

**Bugs fixed**: 2.3

### 3.7 A11y Input Focus Policy (fixes focus conflict)

**Problem**: In A11y mode, Running-state overlay allows input. User tapping the input field steals focus from the screen, conflicting with agent's taps.

**Solution**: In A11y mode overlay, input is disabled during `Running`/`TakeoverPending`. Only enabled in `Takeover` and `WaitingForInput`.

```kotlin
// SmartCapsuleManager — in observer callback after rendering:
if (platformMode == PlatformMode.ACCESSIBILITY) {
    when (mode) {
        is CapsuleMode.Running, is CapsuleMode.TakeoverPending -> {
            inputEditText.isFocusable = false
            inputEditText.isFocusableInTouchMode = false
            inputEditText.hint = "Take over to type note"
        }
        is CapsuleMode.Takeover, is CapsuleMode.WaitingForInput -> {
            inputEditText.isFocusable = true
            inputEditText.isFocusableInTouchMode = true
        }
        else -> { /* input not shown */ }
    }
}
```

In VD mode, input is always interactive (agent operates on a separate display; no focus conflict).

**Files changed**: `SmartCapsuleManager.kt`

**Bugs fixed**: 2.6 (keyboard issue), 2.7 (focus conflict)

### 3.8 Island Tap in VD → Open Viewer (fixes wrong behavior)

**Problem**: VD mode island tap currently shows capsule overlay floating over the user's own apps. The user should be taken to the VD viewer to see the agent's screen.

**Solution**:

```kotlin
// ServiceOverlayController
fun onIslandTapped() {
    if (!stateHolder.hasActiveTask) {
        onOpenApp()
        return
    }
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            // Shouldn't happen (no island in A11y), but defensive:
            showPreference = ShowPreference.CAPSULE
            applyVisibility()
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            // Open VD viewer — onViewerOpened() handles capsule+island swap
            onOpenViewer?.invoke() ?: onOpenApp()
        }
    }
}
```

**Files changed**: `ServiceOverlayController.kt`

**Bugs fixed**: 4.2

### 3.9 Delete `performHandoff()` (fixes VD completion side effect)

**Problem**: `AgentService.kt:525-542` relaunches VD's last app on real screen after task completion. Breaks dual-instance apps (e.g., YouTube), interrupts VD playback.

**Solution**: Delete `performHandoff()` and its call site. Task completion = task done. The VD environment persists for user inspection.

**Files changed**: `AgentService.kt` (~-18 lines)

**Bugs fixed**: 5.3, 5.4

### 3.10 Delete Dead Code

| Dead Code | File | Lines |
|---|---|---|
| `InputDock.kt` | `ui/chat/components/InputDock.kt` | Delete file |
| `InputState` enum | `ui/chat/model/ChatMessage.kt:175-181` | Delete enum |
| `onMessageDelta()` | `ServiceOverlayController.kt:201-209` | Delete method |
| Duplicate `previousMode` | `SmartCapsuleManager.kt:64` | Remove, use `stateHolder.previousMode` |

**Bugs fixed**: 1.5 (confirms InputDock is already unused; SmartCapsuleCompose IS the bottom bar)

### 3.11 Chat/History Completeness

**Problem 1** (bug 3.4): `TaskCompleted.result` summary not always shown in chat history.
- `ChatViewModel.kt:267-276`: `TaskCompleted` marks the agent message as complete but doesn't merge `event.result` text into the message content blocks.
- **Fix**: When `result` is non-null and non-empty, append a `ContentBlock.Text(result)` to the last agent message.

**Problem 2** (bug 3.5): Supplement (add note) not shown in chat history.
- `AgentSession.kt:321-337`: supplement writes to `HistoryManager` but NOT to `SessionRecordingService`.
- `ChatViewModel.kt:103-115`: ignores `SupplementReceived` event.
- **Fix**: In `ChatViewModel`, handle `SupplementReceived` event — add a `ChatMessage.User` with the supplement text. In `AgentSession`, also write supplement to `SessionRecordingService`.

**Files changed**: `ChatViewModel.kt`, `AgentSession.kt`

**Bugs fixed**: 3.4, 3.5

---

## 4. Refactored Event Handlers

After the changes, `ServiceOverlayController` event handlers become simple:

```kotlin
fun onTaskStarted(taskId: String, input: String) {
    stateHolder.onTaskStarted(taskId, input)
    applyVisibility()
}

fun onTurnPhaseChanged(phase: TurnPhase) {
    stateHolder.setTurnPhase(phase)
    stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}

fun onThoughtUpdate(thought: String) {
    stateHolder.onThoughtUpdate(thought)
    // both overlay and island auto-render via observer
}

fun onTaskCompleted(reason: CompletionReason, message: String?) {
    stateHolder.onTaskCompleted(reason, message)
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
    // applyVisibility not needed: Done/Error → rendering updates via observer.
    // When auto-hide fires (Done→Hidden), applyVisibility via mode observer.
}

fun onSessionCompleted(reason: CompletionReason) {
    stateHolder.onSessionEnded(reason)
    applyVisibility()
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}

fun onAskUser(type: AskUserType, message: String, callId: String) {
    stateHolder.onAskUser(type, message, callId)
    // In VD mode, WaitingFor* needs capsule shown for user input
    if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
        showPreference = ShowPreference.CAPSULE
        applyVisibility()
    }
}

fun onSessionTakeover() {
    stateHolder.onTakeoverConfirmed()
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}

fun onSessionResumed() {
    stateHolder.onResumed()
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}
```

Also add a mode observer in the controller to call `applyVisibility()` on terminal transitions (Done→Hidden auto-hide):

```kotlin
init {
    statusIslandManager?.startObserving(stateHolder, scope)

    // Observe mode for terminal transitions that affect window visibility
    scope.launch {
        stateHolder.mode.collect { mode ->
            if (mode is CapsuleMode.Hidden) {
                applyVisibility()
            }
        }
    }
}
```

---

## 5. Visibility Invariants (from Codex, adopted)

These must hold at all times after `applyVisibility()`:

| ID | Invariant |
|---|---|
| A | `StatusIsland` and `OverlayCapsule` are never simultaneously visible |
| B | In `MAIN_APP` (our app foreground), no system overlays visible — Compose capsule handles everything |
| C | In Accessibility platform, island is never shown (`showApp` = false, no minimize) |
| D | In VD + background/viewer + task active, exactly one of island/capsule visible per `ShowPreference` |

---

## 6. State Transition Table (Ground Truth)

No changes to `CapsuleStateHolder`. The existing transitions are correct. This table documents the wired (not just defined) transitions after the fixes:

| Current Mode | Event | Guard | Next Mode | Who Calls |
|---|---|---|---|---|
| Hidden | TaskStarted | - | Running | Controller.onTaskStarted |
| Running | ThoughtUpdate | mode is Running | Running(new thought) | Controller.onThoughtUpdate |
| Running | TakeoverRequested (click) | mode is Running | TakeoverPending | Controller.onTakeover callback |
| TakeoverPending/Running | SessionTakeover (event) | mode is TakeoverPending or Running | Takeover | Controller.onSessionTakeover |
| Takeover/TakeoverPending | SessionResumed (event) | mode is Takeover or TakeoverPending | Running("Thinking...") | Controller.onSessionResumed |
| Any | AskUser(QUESTION) | - | WaitingForInput | Controller.onAskUser |
| Any | AskUser(ACTION) | - | WaitingForAction | Controller.onAskUser |
| WaitingForInput/WaitingForAction | UserResponseSent (click) | mode is WaitingFor* | Running("Processing...") | Controller.onUserResponse callback |
| Active states | TaskCompleted | not Hidden/Done/Error | Done or Error | Controller.onTaskCompleted |
| Done | AutoHideTimeout (3s) | mode is Done | Hidden | CapsuleStateHolder.scheduleAutoHide |
| Error | DismissError (click) | mode is Error | Hidden | capsuleManager.onDismissError |
| Any | SessionError | - | Error | Controller.onSessionError |

---

## 7. NavSpec Ground Truth

```kotlin
NavSpec(
    showMinimize = platformMode == VD && hasIsland && context != MAIN_APP,
    showApp     = context != MAIN_APP && platformMode != ACCESSIBILITY,
    showWatch   = platformMode != ACCESSIBILITY && context != SCREEN_VIEWING,
)
```

| Platform | Context | Minimize | App (phone) | Watch (viewer) |
|---|---|---|---|---|
| A11y | MAIN_APP | - | - | - |
| A11y | SCREEN_VIEWING | - | **hidden** | - |
| VD | MAIN_APP | hidden | hidden | shown |
| VD | SCREEN_VIEWING (viewer) | shown | shown | hidden |
| VD | BACKGROUND | shown | shown | shown |

---

## 8. Input/Focus Policy

| Platform | Mode | Row3 Input | Rationale |
|---|---|---|---|
| A11y overlay | Running | Disabled (hint: "Take over to type note") | Avoid focus conflict with agent's screen ops |
| A11y overlay | TakeoverPending | Disabled | Same |
| A11y overlay | Takeover | Enabled | Agent paused; user has control |
| A11y overlay | WaitingForInput | Enabled | Agent explicitly asked for input |
| A11y overlay | WaitingForAction | Not shown (row3 = null) | Agent needs physical action, not text |
| VD overlay | Any | Always enabled | No focus conflict — agent on separate display |
| Compose (in-app) | Any | Always enabled | Same as VD — no conflict |

---

## 9. Implementation Order

| # | Change | Files | Bugs Fixed |
|---|---|---|---|
| 1 | Add `ShowPreference`, `applyVisibility()`, mode observer | `ServiceOverlayController.kt` | 2.4, 2.5, 5.1, 1.4, 3.2 |
| 2 | VD `MAIN_APP` context tracking | `ServiceOverlayController.kt` | 1.4, 3.2, 3.2.1 |
| 3 | Island observer display-only | `StatusIslandManager.kt` | 2.5, 5.1, 5.4 |
| 4 | Refactor all event handlers to use `applyVisibility()` | `ServiceOverlayController.kt` | (structural) |
| 5 | Wire `onUserResponseSent()` from controller | `ServiceOverlayController.kt` (+1 line) | WaitingFor* stuck |
| 6 | Wire `onTakeoverRequested()` from controller | `ServiceOverlayController.kt` (+1 line) | 1.3, 2.1, 3.1 |
| 7 | A11y: remove showApp + Row1 tap | `CapsuleRenderSpec.kt`, `SmartCapsuleManager.kt` | 2.3 |
| 8 | A11y input focus policy | `SmartCapsuleManager.kt` | 2.6, 2.7 |
| 9 | Island tap → open viewer (VD) | `ServiceOverlayController.kt` | 4.2 |
| 10 | Delete `performHandoff()` | `AgentService.kt` | 5.3, 5.4 |
| 11 | NavSpec context guards | `CapsuleRenderSpec.kt` | 3.2.1, 5.2 |
| 12 | Delete dead code | `InputDock.kt`, `ChatMessage.kt`, `ServiceOverlayController.kt`, `SmartCapsuleManager.kt` | 1.5 |
| 13 | Supplement + completion in chat history | `ChatViewModel.kt`, `AgentSession.kt` | 3.4, 3.5 |

---

## 10. Bug Disposition (Complete)

| Bug | Description | Root Cause | Fix | Step |
|---|---|---|---|---|
| 1.1 | Stop works | - | N/A (good) | - |
| 1.2 | Takeover works | - | N/A (good) | - |
| 1.3 | No pending feedback on click | `onTakeoverRequested()` never called | Wire it | 6 |
| 1.4 | Island visible in main app | VD no MAIN_APP tracking | VD context tracking + applyVisibility | 1, 2 |
| 1.5 | Input dock vs capsule Row3 | Already fixed (SmartCapsuleCompose IS bottom bar) | Delete dead InputDock | 12 |
| 2.1 | Same as 1.3 | Same | Same | 6 |
| 2.2 | Add note works in takeover | - | N/A (good) | - |
| 2.3 | Return-to-app disrupts agent | NavSpec shows phone icon in A11y | Remove showApp + Row1 tap in A11y | 7 |
| 2.4 | Capsule/Island not mutually exclusive | Island observer auto-shows | applyVisibility mutual exclusivity | 1, 3 |
| 2.5 | Island always visible | Island observer auto-shows | Island observer display-only | 3 |
| 2.6 | Keyboard covers capsule; agent sees UI | Running allows input in A11y | Input focus policy | 8 |
| 2.7 | Focus conflict with agent | Running allows input in A11y | Input focus policy | 8 |
| 3.1 | Same as 1.3 | Same | Same | 6 |
| 3.2 | Island in main app (VD) | VD no MAIN_APP tracking | VD context tracking | 2 |
| 3.2.1 | Minimize button visible but no-op in main app | NavSpec no context guard | NavSpec guard | 11 |
| 3.3 | Transient render on task end | Done→Hidden auto-hide timing | applyVisibility + mode observer | 1 |
| 3.4 | Completion not in history | ChatViewModel doesn't merge result | Add result to agent message | 13 |
| 3.5 | Supplement not in history | ChatViewModel ignores SupplementReceived | Handle event, add user message | 13 |
| 3.6 | Viewer icon works | - | N/A (good) | - |
| 4.1 | Island shows in background | - | N/A (good) | - |
| 4.2 | Island tap shows capsule over user's apps | Wrong behavior for VD mode | Island tap → open viewer | 9 |
| 5.1 | Island + capsule both visible | Island observer auto-shows | Mutual exclusivity via applyVisibility | 1, 3 |
| 5.2 | Phone icon no-op in VD viewer | Wiring exists but possibly debounce/activity stack issue | Investigate at runtime; NavSpec fix | 11 |
| 5.3 | performHandoff breaks VD apps | Cross-display app launch | Delete performHandoff | 10 |
| 5.4 | Island stuck on "Working..." | Combination: observer auto-show + WaitingFor* stuck + handoff | Fixes 1, 3, 5, 10 together | 1, 3, 5, 10 |
| 5.5 | VD swipe to return works | - | N/A (good) | - |

---

## 11. Decisions Made (Conflict Resolutions)

| Conflict | Decision | Rationale |
|---|---|---|
| State model: keep CapsuleMode vs 5-dimension split | **Keep CapsuleMode** | Existing types express the right things. Bugs are in visibility, not types. Rename churn across ~20 files for zero functional gain. |
| TakeoverPending: optimistic vs PendingCommand | **Optimistic** | `TakeoverPending` already exists as a state with correct render spec. Session has no rejection path. `PendingCommand` adds a parallel state source renderers must check. YAGNI. |
| WaitingForInput ack: 1-line fix vs session events | **1-line fix** | Call `onUserResponseSent()` from controller. `UserResponseChannel` delivery is synchronous. Adding 4-file ack event plumbing is overkill for a problem the 1-line fix solves. |
| A11y showApp: keep icon vs remove | **Remove entirely** | Per Qi: "不该有show app或者row1 tap回app的能力。" Agent needs uninterrupted screen control. |

---

## 12. Out of Scope

| Issue | Why |
|---|---|
| Bug 2.6 (agent sees Smart Capsule UI) | Perception-layer issue. Requires a11y tree filtering to exclude our overlay package. Not a state machine problem. |
| `CapsuleMode.Hidden` dual semantics | In Compose it means "show idle input"; in overlay it means "hide window." Handled correctly by each renderer already. A rename to `Idle` would touch all consumers for cosmetic benefit only. |
| Session-layer ack events (`UserResponseAccepted`, `SessionTakeoverPending`) | Not needed while optimistic transitions work and no rejection paths exist. Revisit if failure modes are added. |
| Full structural decomposition (`TaskUiState/UserSurface/PanelMode/PendingCommand`) | Current types work. Revisit only if state-truth/visibility confusion recurs after this round's fixes. |

---

## 13. Files Changed (Summary)

| File | Changes | Net Lines |
|---|---|---|
| `ServiceOverlayController.kt` | Add `ShowPreference`, `applyVisibility()`, mode observer, VD context tracking, refactor all event handlers, wire `onTakeoverRequested` + `onUserResponseSent`, fix `onIslandTapped`, delete `onMessageDelta` | ~+40, -30 |
| `StatusIslandManager.kt` | Observer: display-only, no show/hide | ~-10 |
| `CapsuleRenderSpec.kt` | `NavSpec.from()`: hide minimize in MAIN_APP, hide showApp in A11y | ~+3 |
| `SmartCapsuleManager.kt` | Disable Row1 tap in A11y, input focus policy per mode+platform, remove duplicate `previousMode` | ~+15, -5 |
| `AgentService.kt` | Delete `performHandoff()` and call site | ~-20 |
| `ChatViewModel.kt` | Handle `SupplementReceived`, merge `TaskCompleted.result` | ~+15 |
| `AgentSession.kt` | Write supplement to `SessionRecordingService` | ~+3 |
| `InputDock.kt` | Delete file | ~-100 |
| `ChatMessage.kt` | Delete `InputState` enum | ~-8 |

**Estimated net**: ~-90 lines (removals exceed additions)

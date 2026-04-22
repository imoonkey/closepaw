# Round 5 Review & Design Proposal

> Author: Claude Code (Opus 4)
> Date: 2026-02-13
> Principle: KISS + Occam's Razor
> Input: qi_bug_note.md (42 findings), round4 system_design_claude.md, round4 review/summary.md, full code review of HEAD vs 339448dd

---

## Part 1: Code Structure Review (Refactoring Opportunities)

After reading the full implementation across 4 rounds, here is what is clean and what needs simplification.

### 1.1 What's Well-Designed (Keep As-Is)

| Component | Why it works |
|---|---|
| `CapsuleMode` sealed interface | Clean state enum. 8 states, each carries exactly the data it needs. No boolean soup. |
| `CapsuleRenderSpec.from()` | Single source of truth for rendering. Both View and Compose read from the same spec. Eliminates divergence. |
| `CapsuleColors` | Centralized palette. One file to change. |
| `CapsuleStateHolder` transition guards | Guards are correct per the state matrix. Invalid events are silently ignored with logs. |
| `SmartCapsuleRenderer` | Pure spec-in, view-properties-out. No business logic. ~188 lines. Clean. |
| `SmartCapsuleAnimator` | Focused on window-level animations. Small, testable. |
| `UserResponseChannel` | Clean suspend-based channel for ask_user flow. |
| `NavSpec.from()` | Context-aware nav button derivation. Correct logic. |
| Observer pattern (SmartCapsuleManager observes stateHolder.mode) | Eliminates push-based rendering. Auto-renders on state change. |

### 1.2 Structural Problems (Refactor Candidates)

#### Problem A: SmartCapsuleManager Does Too Much

**Current**: SmartCapsuleManager (394 lines) is a View-based overlay manager that handles:
1. Window management (show/hide overlay)
2. StateFlow observation
3. Input handling (keyboard show/hide, focus management)
4. Button click routing
5. Supplement flash animation
6. Debouncing
7. Nudge timer
8. Nav context tracking

**KISS Fix**: This is fine for now. The responsibilities are cohesive (they all relate to "overlay capsule lifecycle"). Extracting them would create 3-4 tiny classes that need to cross-reference each other. The 394-line size is acceptable. **No refactor needed.**

#### Problem B: Duplicate Mode Tracking in SmartCapsuleManager

**Current**: `SmartCapsuleManager` maintains its own `currentMode` and `previousMode` fields (lines 63-64), duplicating what `CapsuleStateHolder` already provides via `mode` StateFlow and `previousMode` property.

**KISS Fix**: Remove `SmartCapsuleManager.previousMode`. Use `stateHolder.previousMode` instead. Keep `currentMode` only as a cache for button click handlers (they need to know the current mode synchronously, and reading `stateHolder.mode.value` from the main thread is fine but the observer callback already provides it).

Actually, `currentMode` IS the observer's latest value, so it's justified as a local cache. But `previousMode` is pure duplication. **Minor fix.**

#### Problem C: `StatusIslandManager.startObserving()` Auto-Shows/Hides

**Current** (StatusIslandManager:86-101): The observer calls `show()` if not showing and `hide()` if Hidden. This means the observer drives window visibility, which conflicts with `ServiceOverlayController` also calling `show()`/`hide()`.

**Problem**: Two owners for the same window visibility. The observer calls `show()` on every non-Hidden mode change even when the island SHOULD be hidden (e.g., when the user is viewing the VD viewer and the capsule overlay is showing instead).

This is the root cause of **bug 5.1** (island and capsule both visible simultaneously) and **bug 2.5** (island always visible).

**KISS Fix**: The observer should ONLY update the display text and dot color. It should NOT call `show()`/`hide()`. Window visibility is the controller's job. See Part 2, Fix F2.

#### Problem D: `performHandoff()` Is Harmful

**Current** (AgentService:525-542): On VD task completion with GOAL_ACHIEVED, relaunches the VD's last app on the real screen.

**Problem**: This causes **bug 5.3** (relaunching YouTube on real screen breaks VD playback, dual-instance issues). The VD is a sandboxed environment; cross-launching its apps onto the real screen is fundamentally wrong.

**KISS Fix**: Delete `performHandoff()` entirely. Task completion = task done. The user can manually open any app. **Remove 18 lines.**

#### Problem E: Dead Code Still Present

| Dead Code | File | Action |
|---|---|---|
| `InputDock.kt` | `ui/chat/components/InputDock.kt` | Already `@Deprecated`. Delete file. |
| `InputState` enum | `ui/chat/model/ChatMessage.kt` (lines 175-181) | Already `@Deprecated`. Delete enum. |
| `onMessageDelta()` in controller | `ServiceOverlayController.kt` (lines 201-209) | Both branches are no-ops. Delete method. |

#### Problem F: `CapsuleContext` Not Used By CapsuleStateHolder For Transitions

`CapsuleContext` is stored in `CapsuleStateHolder._context` but never read by any transition guard or rendering logic inside the holder. It's only used externally by `NavSpec.from()`. This is fine architecturally (context is about WHERE to render, not WHAT to render), but storing it in the holder is misleading since the holder doesn't use it for state transitions.

**KISS verdict**: Keep it. It's a convenient place to store context for observers. No refactor needed.

---

## Part 2: State Machine Analysis (Systematic Bug Resolution)

### 2.1 Mapping Bugs to Root Causes

After correlating all 42 bug findings from `qi_bug_note.md` with the code, every bug traces to one of 5 root causes:

| Root Cause | Bugs Explained |
|---|---|
| **R1: Island visibility not exclusive with capsule** | 2.5, 4.2, 5.1 |
| **R2: Island observer auto-shows independently** | 2.5, 5.1 (island reappears after next turn) |
| **R3: No transition feedback on pending actions** | 1.3-overlay, 2.1 |
| **R4: Main app shows island when it shouldn't** | 1.4, 3.2, 3.2.1 |
| **R5: performHandoff() cross-launches apps** | 5.3, 5.4 |
| **R6: Nav buttons not wired / wrong visibility** | 2.3, 3.2.1, 5.2 |
| **R7: Capsule input conflicts with agent in a11y mode** | 2.6, 2.7 |
| **R8: Task completion message not propagated** | 3.4 |
| **R9: Supplement not shown in chat history** | 3.5 |

### 2.2 State Machine Review: Current vs Correct

The state machine in `CapsuleStateHolder` is **fundamentally correct**. The 8 states, the transition guards, and the event matrix from Section 3A of the round4 design are properly implemented. The bugs are NOT in the state transitions themselves. They are in:

1. **Visibility coordination** (which overlay windows are shown/hidden in which context)
2. **Missing UI feedback** for pending operations
3. **Wiring gaps** (nav buttons not connected, handoff logic, etc.)

This is an important distinction: the state machine is sound; the window management around it is not.

### 2.3 Ground Truth: Visibility State Machine

The core problem is that there is no single, authoritative rule for "given (PlatformMode, CapsuleContext, CapsuleMode, userPreference), which overlay windows should be visible?" The current code scatters this logic across:
- `ServiceOverlayController.onTaskStarted()`
- `ServiceOverlayController.onIslandTapped()`
- `ServiceOverlayController.onViewerOpened()`/`onViewerClosed()`
- `ServiceOverlayController.handleWindowStateChangedA11y()`
- `StatusIslandManager.startObserving()` (auto-show)

**Solution: Define the Visibility Truth Table and enforce it from ONE place.**

#### 2.3.1 Visibility Truth Table (Ground Truth)

This table defines which overlay windows are visible for every valid combination. "Capsule" = overlay SmartCapsuleManager. "Island" = StatusIslandManager. "Glow" = EdgeGlowManager. "Compose" = SmartCapsuleCompose (embedded in main app, always present when in-app).

**Key rule**: Capsule and Island are MUTUALLY EXCLUSIVE. At most one is visible at any time.

**New concept: `showPreference`** — tracks whether the user last chose to see the capsule or the island. Defaults to `CAPSULE`. Toggled by minimize button and island tap.

```
enum class ShowPreference { CAPSULE, ISLAND }
```

##### Accessibility Mode

| Context | Has Active Task? | CapsuleMode | Overlay Capsule | Edge Glow | Island |
|---|---|---|---|---|---|
| MAIN_APP | No | Hidden | hidden | hidden | N/A |
| MAIN_APP | Yes | any active | hidden (Compose shows) | hidden | N/A |
| SCREEN_VIEWING | Yes | any active | shown | shown | N/A |
| SCREEN_VIEWING | No | Hidden/Done | hidden | hidden | N/A |

(In A11y mode, there is no island. SCREEN_VIEWING = user left our app. No ShowPreference needed.)

##### Virtual Display Mode

| Context | Has Active Task? | ShowPreference | Overlay Capsule | Island |
|---|---|---|---|---|
| MAIN_APP | No | - | hidden (Compose shows) | hidden |
| MAIN_APP | Yes | - | hidden (Compose shows) | hidden* |
| BACKGROUND | Yes | ISLAND | hidden | shown |
| BACKGROUND | Yes | CAPSULE | shown | hidden |
| BACKGROUND | No | - | hidden | hidden |
| SCREEN_VIEWING | Yes | ISLAND | hidden | shown |
| SCREEN_VIEWING | Yes | CAPSULE | shown | hidden |
| SCREEN_VIEWING | No | - | hidden | hidden |

*Main app: island is unnecessary because Compose capsule already shows all info. Bug 1.4/3.2 fixed.

##### Terminal States (Done, Error)

When `CapsuleMode` transitions to `Done` or `Error`:
- Overlay capsule: stays visible (if was visible), auto-hides after 3s (Done) or on dismiss (Error)
- Island: stays visible (if was visible), updates text to show result
- Glow: updates color (teal for Done, red for Error), auto-hides after 2s (existing behavior)

When `CapsuleMode` transitions to `Hidden` (after auto-hide or dismiss):
- Overlay capsule: hides
- Island: hides
- Glow: hides

#### 2.3.2 ShowPreference Transitions

| Action | ShowPreference becomes |
|---|---|
| User taps minimize button on capsule | ISLAND |
| User taps island | CAPSULE |
| User opens VD viewer | CAPSULE (default for viewer) |
| User closes VD viewer | ISLAND (default for background) |
| Task starts | Reset to default for current context |

### 2.4 Corrected Event x Visibility Matrix

For each event that arrives, here is what happens:

| Event | State Transition | A11y Visibility | VD Visibility |
|---|---|---|---|
| TaskStarted | Hidden -> Running | if !inApp: show capsule+glow | show island (user is in app or background) |
| ThoughtUpdate | Running -> Running | no-op (observer auto-renders) | no-op (observer auto-renders) |
| TurnPhaseChanged | (turnPhase update) | update glow color | no-op |
| ActionExecuted | (no mode change) | update glow | no-op |
| TakeoverRequested (click) | Running -> TakeoverPending | no-op (observer auto-renders) | no-op |
| TakeoverConfirmed (event) | TakeoverPending -> Takeover | update glow | no-op |
| Resumed (event) | Takeover -> Running | update glow | no-op |
| AskUser | -> WaitingFor* | no-op (observer auto-renders) | show capsule if not already showing |
| UserResponse | WaitingFor* -> Running | no-op | no-op |
| TaskCompleted | -> Done/Error | update glow | no-op |
| SessionCompleted | -> Done/Hidden | update/hide glow | no-op |
| (auto-hide timer) | Done -> Hidden | hide capsule+glow | hide island/capsule |
| IslandTapped | (no mode change) | N/A | showPreference=CAPSULE, apply visibility |
| MinimizeClicked | (no mode change) | N/A | showPreference=ISLAND, apply visibility |
| ViewerOpened | (context change) | N/A | showPreference=CAPSULE, apply visibility |
| ViewerClosed | (context change) | N/A | showPreference=ISLAND, apply visibility |
| AppForegrounded | (context=MAIN_APP) | hide capsule+glow | hide island, hide overlay capsule |
| AppBackgrounded | (context=SCREEN_VIEWING) | show capsule+glow (if active) | show island (if active) |

---

## Part 3: Specific Fixes

### Fix F1: Enforce Mutual Exclusivity (Capsule vs Island)

**Root cause of**: 2.5, 5.1

**Current problem**: `StatusIslandManager.startObserving()` calls `show()` on every non-Hidden mode, even when the capsule overlay is visible. This causes both to appear simultaneously.

**Fix**:

1. Remove auto-show from `StatusIslandManager.startObserving()`. The observer should ONLY update display (text + dot color) when the island IS already showing. It should NOT manage its own visibility.

2. Add `applyVisibility()` to `ServiceOverlayController` as the SINGLE place that decides what's visible based on the truth table.

```kotlin
// ServiceOverlayController
private var showPreference = ShowPreference.ISLAND // default

private fun applyVisibility() {
    val mode = stateHolder.mode.value
    val isActive = stateHolder.hasActiveTask || mode is CapsuleMode.Done || mode is CapsuleMode.Error

    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
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
                capsuleManager.hide()
                statusIslandManager?.hide()
            } else {
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

3. Call `applyVisibility()` after every state change and context change.

### Fix F2: StatusIslandManager Observer Only Updates Display

**Root cause of**: 2.5, 5.1 (island reappears after turns)

**Change `startObserving()`**:

```kotlin
fun startObserving(stateHolder: CapsuleStateHolder, scope: CoroutineScope) {
    if (observeJob != null) return
    observeJob = scope.launch {
        stateHolder.mode.collectLatest { mode ->
            // ONLY update display. Do NOT call show()/hide().
            // Visibility is managed by ServiceOverlayController.applyVisibility().
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

### Fix F3: Don't Show Island/Capsule in MAIN_APP When Task Active

**Root cause of**: 1.4, 3.2, 3.2.1

The `applyVisibility()` function above handles this: when `isAppInForeground` is true, all overlays are hidden. The Compose `SmartCapsuleCompose` in the bottom bar already shows all the information.

For VD mode specifically, bug 3.2.1 ("status island" button visible but non-functional in main app) is fixed by `NavSpec.from()` which already returns `showMinimize = false` when `context == CapsuleContext.MAIN_APP` (well, actually it checks `platformMode == VIRTUAL_DISPLAY && hasIsland`, but in main app we should not show minimize). The fix: the minimize button should not appear when `context == CapsuleContext.MAIN_APP`.

**Current NavSpec logic** (CapsuleRenderSpec.kt:159):
```kotlin
showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY && hasIsland,
```

**Fixed**:
```kotlin
showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY
    && hasIsland
    && context != CapsuleContext.MAIN_APP,
```

### Fix F4: Remove "Return to app" From A11y Overlay

**Root cause of**: 2.3

In A11y mode, tapping Row 1 (thought line) calls `onOpenApp`, which opens MainActivity. This changes the screen state and disrupts the agent's perception/action.

**Fix**: In A11y overlay mode, Row 1 tap should be disabled. The user can swipe back to the app naturally.

In `SmartCapsuleManager.show()`:
```kotlin
onRow1Tap = if (platformMode == PlatformMode.ACCESSIBILITY) null else { { onOpenApp?.invoke() } }
```

Wait, `platformMode` is already tracked in the manager. But this should be checked at tap time, not build time, since platform mode could theoretically change. However, it doesn't change during a session, so build-time check is fine.

Actually, looking at the code more carefully, `onRow1Tap` is set at `show()` time via `layoutBuilder.build()`. The manager stores `platformMode` locally. The simplest fix: pass `null` for `onRow1Tap` in A11y mode.

But actually, let me re-check `NavSpec`: `showApp = context != CapsuleContext.MAIN_APP`. In A11y mode, when the capsule overlay is showing, context is `SCREEN_VIEWING`, so `showApp = true`. The phone icon (navApp) IS visible and tappable. Is that also a problem?

Per bug 2.3: "should not have return to app button because it changes screen state and disrupts agent's operations."

The concern is valid for A11y mode: opening the main app DOES change the foreground app, which the agent detects and reacts to. But the user might legitimately want to check progress in the main app.

**KISS Decision**: Keep the phone icon but make Row 1 tap do nothing in A11y mode. The phone icon is an explicit conscious action; Row 1 tap is an accidental gesture target. Removing accidental navigation is sufficient.

### Fix F5: Input Only Allowed in Takeover Mode (A11y)

**Root cause of**: 2.7 (focus conflict), 2.6 (keyboard covers capsule)

In A11y mode, when the agent is running, the user tapping the input field steals focus from the screen, potentially conflicting with the agent's taps.

**Current behavior**: Row 3 input is always available in Running/TakeoverPending/Takeover states (for supplements).

**KISS Fix**: In A11y mode overlay, when `CapsuleMode` is `Running` or `TakeoverPending`:
- Row 3 input should be **read-only** (not focusable). Show "Tap Takeover to add note" as hint.
- In `Takeover` mode, input becomes fully interactive.
- In VD mode, input is always interactive (no focus conflict since agent operates on a different display).

This requires `CapsuleRenderSpec` to know whether the capsule context is A11y overlay. Two approaches:

**(a) Add `inputEnabled` to Row3Spec**: Derived from mode + context.
**(b) SmartCapsuleManager conditionally disables the EditText**: Manager already knows the platformMode.

**KISS choice**: (b). The manager already handles `setOverlayFocusable()`. Just make the EditText non-focusable in Running/TakeoverPending for A11y mode. This keeps the spec context-free (good separation).

```kotlin
// In SmartCapsuleManager.setupInteractivity()
is CapsuleMode.Running, is CapsuleMode.TakeoverPending -> {
    if (platformMode == PlatformMode.ACCESSIBILITY) {
        v.inputEditText.isFocusable = false
        v.inputEditText.isFocusableInTouchMode = false
        v.inputEditText.hint = "Tap Takeover to add note"
    }
}
is CapsuleMode.Takeover -> {
    v.inputEditText.isFocusable = true
    v.inputEditText.isFocusableInTouchMode = true
    v.inputEditText.hint = "Got ideas? Add a note..."
}
```

### Fix F6: Delete performHandoff()

**Root cause of**: 5.3, 5.4

Remove `performHandoff()` entirely from `AgentService`. Task completion should not cross-launch apps between displays. The agent's work product stays on the VD until the user decides to interact with it.

### Fix F7: Wire VD Nav Buttons

**Root cause of**: 5.2 (phone icon no-op in VD viewer)

In `SmartCapsuleManager`, the `onNavApp` callback is wired to `onOpenApp` which opens MainActivity. This should work. Let me check if it's actually wired...

Looking at `ServiceOverlayController` constructor (line 64): `this.onOpenApp = this@ServiceOverlayController.onOpenApp`, which is the `onOpenApp` lambda from AgentService (lines 149-156): launches MainActivity with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`.

The `onOpenViewer` callback (line 73): `this.onOpenViewer = { this@ServiceOverlayController.onOpenViewer?.invoke() }`, which calls `AgentService.openViewer()`.

So the wiring exists. If bug 5.2 says the phone icon does nothing, it might be a click debounce issue (the `debounced()` wrapper with 300ms cooldown), or the icon might not be visible (NavSpec issue).

Let me check NavSpec for VD viewer context:
```kotlin
showApp = context != CapsuleContext.MAIN_APP  // SCREEN_VIEWING != MAIN_APP -> true. OK.
```

And the click handler in the layout builder:
```kotlin
onNavApp = { debounced { onOpenApp?.invoke() } }
```

This looks correct. The bug might be that clicking the phone icon while in VD viewer tries to launch MainActivity but it's already running as a singleTop — the `onNewIntent` is called but nothing visually happens because the VD viewer is on top. The fix is to also `finish()` the viewer activity or bring MainActivity to front explicitly.

**Fix**: `onOpenApp` in the overlay context should:
1. Hide capsule overlay
2. Launch MainActivity (already done)
3. The viewer activity's onStop will fire naturally (the system brings MainActivity to front)

Actually, `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP` SHOULD bring the existing MainActivity to front, pushing the viewer to background. The viewer's `onStop` then fires, which calls `onViewerClosed()`. This should work.

The real issue might be simpler: when the user is on the VD viewer and taps the phone icon, the debounce timer might be blocking it (if they tapped something else recently). Or the click target is too small.

**KISS verdict**: Investigate at runtime. The wiring looks correct in code.

### Fix F8: Transition Feedback for Pending Actions

**Root cause of**: 1.3 (no UI response after clicking stop/takeover)

When the user clicks "Takeover" or "Stop", the click goes through but there's no immediate visual feedback because:
- Takeover: `submitOp(Op.Takeover)` is sent to session, which processes it async. The `SessionTakeover` event comes back later.
- Stop: `submitOp(Op.Shutdown)` is sent.

The round4 review summary suggested using a local `isSubmitting` flag instead of the `TakeoverPending` state. But looking at the actual implementation, `TakeoverPending` IS in the state machine and the `onTakeoverRequested()` transition IS called from `SmartCapsuleManager.handlePrimaryClick()` -> `onTakeover` callback -> `AgentService.submitOp(Op.Takeover)`.

Wait, let me re-trace: The `onTakeover` callback in `ServiceOverlayController` (line 36) directly calls `this@ServiceOverlayController.onTakeover()`, which in `AgentService` (line 145) is `{ submitOp(Op.Takeover) }`. So it just submits the Op. Nobody calls `stateHolder.onTakeoverRequested()`.

The round4 review (Phase 1A) said: clicks should only emit intent (Op), state transitions happen on confirmed session events. And the code follows this: `onTakeover` only submits `Op.Takeover`. But the problem is that there's NO visual feedback until the `SessionTakeover` event comes back. The `TakeoverPending` mode exists in the state machine but is never entered!

**Fix**: The click handler should provide immediate visual feedback. Two options:
1. Call `stateHolder.onTakeoverRequested()` on click (optimistic). If session rejects, revert.
2. Disable the button visually on click (local state, not mode transition).

**KISS choice**: Option 1. Call `onTakeoverRequested()` on click. This transitions to `TakeoverPending`, which shows "Handing over..." with disabled button. When `SessionTakeover` event arrives, it calls `onTakeoverConfirmed()`, transitioning to `Takeover`. If the session somehow rejects (which shouldn't happen in practice), the next `ThoughtUpdate` or `TaskCompleted` will naturally exit the pending state.

```kotlin
// ServiceOverlayController constructor, onTakeover callback:
onTakeover = {
    stateHolder.onTakeoverRequested()  // immediate feedback
    submitOp(Op.Takeover)
},
```

Similarly for Stop, the button should show "Stopping..." feedback. But Stop maps to `Op.Shutdown` which almost always succeeds immediately. The feedback here is that the capsule will transition to Done/Hidden quickly. **No special handling needed for stop.**

### Fix F9: VD Island Tap Should Go To Viewer or Main App

**Root cause of**: 4.2 (island tap shows capsule on main screen, wrong in VD mode)

Current `onIslandTapped()`:
```kotlin
fun onIslandTapped() {
    if (!stateHolder.hasActiveTask) {
        onOpenApp()
        return
    }
    updateContext(ContextTrigger.ISLAND_TAPPED)
    showCapsuleOverlay()
    if (capsuleManager.isShowing()) {
        hideIsland()
    }
}
```

This shows the capsule overlay on the real screen, which in VD mode means the capsule floats over whatever the user is doing (their own apps, not the VD viewer). Bug 4.2 says this is wrong — the user should be taken to the VD viewer or main app.

**KISS Fix**: In VD mode, island tap should open the VD viewer (which then shows the capsule overlay through `onViewerOpened()`). This lets the user see both the agent's screen AND the controls.

```kotlin
fun onIslandTapped() {
    if (!stateHolder.hasActiveTask) {
        onOpenApp()
        return
    }
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            // Can't happen (no island in A11y mode), but just in case:
            updateContext(ContextTrigger.ISLAND_TAPPED)
            showCapsuleOverlay()
            hideIsland()
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            // Open VD viewer (which triggers onViewerOpened -> shows capsule, hides island)
            onOpenViewer?.invoke() ?: onOpenApp()
        }
    }
}
```

---

## Part 4: Proposed Implementation Plan

### Phase 1: Visibility System (Fixes R1, R2, R4 -- bugs 1.4, 2.5, 3.2, 3.2.1, 4.2, 5.1)

1. Add `ShowPreference` enum to `ServiceOverlayController`
2. Add `applyVisibility()` method (truth table implementation)
3. Modify `StatusIslandManager.startObserving()` to NOT auto-show/hide (Fix F2)
4. Replace scattered show/hide calls in controller with `applyVisibility()`
5. Fix `NavSpec.from()` to hide minimize in MAIN_APP (Fix F3)
6. Fix `onIslandTapped()` for VD mode (Fix F9)
7. Also add an `applyVisibility()` call from `CapsuleStateHolder.mode` observer in the controller itself, so terminal state transitions (Done -> Hidden auto-hide) properly hide windows

### Phase 2: Interaction Fixes (bugs 1.3, 2.3, 2.7)

1. Add TakeoverPending feedback on click (Fix F8)
2. Disable Row 1 tap in A11y mode (Fix F4)
3. Disable input in Running/TakeoverPending for A11y overlay (Fix F5)

### Phase 3: Remove Dead/Harmful Code (bugs 5.3, 5.4)

1. Delete `performHandoff()` from AgentService (Fix F6)
2. Delete `InputDock.kt`
3. Delete `InputState` enum from `ChatMessage.kt`
4. Delete no-op `onMessageDelta()` from controller

### Phase 4: Minor Polish (lower priority)

1. Bug 3.4 (complete_task not in history): Verify `recordingService?.completeAgentMessage()` is called and `TaskCompleted.result` has the actual message (round4 Phase 1C should have fixed this)
2. Bug 3.5 (supplement not in chat history): Add supplement as user message in ChatViewModel when `SupplementReceived` event arrives
3. Bug 2.6 (agent sees Smart Capsule UI): This is a perception-layer issue, not a state machine issue. The a11y tree filtering should exclude our app's overlay package. Out of scope for this round.

---

## Part 5: Summary of All Changes

| File | Changes |
|---|---|
| `ServiceOverlayController.kt` | Add `ShowPreference`, add `applyVisibility()`, refactor all event handlers to call `applyVisibility()`, fix `onIslandTapped()` for VD, add takeover feedback, remove `onMessageDelta()` |
| `StatusIslandManager.kt` | `startObserving()` only updates display, does not manage visibility |
| `CapsuleRenderSpec.kt` | `NavSpec.from()` fix for minimize in MAIN_APP |
| `SmartCapsuleManager.kt` | Disable input in A11y Running mode, disable Row1 tap in A11y mode |
| `AgentService.kt` | Delete `performHandoff()` and its call site |
| `InputDock.kt` | Delete file |
| `ChatMessage.kt` | Delete `InputState` enum |

**Estimated net code change**: ~-50 to -80 lines (removals exceed additions)

---

## Appendix: Bug Disposition

| Bug | Priority | Fix | Phase |
|---|---|---|---|
| 1.1 | good | N/A | - |
| 1.2 | good | N/A | - |
| 1.3 | P2 | F8: TakeoverPending on click | Phase 2 |
| 1.4 | P2 | F3: applyVisibility hides island in MAIN_APP | Phase 1 |
| 1.5 | P1 | Already fixed: SmartCapsuleCompose IS the bottom bar in ChatScreen | - |
| 2.1 | same as 1.1-1.3 | same | - |
| 2.2 | good | N/A | - |
| 2.3 | P2 | F4: disable Row1 tap in A11y | Phase 2 |
| 2.4 | P1 | F1: applyVisibility mutual exclusivity | Phase 1 |
| 2.5 | P1 | F1+F2: observer no auto-show | Phase 1 |
| 2.6 | P0* | Out of scope (perception layer) | - |
| 2.7 | P0/P2 | F5: disable input in Running A11y | Phase 2 |
| 3.1 | good | N/A | - |
| 3.2 | P2 | F3: no island in MAIN_APP | Phase 1 |
| 3.2.1 | P2 | F3: NavSpec fix | Phase 1 |
| 3.3 | P1 | Investigate: may be timing issue with Done->Hidden auto-hide. applyVisibility should fix. | Phase 1 |
| 3.4 | P1 | Phase 4: verify completion message propagation | Phase 4 |
| 3.5 | P2 | Phase 4: add supplement to chat history | Phase 4 |
| 3.6 | good | N/A | - |
| 4.1 | good | N/A | - |
| 4.2 | P2 | F9: island tap opens viewer in VD mode | Phase 1 |
| 5.1 | P1 | F1+F2: mutual exclusivity | Phase 1 |
| 5.2 | P2 | F7: investigate wiring | Phase 1 |
| 5.3 | P1 | F6: delete performHandoff | Phase 3 |
| 5.4 | P2 | F6: related to performHandoff | Phase 3 |
| 5.5 | good | N/A | - |
| 5.6 | skipped | N/A | - |

*Bug 2.6 (agent sees Smart Capsule UI) is P0 but requires changes in the perception/a11y tree filtering layer, not in the UI state machine. Out of scope for this round.

### Re: Bug 1.5 — Status

Bug 1.5 says the idle-state input dock and the Smart Capsule Row 3 are different components. Looking at the code: `ChatScreen.kt` uses `SmartCapsuleCompose` as `bottomBar` (line 122). In `CapsuleMode.Hidden`, the spec has `row3 = Row3Spec("What can I help you with?", "Send ->")` and rows 1+2 are hidden via `AnimatedVisibility(visible = isTaskActive)`. So the input IS part of SmartCapsuleCompose and not a separate InputDock. **Bug 1.5 is already fixed.** The old `InputDock.kt` is deprecated and not used. It should be deleted as dead code (Phase 3).

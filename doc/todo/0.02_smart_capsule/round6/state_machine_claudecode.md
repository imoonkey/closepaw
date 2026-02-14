# Smart Capsule State Machine Specification

Formal state machine definition that satisfies all user flows in `user_flow_claudecode.md`.
Iterated until convergence — every user flow maps to a valid state transition path,
and every state transition maps to a documented user flow.

---

## 1. State Dimensions

The Smart Capsule system has **three orthogonal state dimensions**:

### 1A. CapsuleMode (task lifecycle — single sealed interface)

```
Hidden → Running → TakeoverPending → Takeover → Running ...
                ↘ WaitingForInput → Running ...
                ↘ WaitingForAction → Running ...
                ↘ Done → Hidden (auto, 3s)
                ↘ Error → Hidden (dismiss)
```

| Mode | hasActiveTask | Description |
|------|---------------|-------------|
| Hidden | false | No task. Capsule shows Row 3 only (input dock). |
| Running(thought) | true | Agent executing. Thought line updates. |
| TakeoverPending(lastThought) | true | User requested takeover. Waiting for agent to finish current action. |
| Takeover(lastThought) | true | Agent paused. User has control. |
| WaitingForInput(question, callId) | true | Agent asked a question. Expanded. |
| WaitingForAction(instruction, callId) | true | Agent asked user to perform action. Expanded. |
| Done(message) | false | Task completed. Auto-hide after 3s. |
| Error(message) | false | Error occurred. Manual dismiss. |

### 1B. UserLocation (where is the user viewing)

Derived from accessibility events and viewer lifecycle callbacks.

| Value | Description |
|-------|-------------|
| MAIN_APP | User is on our ChatScreen (MainActivity). |
| VD_VIEWER | User is on VirtualDisplayViewerActivity. **VD mode only.** |
| OTHER_APP | User is on any other app or home screen. |

**Key change from current code:** The current `isAppInForeground` boolean conflates
MAIN_APP and VD_VIEWER. This must be split. The current `CapsuleContext` enum
(MAIN_APP, SCREEN_VIEWING, BACKGROUND) maps as follows:

| UserLocation | CapsuleContext |
|--------------|----------------|
| MAIN_APP | MAIN_APP |
| VD_VIEWER | SCREEN_VIEWING |
| OTHER_APP | A11y: SCREEN_VIEWING; VD: BACKGROUND |

### 1C. ShowPreference (VD background only — Island vs Capsule)

| Value | When set |
|-------|----------|
| ISLAND | Default. Set by: onViewerClosed(), onMinimize(), initialization. |
| CAPSULE | Set by: onViewerOpened(), onAskUser(VD), onError(VD), onIslandTapped-while-on-viewer(). |

Only matters in VD mode when `UserLocation = OTHER_APP` or `VD_VIEWER` with active task.

---

## 2. CapsuleMode Transition Table

### 2A. Universal events (accepted from any mode)

| Event | Source mode | Target mode |
|-------|------------|-------------|
| TaskStarted(input) | any | Running(input) |
| Error(message) | any | Error(message) |
| AskUser(QUESTION, q, callId) | any | WaitingForInput(q, callId) |
| AskUser(ACTION, instr, callId) | any | WaitingForAction(instr, callId) |

### 2B. Guarded events (accepted from specific modes only)

| Event | Guard (source mode) | Target mode | Notes |
|-------|-------------------|-------------|-------|
| ThoughtUpdate(t) | Running | Running(t) | Update thought text only |
| TakeoverRequested | Running | TakeoverPending(thought) | Immediate visual feedback |
| TakeoverConfirmed | TakeoverPending or Running | Takeover(thought) | Server confirmed |
| Resumed | Takeover or TakeoverPending | Running("Thinking...") | Agent resumes |
| UserResponseSent(callId) | WaitingForInput or WaitingForAction | Running("Processing...") | User answered |
| TaskCompleted(reason, msg) | Running/TakeoverPending/Takeover/WaitingFor* | Done(msg) or Error | See 2C |
| SessionEnded(reason) | any (but guards apply) | Done/Error/Hidden | See 2D |
| DismissError | Error | Hidden | User clicked Close |

Events on wrong source mode are **silently ignored** (with debug log).

### 2C. TaskCompleted reason mapping

| CompletionReason | Target mode |
|------------------|-------------|
| GOAL_ACHIEVED | Done(message \|\| "Completed") |
| MAX_TURNS | Done("Max steps reached") |
| TASK_IMPOSSIBLE | Done("Task impossible") |
| USER_STOPPED | Done("Stopped") |
| INTERRUPTED | Done("Interrupted") |
| ERROR | Error("Error occurred") |

After Done: schedule auto-hide timer (3s → Hidden).

### 2D. SessionEnded reason mapping

| CompletionReason | Target mode |
|------------------|-------------|
| GOAL_ACHIEVED | Done(preserved message), auto-hide |
| MAX_TURNS | Done("Max steps reached"), auto-hide |
| TASK_IMPOSSIBLE | Done("Task impossible"), auto-hide |
| USER_STOPPED | Hidden (immediate) |
| INTERRUPTED | Hidden (immediate) |
| ERROR | Error("Error occurred") |

---

## 3. Visibility Decision Table

The function `applyVisibility()` is the SINGLE authority on which overlay windows are visible.
Inputs: (PlatformMode, UserLocation, isActive, ShowPreference).

`isActive = hasActiveTask || mode is Done || mode is Error`

### 3A. Accessibility Mode

| UserLocation | isActive | Compose Capsule | Overlay Capsule | Glow | Island |
|--------------|----------|-----------------|-----------------|------|--------|
| MAIN_APP | false | visible (Row3 only) | hidden | hidden | N/A |
| MAIN_APP | true | visible (full) | hidden | hidden | N/A |
| OTHER_APP | false | - | hidden | hidden | N/A |
| OTHER_APP | true | - | **visible** | **visible** | N/A |

A11y never uses Island. A11y never has VD_VIEWER.

### 3B. Virtual Display Mode

| UserLocation | isActive | ShowPref | Compose Capsule | Overlay Capsule | Island |
|--------------|----------|----------|-----------------|-----------------|--------|
| MAIN_APP | false | * | visible (Row3) | hidden | hidden |
| MAIN_APP | true | * | visible (full) | hidden | hidden |
| VD_VIEWER | false | * | - | hidden | hidden |
| VD_VIEWER | true | CAPSULE | - | **visible** | hidden |
| VD_VIEWER | true | ISLAND | - | hidden | **visible** |
| OTHER_APP | false | * | - | hidden | hidden |
| OTHER_APP | true | CAPSULE | - | **visible** | hidden |
| OTHER_APP | true | ISLAND | - | hidden | **visible** |

**Invariants:**
1. Overlay Capsule and Island are NEVER simultaneously visible.
2. In MAIN_APP, only Compose Capsule is used. No system overlays.
3. Compose Capsule visibility is controlled by the ChatScreen layout, not by applyVisibility().

---

## 4. UserLocation Detection

### Current implementation (broken)

```
isAppInForeground = (packageName == ourPackage)
```

This treats both MainActivity and VirtualDisplayViewerActivity as "in foreground" and
hides all overlays. The VD Viewer has no Compose Capsule, so the user gets NO capsule UI.

### Required implementation

```
UserLocation = when {
    className matches VirtualDisplayViewerActivity → VD_VIEWER
    packageName == ourPackage → MAIN_APP
    else → OTHER_APP
}
```

The `handleWindowStateChanged` method receives both `packageName` and `className`.
We can use className to distinguish VD Viewer from Main Activity:

```kotlin
private fun handleWindowStateChangedInternal(packageName: String?, className: String?) {
    // ... existing activity filter ...

    if (packageName == appPackage) {
        // Distinguish between Main App and VD Viewer
        val isViewer = className?.contains("VirtualDisplayViewer") == true
        val newLocation = if (isViewer) UserLocation.VD_VIEWER else UserLocation.MAIN_APP
        // ... update state ...
    } else {
        // Other app
        val newLocation = UserLocation.OTHER_APP
    }
}
```

Implementation detail: We don't need a new enum. We can use:
- `isAppInForeground`: true only for MAIN_APP (not VD Viewer)
- `isViewerVisible`: true for VD_VIEWER

So `isAppInForeground` stays false when VD Viewer is foreground. Instead, `isViewerVisible`
is set to true by detecting the VD Viewer activity class in `handleWindowStateChanged`,
in addition to the explicit `onViewerOpened()` callback.

### Updated applyVisibility for VD mode

```kotlin
PlatformMode.VIRTUAL_DISPLAY -> {
    val isMainApp = isAppInForeground && !isViewerVisible
    if (isMainApp || !isActive) {
        // In main app or no task: Compose capsule handles it
        capsuleManager.hide()
        statusIslandManager?.hide()
    } else {
        // On VD viewer or background: show overlay per preference
        when (showPreference) {
            ShowPreference.CAPSULE -> {
                if (!capsuleManager.isShowing()) capsuleManager.show()
                statusIslandManager?.hide()
            }
            ShowPreference.ISLAND -> {
                capsuleManager.hide()
                if (statusIslandManager?.isShowing() != true) statusIslandManager?.show()
            }
        }
    }
}
```

---

## 5. onIslandTapped Logic

Current behavior (broken): Always calls `onOpenViewer()` in VD mode, which fails when
already on the VD Viewer.

### Required logic

```kotlin
fun onIslandTapped() {
    if (!stateHolder.hasActiveTask && mode !is Done && mode !is Error) {
        onOpenApp()  // No task → just open main app
        return
    }
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            // Shouldn't happen (no island in A11y)
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            if (isViewerVisible) {
                // Already on VD Viewer → just toggle preference
                showPreference = ShowPreference.CAPSULE
                applyVisibility()
            } else {
                // On other app → open VD Viewer
                // Viewer lifecycle will call onViewerOpened() → set CAPSULE
                onOpenViewer?.invoke() ?: onOpenApp()
            }
        }
    }
}
```

---

## 6. NavSpec Ground Truth

Updated to match user flows.

| Context | PlatformMode | Minimize (⊖) | App (📱) | Watch (👁) |
|---------|-------------|---------------|----------|-----------|
| MAIN_APP | A11y | false | false | false |
| MAIN_APP | VD | false | false | true |
| SCREEN_VIEWING (VD Viewer) | VD | true | true | false |
| BACKGROUND (Other App) | VD | true | true | true |
| SCREEN_VIEWING | A11y | false | false | false |

A11y mode: NO nav buttons ever. (Agent controls the screen — navigating away disrupts it.)

VD MAIN_APP: Only 👁 (open VD viewer). No 📱 (already in app). No ⊖ (no island in main app).

VD SCREEN_VIEWING: ⊖ to minimize to island, 📱 to go to main app. No 👁 (already watching).

VD BACKGROUND: All three. ⊖ to minimize, 📱 to open app, 👁 to open viewer.

---

## 7. Input Focus Policy

| Mode | A11y Overlay | VD Overlay | Compose (Main App) |
|------|-------------|-----------|-------------------|
| Hidden | N/A (not visible) | N/A | Row 3 input always enabled |
| Running | **Disabled** (hint: "Take over to type note"). Agent has screen control → focus conflict. | Enabled. VD mode — agent on separate screen. | Enabled |
| TakeoverPending | **Disabled** | Enabled | Enabled |
| Takeover | **Enabled** (agent paused, user has screen) | Enabled | Enabled |
| WaitingForInput | **Enabled** + auto-focus + keyboard | Enabled + auto-focus + keyboard | Enabled + auto-focus |
| WaitingForAction | Disabled (no Row 3) | Disabled (no Row 3) | Disabled (no Row 3) |
| Done | N/A (no Row 3) | N/A (no Row 3) | N/A (no Row 3) |
| Error | N/A (no Row 3) | N/A (no Row 3) | N/A (no Row 3) |

---

## 8. ShowPreference Transitions

| Trigger | New ShowPreference | Rationale |
|---------|-------------------|-----------|
| Initialization | ISLAND | Least intrusive default |
| onViewerOpened() | CAPSULE | Viewer needs full controls |
| onViewerClosed() | ISLAND | Back to compact when leaving viewer |
| onMinimize() (user clicks ⊖) | ISLAND | User explicitly wants compact view |
| onIslandTapped() while on viewer | CAPSULE | User wants to expand controls |
| onAskUser() (VD mode) | CAPSULE | Need input UI for question/action |
| onError() (VD mode) | CAPSULE | Need [Close] button to dismiss |

### Key addition: Force CAPSULE for Error

The current code doesn't force CAPSULE when an error occurs. If the user is in B2.x (background
with island), an error makes the island show "Error: ..." but there's no way to dismiss it
from just the island. Fix: `onSessionError()` in VD mode sets `showPreference = CAPSULE`.

---

## 9. Row 1 Tap Behavior

| PlatformMode | Row 1 Tap Action |
|-------------|------------------|
| A11y | **null** (disabled). Tapping Row 1 would change foreground, disrupting agent. |
| VD | Opens Main App (`onOpenApp()`). Safe because agent is on virtual display. |

---

## 10. Supplement Flow

| Step | Action |
|------|--------|
| 1 | User types in Row 3, clicks "Add note" |
| 2 | `handleRow3Submit()` → mode check: Running/TakeoverPending/Takeover → `onSupplement(text)` |
| 3 | `ServiceOverlayController.onSupplement` → `submitOp(Op.Supplement(text))` |
| 4 | Session emits `AgentEvent.SupplementReceived` |
| 5 | `AgentService.handleEvent` → `overlayController.onSupplementReceived(text)` |
| 6a | A11y: `capsuleManager.flashSupplementConfirmation()` → "Received" flash on thought line |
| 6b | VD: **Should also flash** (currently no-op — fix needed) |
| 7 | ChatViewModel receives SupplementReceived → adds user message to chat history |

**State machine effect:** NONE. Supplement does not change CapsuleMode.
The capsule remains in its current mode. Text is cleared, keyboard hidden.

---

## 11. Chat History Events

| Event | Chat History Effect |
|-------|-------------------|
| TaskStarted(input) | Add User message with input text |
| MessageDelta(delta) | Append to current Agent message streaming text |
| ActionProposed(actionId, tool, desc) | Add Action content block to Agent message |
| ActionExecuted(actionId, success, result) | Update Action block state |
| TaskCompleted(reason, result) | Append completion text to Agent message: `result ?? "Task completed"`. Mark as Complete. |
| SupplementReceived(text) | Add User message: "text" |
| SessionError(error) | Mark Agent message as Complete. Show error banner. |

**Key fix for Bug 2:** TaskCompleted must ALWAYS append a visible completion text,
even when `event.result` is null or blank. Use default "Task completed".

---

## 12. Convergence Verification

Verifying each user flow against the state machine:

### V1: A11y - Other App - Running → Takeover → Supplement → Resume

- Start: mode=Running, location=OTHER_APP, A11y
- Visibility: overlay capsule visible + glow (Table 3A: OTHER_APP + active)
- User clicks [Takeover]: TakeoverRequested → TakeoverPending (guarded: Running → TakeoverPending ✓)
- Visual update: amber dot, "Handing over...", disabled button ✓
- Input: disabled (A11y Running/TakeoverPending → disabled) ✓
- Server confirms: TakeoverConfirmed → Takeover (guarded: TakeoverPending → Takeover ✓)
- Input: re-enabled (A11y Takeover → enabled) ✓
- User types + Add note: supplement sent, no mode change ✓
- Flash confirmation: A11y → flash ✓
- User clicks [Resume]: Resumed → Running (guarded: Takeover → Running ✓)
- Input: re-disabled (A11y Running → disabled) ✓
✅ **Fully covered**

### V2: VD - VD Viewer - Running → Island tap toggle

- Start: mode=Running, location=VD_VIEWER, ShowPref=CAPSULE
- Visibility: overlay capsule visible (Table 3B: VD_VIEWER + active + CAPSULE)
- User clicks ⊖: onMinimize → ShowPref=ISLAND → applyVisibility
- Visibility: island visible, capsule hidden ✓
- User taps island: onIslandTapped, isViewerVisible=true → ShowPref=CAPSULE → applyVisibility
- Visibility: capsule visible, island hidden ✓
✅ **Fully covered** (requires fix to onIslandTapped to detect isViewerVisible)

### V3: VD - VD Viewer - Takeover → Add note → capsule stays

- Start: mode=Takeover, location=VD_VIEWER, ShowPref=CAPSULE
- Visibility: overlay capsule visible (Table 3B: VD_VIEWER + active + CAPSULE) ✓
- User types + Add note: supplement sent, mode stays Takeover
- No applyVisibility call (supplement doesn't change mode or location)
- Capsule remains visible ✓
✅ **Fully covered** (requires VD_VIEWER detection fix so capsule IS visible)

### V4: VD - Other App - WaitingForInput

- Start: mode=WaitingForInput, location=OTHER_APP
- onAskUser forces ShowPref=CAPSULE ✓
- Visibility: overlay capsule visible (Table 3B: OTHER_APP + active + CAPSULE) ✓
- Input focused, keyboard opens ✓
- User types + Send: UserResponseSent → Running ✓
✅ **Fully covered**

### V5: VD - Other App - Error from Island

- Start: mode=Error, location=OTHER_APP
- Previous ShowPref=ISLAND
- onSessionError must force ShowPref=CAPSULE (new requirement) ✓
- Visibility: overlay capsule visible (Table 3B: OTHER_APP + active + CAPSULE) ✓
- User clicks [Close]: DismissError → Hidden → applyVisibility → hide all ✓
✅ **Covered with new force-CAPSULE-on-error fix**

### V6: Task completion → chat history

- TaskCompleted(GOAL_ACHIEVED, null) → mode=Done("Completed")
- ChatViewModel: `result ?? "Task completed"` → always appends text ✓
- Banner: "Task complete" → auto-hide ✓
- Mode auto-hide: Done → Hidden after 3s ✓
✅ **Covered with null-result fix**

### V7: VD - Main App → VD Viewer → Other App → Island → back to VD Viewer

- B1.2: Main App, Running. Compose capsule visible. Click 👁.
- Opens VD Viewer. onViewerOpened → ShowPref=CAPSULE, isViewerVisible=true.
- B3.2: VD Viewer, Running. Overlay capsule visible (VD_VIEWER detection fix).
- User presses Home. Leaves viewer → onViewerClosed → ShowPref=ISLAND, isViewerVisible=false.
- handleWindowStateChanged: other package → isAppInForeground=false, location=OTHER_APP.
- B2.2: Other App, Running. Island visible (ShowPref=ISLAND).
- Tap island → onIslandTapped, isViewerVisible=false → onOpenViewer().
- VD Viewer opens → onViewerOpened → ShowPref=CAPSULE, isViewerVisible=true.
- handleWindowStateChanged: our package, VD Viewer class → isAppInForeground stays false (VD Viewer detection).
- B3.2: Overlay capsule visible. ✓
✅ **Fully covered**

---

## 13. Required Code Changes (Summary)

| # | File | Change | Fixes |
|---|------|--------|-------|
| C1 | ServiceOverlayController | Split `isAppInForeground` to exclude VD Viewer. Use `className` in `handleWindowStateChanged` to detect VD Viewer → set `isViewerVisible=true`, `isAppInForeground=false`. | Bug 1, 3, 4 |
| C2 | ServiceOverlayController | Update `applyVisibility()`: VD mode checks `isAppInForeground && !isViewerVisible` for "main app" condition. | Bug 1, 3, 4 |
| C3 | ServiceOverlayController | Update `onIslandTapped()`: if `isViewerVisible`, toggle ShowPreference directly instead of calling `onOpenViewer()`. | Bug 1, Gap G4 |
| C4 | ServiceOverlayController | `onSessionError()` in VD mode: set `showPreference = CAPSULE` and call `applyVisibility()`. | Gap G2 |
| C5 | ServiceOverlayController | `onSupplementReceived()`: flash confirmation in VD mode too (remove A11y guard). | Gap G3 |
| C6 | ChatViewModel | `handleTaskCompleted()`: use `event.result ?: "Task completed"` instead of only appending when non-blank. | Bug 2 |
| C7 | ServiceOverlayController | Remove dead `ContextTrigger.ISLAND_TAPPED` enum value. | Cleanup |

# Overlay Touchability: Mode-Driven Policy + Gesture Pass-Through Gate

Date: 2026-02-20
Status: Design
Resolves: `ui_suggestions.md` 1.1 (P0) + 1.3 (P1)
Prerequisite: `debug5/dispatchGesture_overlay_intercept_claude.md`

## 1. Problem

Two requirements conflict at the `CapsuleOverlayHost` window:

| Requirement | Needs | Source |
|---|---|---|
| **User touch blocking** during Running | Overlay **touchable** (full-screen shield eats touches) | `ui_suggestions.md` 1.3 |
| **Agent gesture dispatch** (`dispatchGesture`) | Overlay **NOT touchable** (events pass to target app) | `debug5` root cause analysis |

The hotfix (commit `2493be6`) resolved the `dispatchGesture` interception by adding permanent `FLAG_NOT_TOUCHABLE` to `CapsuleOverlayHost`. This unblocked eval (9/14 success in run `20260220_162433`) but introduced two regressions:

1. **Capsule buttons unreachable in overlay context** — user cannot tap Takeover, Resume, Stop, Done, Close, or interact with input fields on the system overlay capsule.
2. **Interaction lock shield broken** — the full-screen touch-eating `View` in `lockTouches` mode is rendered but cannot consume touches because the window itself passes everything through.

### Current cascade order (post-`ac02d35`)

```
ActionPriorityOrder.kt:
  click:      NODE_CLICK → GESTURE_TAP
  longPress:  NODE_LONG_CLICK → GESTURE_LONG_PRESS
  scroll:     A11Y_SCROLL → GESTURE_SWIPE
```

Node-first cascade means gesture is the **fallback** path, so the overlay interception bug is rarely hit in practice. But the gesture path must still work reliably because:
- Coordinate-target clicks have no node fallback (gesture-only)
- Some apps don't expose a11y nodes for all clickable elements
- Future gesture-first cascade is the design target

## 2. Requirements

1. **No dispatchGesture regression** — agent gestures must reach the target app in all modes.
2. **Capsule buttons touchable** — users must be able to tap Takeover/Resume/Stop/Done/Close/input on the overlay capsule.
3. **Interaction lock functional** — during Running in A11y OTHER_APP, user touches to the underlying app must be blocked.
4. **Takeover pass-through** — during Takeover, user touches must reach the underlying app.
5. **VD mode unaffected** — virtual display gestures use `VirtualDisplayInputInjector` (Shizuku), which injects directly to the virtual display. No conflict with the default-display overlay.

## 3. Design

Two orthogonal mechanisms, layered:

```
┌─────────────────────────────────────────────────────┐
│  Layer 1: Mode-Driven Baseline Touchability         │
│  (determines steady-state FLAG_NOT_TOUCHABLE)       │
├─────────────────────────────────────────────────────┤
│  Layer 2: Gesture Pass-Through Gate                 │
│  (temporarily forces FLAG_NOT_TOUCHABLE during      │
│   dispatchGesture, regardless of baseline)          │
└─────────────────────────────────────────────────────┘
```

### 3.1 Layer 1: Mode-Driven Baseline Touchability

Replace the hardcoded `FLAG_NOT_TOUCHABLE` with a mode-driven policy. The touchability of the `CapsuleOverlayHost` window is derived from `CapsuleMode`:

| CapsuleMode | Touchable | Window Size | Rationale |
|---|---|---|---|
| `Running` | YES | MATCH_PARENT | Shield blocks user touches; capsule buttons (Takeover, Stop) accessible above shield |
| `TakeoverPending` | YES | MATCH_PARENT | Shield still active; UI shows "Handing over..." |
| `Takeover` | YES | WRAP_CONTENT | User needs Resume button; touches outside capsule reach underlying app naturally |
| `WaitingForInput` | YES | WRAP_CONTENT | User types text response |
| `WaitingForAction` | YES | WRAP_CONTENT | User taps Done |
| `Done` | YES | WRAP_CONTENT | User can dismiss (also auto-hides) |
| `Error` | YES | WRAP_CONTENT | User taps Close |
| `Hidden` | NO | WRAP_CONTENT | No UI to interact with; pass touches through |

Note: Window size (MATCH_PARENT vs WRAP_CONTENT) is already controlled by `setInteractionLocked()` via `shouldLockUserInteraction()`. The touchability policy aligns with it but is a separate flag dimension.

**Implementation**: `CapsuleOverlayHost` observes `stateHolder.mode` and updates `FLAG_NOT_TOUCHABLE` on the window whenever mode transitions.

```kotlin
// CapsuleOverlayHost
private fun shouldBeTouchable(mode: CapsuleMode): Boolean = when (mode) {
    is CapsuleMode.Hidden -> false
    else -> true
}
```

This is simple because almost all modes need touchability. Only `Hidden` passes through.

**Mode observer** (in `startFocusObserver` or a new observer):

```kotlin
scope.launch {
    stateHolder.mode.collect { mode ->
        applyBaselineTouchability(mode)
    }
}

private fun applyBaselineTouchability(mode: CapsuleMode) {
    if (!composeHost.isShowing()) return
    val touchable = shouldBeTouchable(mode)
    composeHost.updateLayoutParams { params ->
        params.flags = if (touchable) {
            params.flags and FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or FLAG_NOT_TOUCHABLE
        }
    }
}
```

### 3.2 Layer 2: Gesture Pass-Through Gate

During `dispatchGesture`, temporarily force `FLAG_NOT_TOUCHABLE` on the capsule overlay so the injected `MotionEvent`s bypass it and reach the target app. Remove the flag after the gesture completes (or times out).

**Interface**:

```kotlin
// platform/OverlayTouchGate.kt
fun interface OverlayTouchGate {
    /**
     * Temporarily make the capsule overlay non-touchable.
     * Called on Main thread (same as dispatchGesture).
     */
    fun setPassThrough(enabled: Boolean)
}
```

A `fun interface` (SAM) is used instead of `suspend` because:
- Both caller (`dispatchGesture`) and implementation (`updateLayoutParams`) run on Main thread.
- `WindowManager.updateViewLayout()` is synchronous — the flag takes effect immediately for input dispatch.
- No suspension needed; a simple function call is cleaner.

**CapsuleOverlayHost implementation**:

```kotlin
class CapsuleOverlayHost(...) {
    // ...

    /** Gate for AccessibilityGestureInjector to temporarily pass touches through. */
    val touchGate: OverlayTouchGate = OverlayTouchGate { enabled ->
        if (!composeHost.isShowing()) return@OverlayTouchGate
        if (enabled) {
            composeHost.updateLayoutParams { params ->
                params.flags = params.flags or FLAG_NOT_TOUCHABLE
            }
        } else {
            // Restore baseline based on current mode
            applyBaselineTouchability(stateHolder.mode.value)
        }
    }
}
```

**AccessibilityGestureInjector usage**:

```kotlin
class AccessibilityGestureInjector(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null,
    private val overlayTouchGate: OverlayTouchGate? = null,
) {
    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
        overlayTouchGate?.setPassThrough(true)
        try {
            return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    // ... existing callback logic
                }
            } ?: run {
                Log.w(TAG, "Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
                ActionResult.Failure("Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
            }
        } finally {
            overlayTouchGate?.setPassThrough(false)
        }
    }
}
```

### 3.3 VD Mode: No Gate Needed

`VirtualDisplayInputInjector` injects events directly to the virtual display via Shizuku's `InputManager.injectInputEvent()`. These events are display-targeted and never interact with the default-display overlay window. No touch gate is needed.

```
A11y mode:  dispatchGesture → InputDispatcher (default display) → overlay intercepts → NEED GATE
VD mode:    injectInputEvent → InputManager (virtual display)   → no overlay conflict → NO GATE
```

### 3.4 Wiring

The `OverlayTouchGate` is wired from `CapsuleOverlayHost` through `ServiceOverlayController` to `AccessibilityPlatform` to `AccessibilityGestureInjector`:

```
CapsuleOverlayHost.touchGate
  ↓ exposed via
ServiceOverlayController  (new getter: val overlayTouchGate: OverlayTouchGate?)
  ↓ passed to
AccessibilityPlatform constructor  (new param: overlayTouchGate)
  ↓ passed to
AccessibilityGestureInjector constructor  (new param: overlayTouchGate)
```

This is a one-directional dependency chain. The gesture injector calls the gate; it never observes or cares about mode.

### 3.5 Interaction Lock Shield — How It Works With the Gate

During `Running` mode in A11y + OTHER_APP:

```
Steady state:
  Window: MATCH_PARENT, FLAG_NOT_TOUCHABLE = OFF (touchable)
  Full-screen shield View (setOnTouchListener { true }) eats user touches
  Capsule buttons above shield remain interactive

During dispatchGesture (~100-300ms):
  Window: MATCH_PARENT, FLAG_NOT_TOUCHABLE = ON (pass-through)
  Shield cannot eat anything — user touches could theoretically slip through
  Gesture events pass to target app

After gesture completes:
  Window: FLAG_NOT_TOUCHABLE = OFF (touchable again, baseline restored)
  Shield resumes blocking user touches
```

The pass-through window during gesture dispatch is ~100ms for taps, ~300ms for swipes. User touch leaking during this window is acceptable because:
- Users are not actively trying to interact during agent execution
- Even if a touch slips through, it's a single stray event, not a purposeful interaction
- The shield's purpose is to prevent *accidental* interference, not to be a security boundary

### 3.6 IslandOverlayHost

`IslandOverlayHost` does NOT have `FLAG_NOT_TOUCHABLE` (it needs to be tappable to expand to capsule). This is correct — the island is WRAP_CONTENT and only covers its own small area, so it doesn't intercept gestures aimed at other screen locations.

No changes needed for the island.

## 4. State Transition Diagram

```
                  ┌────────────────────────────┐
                  │  CapsuleMode changes       │
                  └────────────┬───────────────┘
                               │
                               ▼
                  ┌────────────────────────────┐
                  │  applyBaselineTouchability  │
                  │  (mode → FLAG decision)     │
                  └────────────┬───────────────┘
                               │
             ┌─────────────────┼──────────────────┐
             │ Hidden          │ All other modes   │
             ▼                 ▼                   │
      FLAG_NOT_TOUCHABLE    remove FLAG            │
      (pass-through)        (touchable)            │
                               │                   │
                               ▼                   │
                  ┌────────────────────────────┐   │
                  │  dispatchGesture called     │   │
                  └────────────┬───────────────┘   │
                               │                   │
                               ▼                   │
                  ┌────────────────────────────┐   │
                  │  setPassThrough(true)       │   │
                  │  → force FLAG_NOT_TOUCHABLE │   │
                  └────────────┬───────────────┘   │
                               │                   │
                               ▼                   │
                  ┌────────────────────────────┐   │
                  │  gesture completes/fails    │   │
                  └────────────┬───────────────┘   │
                               │                   │
                               ▼                   │
                  ┌────────────────────────────┐   │
                  │  setPassThrough(false)      │   │
                  │  → restore baseline         │───┘
                  └────────────────────────────┘
```

## 5. Implementation Plan

### Step 1: Add `OverlayTouchGate` interface

New file: `platform/OverlayTouchGate.kt`

```kotlin
package com.moonkey.androidagent.platform

fun interface OverlayTouchGate {
    fun setPassThrough(enabled: Boolean)
}
```

### Step 2: Mode-driven touchability in `CapsuleOverlayHost`

1. Remove hardcoded `FLAG_NOT_TOUCHABLE` from `createLayoutParams()` (revert the `2493be6` one-line change).
2. Add `shouldBeTouchable(mode)` function.
3. Add `applyBaselineTouchability(mode)` function.
4. Add mode observer in `show()` that calls `applyBaselineTouchability` on mode changes.
5. Expose `touchGate: OverlayTouchGate` property.

### Step 3: Wire gate through `AccessibilityGestureInjector`

1. Add `overlayTouchGate: OverlayTouchGate?` constructor parameter to `AccessibilityGestureInjector`.
2. Wrap `dispatchGesture` body in `try/finally` with `setPassThrough(true/false)`.
3. Thread the gate from `ServiceOverlayController` → `AccessibilityPlatform` → `AccessibilityGestureInjector`.

### Step 4: Expose gate from `ServiceOverlayController`

1. Add `val overlayTouchGate: OverlayTouchGate? get() = capsuleManager.touchGate`.
2. `AgentService` passes this to `AccessibilityPlatform` when constructing the platform.

### Step 5: Verify

1. **Build**: `./gradlew assembleDebug`
2. **Eval run**: confirm gesture-first cascade still works (no `dispatchGesture` regression).
3. **Manual test (A11y mode)**:
   - Running: capsule Takeover/Stop buttons respond to touch.
   - Running: touches to underlying app are blocked by shield.
   - WaitingForInput: text field is interactive.
   - Takeover: Resume button works; underlying app receives touches.
   - Hidden: touches pass through to underlying app.
4. **Manual test (VD mode)**: no behavioral change expected.

## 6. Risks

### R1: User touch leak during gesture pass-through window

**Severity**: Low
**Window**: ~100ms (tap), ~300ms (swipe), ~500ms+ (long press)
**Mitigation**: Acceptable tradeoff. The shield prevents *accidental* interference, not adversarial input. The pass-through window is brief and occurs when the agent is actively performing an action that will change the screen anyway.

### R2: Race between mode transition and gesture pass-through

**Scenario**: Mode changes from Running to Takeover while a gesture is mid-dispatch. `setPassThrough(false)` in `finally` calls `applyBaselineTouchability` which reads the new mode (Takeover, touchable) — correct behavior, no issue.

**Scenario**: Mode changes to Hidden while gesture is mid-dispatch. `finally` restores baseline → Hidden → FLAG_NOT_TOUCHABLE. This is correct — the gesture was already dispatched.

**Verdict**: No race risk. Both the mode observer and the gate operate on Main thread, so they cannot interleave within a single handler.

### R3: Gate called when overlay is not showing

**Mitigation**: Both `applyBaselineTouchability` and `touchGate` lambda check `composeHost.isShowing()` and no-op if the overlay is hidden. Safe.

### R4: `dispatchGesture onCompleted` false success persists

The original debug5 finding noted that `dispatchGesture` reports `onCompleted()` even when events are consumed by the overlay (delivered to *a* window, just not the right one). The gate approach eliminates this by ensuring the overlay is non-touchable during dispatch, so events are routed to the correct window. However, if the gate fails to take effect (e.g., `updateViewLayout` not processed before `dispatchGesture`), the false-success problem would return.

**Mitigation**: `WindowManager.updateViewLayout()` is a synchronous binder call. The flag change takes effect in the system server's window list immediately. Subsequent `dispatchGesture` calls will see the updated window flags. No timing gap.

## 7. Files Changed

| File | Change |
|---|---|
| `platform/OverlayTouchGate.kt` | **New** — `fun interface OverlayTouchGate` |
| `ui/overlay/compose/CapsuleOverlayHost.kt` | Remove hardcoded `FLAG_NOT_TOUCHABLE`, add mode observer + `touchGate` |
| `platform/AccessibilityGestureInjector.kt` | Add `overlayTouchGate` param, wrap `dispatchGesture` |
| `app/ServiceOverlayController.kt` | Expose `overlayTouchGate` getter |
| `platform/AccessibilityPlatform.kt` | Thread `overlayTouchGate` to gesture injector |
| `app/AgentService.kt` | Pass `overlayTouchGate` when constructing `AccessibilityPlatform` |
| `doc/main/ui/overlay.md` | Update touchability section |

## 8. Relationship to ActionPriorityOrder

This design is **independent** of the cascade order. Whether gesture is primary or fallback, the gate ensures `dispatchGesture` works correctly. This means:

- Current node-first cascade (`ac02d35`): works. Gesture fallback hits the gate when needed.
- Future gesture-first cascade: works. All gestures hit the gate.
- Mixed per-action cascades: works. Gate is at the `dispatchGesture` call site, not the executor.

The `ActionPriorityOrder.kt` can be freely adjusted without touching the touchability system.

# dispatchGesture Blocked by Capsule Overlay (lockTouches)

## Summary

ALL `dispatchGesture` calls (tap, swipe, long press) fail silently during eval because the capsule overlay's full-screen touch-intercepting `View` sits on top of the target app and consumes the injected `MotionEvent`s. `dispatchGesture` reports `onCompleted()` because events were delivered to a window — just not the right one.

## Evidence

### Eval run: `eval/results/20260220_141111` (SystemBrightnessMax, 0/2)

| Time | Action | Dispatch method | Screen changed? |
|------|--------|----------------|-----------------|
| 14:11:48 | `system_button HOME` | `performGlobalAction` | YES (→ launcher, 14 elements) |
| 14:11:48 | **Capsule overlay shown** (lockTouches=true, MATCH_PARENT) | — | — |
| 14:11:52 | Swipe (540,100)→(540,800) — open quick settings | `dispatchGesture` | **NO** (17 → 17) |
| 14:12:00 | Swipe (492,463)→(492,900) — scroll | `dispatchGesture` | **NO** (17 → 17) |
| 14:12:08 | `system_button HOME` | `performGlobalAction` | YES |
| 14:12:12 | `open_app Settings` | `startActivity()` | YES (→ Settings, 25 elements) |
| 14:12:26 | Scroll down via a11y | `node.performAction(SCROLL)` | YES (29 elements) |
| 14:12:28 | Tap (540,872) — click Display | `dispatchGesture` | **NO** (29 → 29) |
| 14:12:41+ | Taps at 540,872 / 275,846 / 540,238 (×10+) | `dispatchGesture` | **NO** (all fail) |

Pattern: `performGlobalAction`, `startActivity`, `node.performAction` all work. Every `dispatchGesture` call fails.

### Action-test comparison

`./scripts/action-test.sh click --use-node false --x 540 --y 872` on the same Settings page works reliably.

Difference: `ActionDebugReceiver` rejects execution when there's an active session (`ActionDebugReceiver.kt:34`). No active session → `shouldLockUserInteraction` returns false → capsule overlay not in lockTouches mode → `dispatchGesture` reaches the target app.

## Root Cause Chain

```
Agent session active
  → location == OTHER_APP (Settings visible)
  → shouldLockUserInteraction(ACCESSIBILITY, OTHER_APP, <active mode>) == true
    (OverlayLocationPolicy.kt:144)
  → capsuleManager.setInteractionLocked(true)
    (ServiceOverlayController.kt:147)
  → CapsuleOverlayHost window becomes MATCH_PARENT
    (CapsuleOverlayHost.kt:197)
  → Full-screen View with setOnTouchListener { _, _ -> true }
    (CapsuleOverlayHost.kt:138-139)
  → TYPE_ACCESSIBILITY_OVERLAY window, NO FLAG_NOT_TOUCHABLE
    (CapsuleOverlayHost.kt:266-273)
  → InputDispatcher routes dispatchGesture MotionEvents to overlay (topmost touchable window)
  → Overlay View consumes events → target app never receives them
  → dispatchGesture callback fires onCompleted() (events delivered to A window)
  → Agent reports "Success: Tapped via gesture_tap" — false success
```

### Window params (the missing flag)

```kotlin
// CapsuleOverlayHost.kt:261-278
WindowManager.LayoutParams(
    MATCH_PARENT,
    MATCH_PARENT,                              // full screen when locked
    TYPE_ACCESSIBILITY_OVERLAY,                // topmost Z-order
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN, // ← NO FLAG_NOT_TOUCHABLE
    PixelFormat.TRANSLUCENT
)
```

Compare with `VisualizerOverlayHost.kt:141-143` (which correctly passes through touches):
```kotlin
FLAG_NOT_FOCUSABLE or
    FLAG_NOT_TOUCHABLE or                      // ← HAS FLAG_NOT_TOUCHABLE
    FLAG_LAYOUT_IN_SCREEN,
```

And `GlowOverlayHost` also has `FLAG_NOT_TOUCHABLE`.

Only `CapsuleOverlayHost` and `IslandOverlayHost` are missing it.

## What works vs what doesn't during eval

| Works | Dispatch method | Why |
|-------|----------------|-----|
| `system_button` (HOME/BACK) | `performGlobalAction()` | Framework call, not input injection |
| `open_app` | `startActivity()` | Intent launch, not input injection |
| a11y scroll (ACTION_SCROLL_*) | `node.performAction()` | Direct node action, bypasses input pipeline |
| a11y click (ACTION_CLICK) | `node.performAction()` | Direct node action, bypasses input pipeline |

| Fails | Dispatch method | Why |
|-------|----------------|-----|
| gesture tap | `dispatchGesture` → `InputManager.injectInputEvent` | Overlay intercepts |
| gesture swipe | `dispatchGesture` → `InputManager.injectInputEvent` | Overlay intercepts |
| gesture long press | `dispatchGesture` → `InputManager.injectInputEvent` | Overlay intercepts |

## Why pre-Phase-2 code appeared to work

Pre-Phase-2 cascade order: node action first → gesture fallback.

- Click: tried `ACTION_CLICK` first (works, bypasses overlay) → gesture fallback rarely triggered
- Scroll: tried `ACTION_SCROLL` first (works) → gesture fallback rarely triggered
- Long press: tried `ACTION_LONG_CLICK` first (works) → gesture fallback rarely triggered

Post-Phase-2: gesture first → node fallback.

- Click: tried `dispatchGesture` tap first → overlay intercepts → reports "success" → **node fallback never reached**
- Scroll: (reverted to a11y-first after regression) → works
- Long press: same issue as click

The overlay has ALWAYS blocked `dispatchGesture`. Phase 2 just exposed it by making gesture the primary path.

## Why the passing eval (20260220_140905) passed

The agent happened to use quick settings approach:
1. `system_button HOME` (performGlobalAction — works)
2. Navigated through non-gesture paths
3. Never needed gesture tap on Settings list items

Lucky path that avoided the bug.

## Fix options

### Option A: Temporarily set FLAG_NOT_TOUCHABLE during gesture dispatch

In `AccessibilityGestureInjector.dispatchGesture()`, notify the overlay controller to add `FLAG_NOT_TOUCHABLE` before dispatching and remove it after. This lets agent gestures pass through while still blocking real user touches between gesture dispatches.

Pros: Preserves user touch blocking intent. Gesture-first cascade works correctly.
Cons: Requires wiring gesture injector to overlay controller. Brief window where user touches could slip through (during the ~100ms gesture).

### Option B: Revert to node-first cascade (undo Phase 2 for click/long_press)

Simplest fix. Node actions bypass the overlay entirely.

Pros: Zero risk. Proven to work.
Cons: Defeats Phase 2 goal. Gesture path is never exercised.

### Option C: Redesign lockTouches to not use overlay touch interception

Instead of a full-screen `View` that eats touches, use `FLAG_NOT_TOUCHABLE` on ALL windows and block user interaction via a different mechanism (e.g., ignore user-initiated accessibility events, or use `requestInterceptKeyEvents`).

Pros: Clean separation between user touches and agent gestures.
Cons: Significant redesign. May not be possible with Android's accessibility APIs.

### Option D: Use FLAG_NOT_TOUCHABLE always + block user by other means

Set `FLAG_NOT_TOUCHABLE` on the capsule overlay window always. Prevent user interference via different mechanism (e.g., the capsule UI is still rendered for visual feedback, but user taps don't trigger actions because callbacks are gated by session state).

Pros: `dispatchGesture` always works. Overlay is purely visual.
Cons: Users CAN interact with the underlying app during agent execution (which lockTouches was designed to prevent).

## Recommendation

Option A is the best balance. The capsule overlay's touch blocking is intentional -- it prevents users from accidentally interfering with agent actions. But it shouldn't block the agent's OWN gestures.

Implementation sketch:
```kotlin
// AccessibilityGestureInjector
private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
    overlayTouchGate?.setPassThrough(true)   // FLAG_NOT_TOUCHABLE
    try {
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { ... }
            ?: ActionResult.Failure("Gesture timed out")
    } finally {
        overlayTouchGate?.setPassThrough(false) // remove FLAG_NOT_TOUCHABLE
    }
}
```

However, if the user touch blocking during gesture dispatch is not a concern (gestures are fast, ~100ms), Option D (always FLAG_NOT_TOUCHABLE) is simpler.

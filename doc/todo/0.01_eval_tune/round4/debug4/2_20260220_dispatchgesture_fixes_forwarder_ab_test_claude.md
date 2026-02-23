# dispatchGesture Flakiness: Fixes, Reverts & AccessibilityForwarder A/B Test

**Date**: 2026-02-20
**Context**: Debugging flaky `dispatchGesture()` on BrightnessDialog SeekBar
**Tasks**: SystemBrightnessMax, SystemBrightnessMin

## Background

Two consecutive eval runs (025115 and 025322) with identical code produced different results: both passed in 025115, BrightnessMin failed in 025322. The swipe gesture reported "Success" via callback but had zero effect on the SeekBar value.

This doc covers three areas of work:
1. Reverting `AccessibilityGestureInjector` regressions from prior debug session
2. Adding right-edge inset in `AccessibilityPlatform` to avoid gesture-nav interception
3. A/B testing `AccessibilityForwarder` as a potential interference source

---

## 1. AccessibilityGestureInjector — Revert Regressions

Prior debug session (commit `e745664`) introduced two changes that were identified as regression causes:

### Reverted: `setDisplayId()`

```kotlin
// BEFORE (regressed):
private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
    val builder = GestureDescription.Builder().addStroke(stroke)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val displayId = resolveDisplayId() ?: Display.DEFAULT_DISPLAY
        builder.setDisplayId(displayId)
    }
    return builder.build()
}

// AFTER (reverted to baseline):
private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
    return GestureDescription.Builder().addStroke(stroke).build()
}
```

**Why reverted**: `setDisplayId()` was added thinking it would help target the correct display, but it caused gestures to fail silently on some emulator configurations. The default behavior (no explicit display ID) works correctly.

### Reverted: `Handler(Looper.getMainLooper())`

```kotlin
// BEFORE (regressed):
val dispatched = service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))

// AFTER (reverted to baseline):
val dispatched = service.dispatchGesture(gesture, callback, null)
```

**Why reverted**: Passing a main-thread Handler forces the gesture callback to be delivered on the main looper. With `null`, the framework uses its own internal handler. The explicit Handler added unnecessary main-thread contention and was correlated with increased gesture failures.

### Reverted: Tap duration to fixed 100ms

```kotlin
// BEFORE (regressed):
private val DEFAULT_TAP_DURATION_MS = ViewConfiguration.getTapTimeout().toLong()

// AFTER (reverted to baseline):
private const val DEFAULT_GESTURE_DURATION_MS = 100L
```

**Why reverted**: `ViewConfiguration.getTapTimeout()` returns the system's tap recognition threshold, not an optimal gesture injection duration. Using a fixed 100ms is more predictable.

### Also reverted: Verbose diagnostic logging

Removed per-gesture `rootInActiveWindow`, `serviceInfo.flags`, `serviceInfo.capabilities` logging that was added for debugging. The compact `dispatched=$dispatched, ${describeGesture(gesture)}` format is sufficient.

---

## 2. AccessibilityPlatform — Right-Edge Inset for Leftward Swipes

Android's gesture navigation intercepts `ACTION_DOWN` events near the right screen edge for "back" gesture recognition. This causes `dispatchGesture()` swipes that start from the right edge to be silently consumed by `InputDispatcher [Gesture Monitor]` instead of reaching the target UI element.

### Fix: Inset start X by 30dp from right edge (leftward swipes only)

```kotlin
companion object {
    private const val EDGE_INSET_DP = 30
}

private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
    val edgeInsetPx = (EDGE_INSET_DP * display.density).toInt()
    var startX = action.startX.coerceIn(0, maxX)
    // ...
    if (action.endX < action.startX && startX > maxX - edgeInsetPx) {
        startX = maxX - edgeInsetPx
    }
}
```

**Key design decisions**:
- **Right-edge only**: Left-edge rightward swipes are empirically NOT intercepted and must NOT be inset — the exact edge coordinate (e.g. x=42 for SeekBar left endpoint) is needed to hit UI elements flush with the screen edge.
- **Only when swiping left** (`endX < startX`): A rightward swipe starting near the right edge shouldn't be inset since it's moving away from the edge.
- **30dp**: Matches Android's default `gestureInsets` for back gesture zones.

### Iteration history
- v1 (bilateral): Inset both edges — broke left-edge SeekBar swipes
- v2 (directional): Inset based on swipe direction on both edges — still broke left-edge
- v3 (right-edge only, current): Only inset right edge for leftward swipes — works correctly

---

## 3. AccessibilityForwarder Investigation

### What is AccessibilityForwarder?

A **read-only observation service** from Google DeepMind's android_env project:

- Subscribes to `typeAllMask` (every accessibility event type)
- Every 100ms traverses the full accessibility node tree, serializes to protobuf
- Forwards events + tree snapshots via **gRPC** to host-side Python process
- Used by Python-based Android World agents to "see" the screen remotely

**Our agent doesn't need it** — we have our own native `AgentService` that reads the accessibility tree directly on-device.

### Potential interference mechanisms (theoretical)

1. **Binder contention**: Both services query `AccessibilityNodeInfo` via shared Binder IPC; concurrent tree traversals can return stale/null data
2. **Event queue blocking**: `onAccessibilityEvent` does blocking gRPC calls (1s timeout); if gRPC server is down, backs up the framework's event queue
3. **Crash dialogs**: When gRPC is misconfigured, produces "keeps stopping" dialogs that pollute the UI

### A/B Test Design

Modified `native_agent_bridge.py:_ensure_accessibility_service()` to strip AccessibilityForwarder from `enabled_accessibility_services` before each task (WITHOUT condition), vs. preserving the default behavior of appending our service to the existing list (WITH condition).

### Results

| Run | Forwarder | BrightnessMax | BrightnessMin | Notes |
|-----|-----------|---------------|---------------|-------|
| 025115 | WITH | PASS | PASS | baseline |
| 025322 | WITH | PASS | FAIL | swipe no effect (13 actual turns, 0 reported) |
| 033659 | WITHOUT | PASS | PASS | first WITHOUT test |
| 034104 | WITH | STUCK | N/A | agent hung after init (ANR/freeze) |
| 035422 | WITH | PASS | PASS | retry after stuck |
| 035747 | WITHOUT | FAIL | FAIL | Max: 30 turns swipe no effect; Min: timeout/ANR |
| 114830 | WITHOUT | FAIL | PASS | Max: 0 turns reported (trace bug), 134s |
| 115544 | WITH | PASS | PASS | both passed |

### Aggregate

| Condition | Runs | All-pass | Partial/fail | All-pass rate |
|-----------|------|----------|-------------|---------------|
| WITH forwarder | 4 (excl. stuck) | 3 | 1 | 75% |
| WITHOUT forwarder | 3 | 1 | 2 | 33% |

### Conclusion

**AccessibilityForwarder is NOT the root cause of dispatchGesture flakiness.**

Failures occur both with and without the forwarder. The flakiness has multiple independent causes:

1. **Platform-level dispatchGesture unreliability on SeekBar** — identical coordinates, identical code, gesture callback reports success, but SeekBar value doesn't change. This is the primary issue and is not related to AccessibilityForwarder.

2. **Agent ANR / process freezing** — the agent app itself sometimes becomes unresponsive (seen in runs 034104 and 035747-Min), producing ANR dialogs that block progress.

3. **FileTraceRecorder channel overflow** — trace recording buffer overflows during shutdown, `session_stopped` event gets dropped, eval parser reports 0 turns. Separate infra bug.

### Decision

**Strip AccessibilityForwarder by default.** Although it's not the root cause of flakiness, it is:
- Unnecessary for our native agent (we don't use the gRPC observation path)
- A source of CPU/Binder overhead (100ms tree traversal polling)
- A potential source of crash dialogs when gRPC is misconfigured
- One fewer variable in debugging

### Code Change

`eval/aw_bridge/native_agent_bridge.py` — `_ensure_accessibility_service()`:

```python
# Strip other accessibility services (e.g. AccessibilityForwarder)
# that Android World env setup enables.  We don't use the gRPC
# observation path — our agent has its own native a11y service —
# so the forwarder is unnecessary overhead and a source of Binder
# contention, blocking gRPC calls in onAccessibilityEvent, and
# "keeps stopping" crash dialogs.
if current and current != "null":
    parts = [p for p in current.split(":") if p == self._A11Y_SERVICE]
    current = ":".join(parts)
```

## Remaining Open Issues

1. **dispatchGesture SeekBar flakiness** — platform-level; potential fix paths:
   - Use `AccessibilityNodeInfo.ACTION_SET_PROGRESS` (API 24+) for SeekBar widgets
   - Use `ACTION_SCROLL_FORWARD/BACKWARD` as fallback
   - Both bypass touch injection entirely

2. **FileTraceRecorder channel overflow** — eval infra bug causing 0-turn reporting

3. **Agent ANR** — investigate main-thread work during LLM response processing

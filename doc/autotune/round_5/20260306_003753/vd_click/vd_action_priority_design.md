# VD Action Priority Design

> **Status: Not proceeding.** Experiment (`doc/main/infra/tool/click_transport_experiment.md`) showed Shizuku `injectInputEvent` false-succeeds on Files RecyclerView (0/3), invalidating the core premise that VD tap is more reliable than node click. Current priority `node_click → gesture_tap` confirmed correct for both A11Y and VD modes.

## Problem

The current `ActionPriorityOrder` is a static, platform-agnostic config:

```kotlin
object ActionPriorityOrder {
    val click = listOf(NODE_CLICK, GESTURE_TAP)      // node first
    val longPress = listOf(NODE_LONG_CLICK, GESTURE_LONG_PRESS)
    val scroll = listOf(A11Y_SCROLL, GESTURE_SWIPE)
}
```

This order is correct for **Accessibility mode** but suboptimal for **Virtual Display mode**.

### Why

Two fundamentally different "tap" implementations exist:

| Channel | Accessibility Mode | Virtual Display Mode |
|---|---|---|
| `GESTURE_TAP` | `dispatchGesture()` — a11y gesture injection | `injectInputEvent()` — real MotionEvent via Shizuku IInputManager |
| `NODE_CLICK` | `performAction(ACTION_CLICK)` — same in both modes | same |

The critical difference: **`dispatchGesture()` produces false successes** on certain views (e.g., DocumentsUI RecyclerView items report success but don't respond), while VD's `injectInputEvent()` delivers real `MotionEvent(ACTION_DOWN/UP, SOURCE_TOUCHSCREEN)` to the window's `InputDispatcher` — equivalent to a physical finger tap.

### Evidence (Round 5 Debug)

On Files app RecyclerView items:

| Path | API | Result |
|---|---|---|
| `node_action_click` | `performAction(ACTION_CLICK)` | Works (warm app) |
| `gesture_tap` (a11y) | `AccessibilityService.dispatchGesture()` | **FALSE SUCCESS** — action_accepted=success, ui_changed=unchanged |
| `adb input tap` | `InputManager` from shell | Works (warm app) |
| VD `injectTap` | `IInputManager.injectInputEvent()` via Shizuku | Expected to work (same InputDispatcher pipeline as adb) |

In VD mode, `GESTURE_TAP` maps to `injectInputEvent()` which is a **real touch event** — fundamentally more reliable than `dispatchGesture()`. It should be tried **first**, not as fallback.

## Current Architecture

```
ClickExecutor.execute()
  → ActionPriorityOrder.click          // static list
  → maps each channel to ChannelAttempt
  → executePointAction() tries channels in order
```

- `ClickExecutor`, `LongPressExecutor`, `ScrollExecutor` all read from `ActionPriorityOrder.*`
- `ActionPriorityOrder` is a singleton `object` with `val` lists
- Platform dispatch happens downstream in `AccessibilityPlatform.performAction()` / `VirtualDisplayPlatform.dispatchAction()`

## Design Options

### Option A: Platform-Parameterized Priority

Make `ActionPriorityOrder` accept a platform mode flag.

```kotlin
object ActionPriorityOrder {
    enum class PlatformMode { ACCESSIBILITY, VIRTUAL_DISPLAY }

    fun click(mode: PlatformMode) = when (mode) {
        ACCESSIBILITY    -> listOf(NODE_CLICK, GESTURE_TAP)
        VIRTUAL_DISPLAY  -> listOf(GESTURE_TAP, NODE_CLICK)  // tap first in VD
    }

    fun longPress(mode: PlatformMode) = when (mode) {
        ACCESSIBILITY    -> listOf(NODE_LONG_CLICK, GESTURE_LONG_PRESS)
        VIRTUAL_DISPLAY  -> listOf(GESTURE_LONG_PRESS, NODE_LONG_CLICK)
    }

    fun scroll(mode: PlatformMode) = when (mode) {
        ACCESSIBILITY    -> listOf(A11Y_SCROLL, GESTURE_SWIPE)
        VIRTUAL_DISPLAY  -> listOf(A11Y_SCROLL, GESTURE_SWIPE)  // same — a11y scroll works
    }
}
```

**Executors** receive mode from the tool layer (which knows the platform):

```kotlin
class ClickExecutor {
    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        val mode = if (platform is VirtualDisplayPlatform)
            PlatformMode.VIRTUAL_DISPLAY else PlatformMode.ACCESSIBILITY
        val channels = ActionPriorityOrder.click(mode)
        // ... rest unchanged
    }
}
```

**Pros**: Minimal change. Priority logic stays centralized. No new abstractions.
**Cons**: Executor must know platform type (slight coupling).

### Option B: Platform Provides Priority

Each `AndroidPlatform` exposes its preferred priority order.

```kotlin
interface AndroidPlatform {
    // existing methods...
    val actionPriority: ActionPriority  // new
}

data class ActionPriority(
    val click: List<ClickChannel>,
    val longPress: List<LongPressChannel>,
    val scroll: List<ScrollChannel>
)
```

**Pros**: Zero coupling — executor just asks platform.
**Cons**: Over-abstraction for two modes. Priority config scattered across platform impls.

### Recommendation: Option A

Option A keeps the priority table in one place (`ActionPriorityOrder`) and adds only a `when(mode)` branch. The coupling (`platform is VirtualDisplayPlatform`) is acceptable — we have exactly two platform implementations and the check is in one place.

## VD-Specific Priority Rationale

| Action | VD Order | Why |
|---|---|---|
| **click** | `TAP → NODE_CLICK` | VD tap = real MotionEvent, no false success problem. Node click works but requires a11y tree node resolution. |
| **long_press** | `GESTURE_LONG_PRESS → NODE_LONG_CLICK` | Same logic: VD long press = real MotionEvent hold. |
| **scroll** | `A11Y_SCROLL → GESTURE_SWIPE` | **Keep same**. A11y scroll is precise (exact scroll distance) and fast (no animation). Gesture swipe only needed when no scrollable node found. |

## Impact

### Files Changed

| File | Change |
|---|---|
| `ActionPriorityOrder.kt` | Add `PlatformMode` enum, convert `val` to `fun(mode)` |
| `ClickExecutor.kt` | Derive mode from platform, pass to `ActionPriorityOrder.click(mode)` |
| `LongPressExecutor.kt` | Same pattern |
| `ScrollExecutor.kt` | Same pattern (order unchanged for now) |

### Risk

- **Low**: Priority order is the only change. Execution paths and fallback logic are untouched.
- **Backward compatible**: Accessibility mode behavior is identical to current.
- **Testable**: Unit test both modes' channel ordering.

## Follow-Up

- [ ] Device validation: run debug-run with `--vd` on Files task after implementing
- [ ] Eval comparison: A11y mode vs VD mode on same task set
- [ ] Consider: should `GESTURE_TAP` channel name be renamed to `INPUT_TAP` in VD context for clarity? (cosmetic, not blocking)

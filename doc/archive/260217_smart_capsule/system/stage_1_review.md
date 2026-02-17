# Smart Capsule v2 Stage 1 — Code Review

**Scope**: Redesign of the floating overlay capsule from a simple status bar to a two-row collaboration surface driven by `CapsuleMode`.

**Files reviewed**: CapsuleMode.kt, AgentEvent.kt, AgentEventDispatcher.kt, AgentTurnRunner.kt, SmartCapsuleLayoutBuilder.kt, SmartCapsuleManager.kt, ServiceOverlayController.kt, AgentService.kt, CapsuleModeTest.kt

---

## Critical

### [CRITICAL] Pulse animation leaks scaleY animator

**File**: `SmartCapsuleManager.kt`  
**Lines**: 272–287

**Problem**: `startPulse()` creates two `ObjectAnimator`s (scaleX and scaleY) but only the first is stored in `pulseAnimator`. `stopPulse()` cancels only the scaleX animator. The scaleY animator is never cancelled, so it keeps running indefinitely after `stopPulse()` and can leak.

**Fix**:
```kotlin
private var pulseAnimatorX: ObjectAnimator? = null
private var pulseAnimatorY: ObjectAnimator? = null

private fun startPulse(dot: View) {
    stopPulse()
    pulseAnimatorX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.3f, 1f).apply {
        duration = 1500
        repeatCount = ObjectAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }
    pulseAnimatorY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.3f, 1f).apply {
        duration = 1500
        repeatCount = ObjectAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }
}

private fun stopPulse() {
    pulseAnimatorX?.cancel()
    pulseAnimatorX = null
    pulseAnimatorY?.cancel()
    pulseAnimatorY = null
}
```

Alternative: use a single `AnimatorSet` or `PropertyValuesHolder` for both axes.

---

### [CRITICAL] Done → Error race: delayed hide overwrites Error state

**File**: `SmartCapsuleManager.kt`  
**Lines**: 222–235

**Problem**: `renderDone()` posts `handler.postDelayed({ hide() }, 3000)` but does not cancel it when switching to another mode. If `updateMode(Error(...))` is called within those 3 seconds, the delayed hide still runs and hides the Error overlay prematurely.

**Fix**: Store a dedicated runnable and remove it when rendering a new mode:

```kotlin
private var delayedHideRunnable: Runnable? = null

private fun renderDone(v: CapsuleViews, mode: CapsuleMode.Done) {
    // ... existing render logic ...
    delayedHideRunnable?.let { handler.removeCallbacks(it) }
    delayedHideRunnable = Runnable {
        hide()
        delayedHideRunnable = null
    }.also { handler.postDelayed(it, 3000) }
}

// In updateMode(), when switching to a non-Done mode, clear any pending hide:
private fun clearDelayedHide() {
    delayedHideRunnable?.let { handler.removeCallbacks(it) }
    delayedHideRunnable = null
}
```

Then call `clearDelayedHide()` at the start of `updateMode()` when `newMode !is CapsuleMode.Hidden` and before `render()`.

---

## High

### [HIGH] Error mode: Stop button still invokes onStop instead of onDismissError

**File**: `SmartCapsuleManager.kt`  
**Lines**: 236–254, 93–97

**Problem**: In Error mode the stop button label is changed to "关闭" but the click handler still calls `onStop` (via the debounced callback from `build()`). The design intends "Stays until dismissed" with `onDismissError`. Calling `onStop` may be acceptable but is inconsistent with the documented behavior.

**Fix**: Either (a) update the stop button’s `OnClickListener` when rendering Error mode to call `onDismissError?.invoke()`, or (b) wire `onDismissError` to the same implementation as `onStop` and document that behavior.

---

### [HIGH] handler.removeCallbacksAndMessages(null) clears all callbacks

**File**: `SmartCapsuleManager.kt`  
**Line**: 108

**Problem**: `handler.removeCallbacksAndMessages(null)` removes all callbacks and messages from the handler. That clears the delayed hide, but it is broad and could affect future callbacks if more are added.

**Fix**: Prefer a dedicated runnable and remove it explicitly (as in the Done→Error fix above). Reserve `removeCallbacksAndMessages(null)` for full cleanup in `dispose()` only.

---

## Medium

### [MEDIUM] WaitingForInput / WaitingForAction spelling

**File**: `CapsuleMode.kt`  
**Lines**: 25–28, 48–52

**Problem**: The sealed interface uses `WaitingForInput` and `WaitingForAction` (single “t”). The correct spelling is `WaitingForInput` and `WaitingForAction` (double “t”).

**Fix**: Rename to `WaitingForInput` and `WaitingForAction` everywhere (CapsuleMode.kt, SmartCapsuleManager.kt, displayThought, doc references).

---

### [MEDIUM] Open App button removed

**File**: `SmartCapsuleLayoutBuilder.kt`, `ServiceOverlayController.kt`

**Problem**: The previous layout included an "Open App" button; the new layout does not. `onOpenApp` is set on `SmartCapsuleManager` but never wired to a view.

**Fix**: Either add an Open App button to the new layout (if desired for Stage 1) or document that it is intentionally deferred to a later stage.

---

### [MEDIUM] TakeoverPending never used

**File**: `CapsuleMode.kt`, `SmartCapsuleManager.kt`

**Problem**: `TakeoverPending` is defined ("User requested takeover, waiting for current action to finish") but no code sets it. `updatePauseState(true)` goes directly to `Takeover`.

**Fix**: Either implement the takeover-pending flow (e.g. when user requests takeover during execution) or document that it is planned for Stage 2.

---

### [MEDIUM] Large SmartCapsuleManager file

**File**: `SmartCapsuleManager.kt`  
**Lines**: ~350

**Problem**: File exceeds the 400-line guideline but is close. Consider extracting render helpers (e.g. `CapsuleRenderer`) if it grows further.

---

### [MEDIUM] Pill buttons lack contentDescription

**File**: `SmartCapsuleLayoutBuilder.kt`  
**Lines**: 205–239

**Problem**: `buildPillButton()` creates buttons without `contentDescription`. Overlay buttons should be accessible.

**Fix**: Add `contentDescription` for each pill: e.g. "补充", "接管", "停止", "继续", "关闭".

---

## Low

### [LOW] Unused color constants

**File**: `SmartCapsuleManager.kt`  
**Lines**: 54–58

**Problem**: `colorLightBlue` and `colorPurple` are defined but not used.

**Fix**: Remove or use them.

---

### [LOW] Test coverage for displayThought

**File**: `CapsuleModeTest.kt`

**Problem**: `displayThought` is not tested for `TakeoverPending`, `SupplementInput(previousMode = TakeoverPending)`, or `SupplementInput(previousMode = Takeover)`.

**Fix**: Add tests for these branches.

---

### [LOW] CapsuleViews doc typo

**File**: `SmartCapsuleLayoutBuilder.kt`  
**Line**: 16

**Problem**: Javadoc says "handles to" instead of "references" or "handles".

**Fix**: Change "handles to" → "references" or "handles".

---

### [LOW] updateStatus regex for emoji stripping

**File**: `SmartCapsuleManager.kt`  
**Line**: 333

**Problem**: `Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]")` may not match emoji sequences correctly (e.g. `⏸️` = base + variation selector). Consider whether `StatusUtils.cleanStatusText` is still needed.

---

## Positive Notes

- **Thread safety**: Event collection runs on `Dispatchers.Main`; `render()` uses `v.container.post { }` for view updates, so UI changes stay on the main thread.
- **CapsuleMode**: Clear sealed interface for capsule states.
- **Thought extraction**: `emitAgentThought` fallback chain (agent_thought → ActionDescriptionFormatter → nothing) is sound.
- **Legacy compatibility**: `onTaskStarted`, `onMessageDelta`, `updatePauseState`, etc. preserved for ServiceOverlayController.
- **Debouncing**: 300 ms debounce on button clicks.
- **Tests**: `sanitizeThought` and `displayThought` well covered.

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 2 |
| High     | 1 |
| Medium   | 5 |
| Low      | 4 |

**Recommendation**: **CHANGES_REQUESTED** — fix the two Critical issues (pulse animator leak, Done→Error race) before merge. Address High issues as part of the same change set when practical.

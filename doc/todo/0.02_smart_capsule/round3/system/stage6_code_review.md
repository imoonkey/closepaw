# Stage 6 Code Review: CapsuleStateHolder + Pure Renderer Refactor

**Scope**: Smart Capsule Round 3, Stage 6  
**Files reviewed**: CapsuleContext.kt, CapsuleStateHolder.kt, CapsuleMode.kt, SmartCapsuleManager.kt, SmartCapsuleRenderer.kt, ServiceOverlayController.kt, CapsuleModeTest.kt

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 1 |
| HIGH | 4 |
| MEDIUM | 3 |
| LOW | 2 |
| INFO | 2 |

**Recommendation: CHANGES_REQUESTED** — Fix the 1 Critical and 4 High issues before merge.

---

## CRITICAL

### [CRITICAL] onDismissError never wired — stateHolder stays in Error after user dismisses

**File**: `ServiceOverlayController.kt`  
**Line**: 46–52 (capsuleManager setup)

**Problem**: `capsuleManager.onDismissError` is never set. When the user taps "关闭" in Error mode, `handleStopClick()` calls `onDismissError?.invoke() ?: hide()`. Since `onDismissError` is null, it falls through to `hide()`. The capsule hides but `stateHolder` remains in `CapsuleMode.Error`. StateHolder and UI diverge.

**Fix**: Wire `onDismissError` in the capsuleManager setup:

```kotlin
private val capsuleManager = SmartCapsuleManager(service = context).apply {
    this.onStop = this@ServiceOverlayController.onStop
    this.onTakeover = ...
    // ...
    this.onDismissError = {
        stateHolder.onDismissError()
        pushModeToOverlayCapsule()
    }
}
```

---

## HIGH

### [HIGH] onTakeoverRequested never called — TakeoverPending state never shown

**File**: `ServiceOverlayController.kt`  
**Line**: 47

**Problem**: When the user taps "接管" in Running mode, `handlePrimaryClick()` invokes `onTakeover?.invoke()` directly. That forwards to AgentService's `submitOp(Op.Takeover)`. `stateHolder.onTakeoverRequested()` is never called, so the capsule never transitions to `TakeoverPending` ("正在交接..."). The user sees no feedback until `onSessionTakeover` fires.

**Fix**: Wrap `onTakeover` to update state first:

```kotlin
this.onTakeover = {
    stateHolder.onTakeoverRequested()
    pushModeToOverlayCapsule()
    this@ServiceOverlayController.onTakeover()
}
```

---

### [HIGH] onUserResponseSent never called — state stays in WaitingForInput after send

**File**: `ServiceOverlayController.kt`  
**Line**: 50

**Problem**: When the user sends a response in `WaitingForInput`, `setupAnswerInput` calls `onUserResponse?.invoke(callId, text)`, which forwards to AgentService. `stateHolder.onUserResponseSent(callId)` is never called. The stateHolder remains in `WaitingForInput` until another event (e.g. `onThoughtUpdate`) overwrites it. Comment in SmartCapsuleManager.kt line 229 says "State transition handled by CapsuleStateHolder via onUserResponseSent" but nothing invokes it.

**Fix**: Wrap `onUserResponse` to update state first:

```kotlin
this.onUserResponse = { callId, response ->
    stateHolder.onUserResponseSent(callId)
    pushModeToOverlayCapsule()
    this@ServiceOverlayController.onUserResponse(callId, response)
}
```

---

### [HIGH] Done auto-hide does not update stateHolder — state drift

**File**: `SmartCapsuleManager.kt`  
**Line**: 286–291

**Problem**: When `scheduleAutoHide()` fires, the runnable calls `animator.animateExit(container) { hide() }`. `hide()` clears the overlay but never notifies the controller. `stateHolder` stays in `CapsuleMode.Done` while the capsule is hidden. StateHolder and UI diverge.

**Fix**: Add `onDoneAutoHide` callback to SmartCapsuleManager, wire it in the controller, and call it from the animateExit callback:

```kotlin
// SmartCapsuleManager: add var onDoneAutoHide: (() -> Unit)? = null
// In scheduleAutoHide runnable:
animator.animateExit(container) { onDoneAutoHide?.invoke() ?: hide() }

// ServiceOverlayController capsuleManager setup:
this.onDoneAutoHide = {
    stateHolder.onDoneAutoHide()
    pushModeToOverlayCapsule()
}
```

---

### [HIGH] platformMode and isAgentMidTurn are mutable vars — not thread-safe

**File**: `CapsuleStateHolder.kt`  
**Line**: 33–36

**Problem**: `platformMode` and `isAgentMidTurn` are plain `var` fields. They are written from `ServiceOverlayController` (likely main thread) but could be read from Compose/StateFlow collectors on other threads. No synchronization. `mode` and `context` use StateFlow (thread-safe); these do not.

**Fix**: Either (a) expose them as StateFlow for consistency and thread safety, or (b) document that all access must be on the main thread. Prefer (a) if Compose will consume them.

---

## MEDIUM

### [MEDIUM] CapsuleContext.setContext never called — dead API

**File**: `CapsuleContext.kt`, `CapsuleStateHolder.kt`  
**Line**: N/A

**Problem**: `CapsuleContext` and `stateHolder.setContext()` are introduced but never invoked. The design doc (system_design_round3.md) shows `setContext(SCREEN_VIEWING)` and `setContext(BACKGROUND)` in VD mode, but `ServiceOverlayController` does not call them. Context remains `MAIN_APP` always.

**Fix**: Either wire `setContext` in the appropriate places (e.g. when switching platform mode or window state) or remove the API until Stage 7+ when it is used. Document intent if deferred.

---

### [MEDIUM] SmartCapsuleManager retains local mode/previousMode — redundant with stateHolder

**File**: `SmartCapsuleManager.kt`  
**Line**: 46–47

**Problem**: Manager keeps `private var mode` and `private var previousMode` as a "cache". These are passed in via `renderMode(newMode, prevMode)` and overwritten. The comment says "cache, not source of truth" which is correct, but `handlePrimaryClick()` and `handleStopClick()` read `mode` from this cache. If `renderMode` is ever called with stale or out-of-order args, button behavior could be wrong. Low risk if controller always pushes correctly.

**Fix**: Consider passing mode explicitly to click handlers or documenting that `mode` must match the last `renderMode` call. Alternatively, have the controller pass a `CapsuleMode` getter if needed.

---

### [MEDIUM] Missing tests for CapsuleStateHolder and new transitions

**File**: `app/src/test/`  
**Line**: N/A

**Problem**: `CapsuleStateHolder` has no unit tests. State transitions (`onTakeoverRequested`, `onUserResponseSent`, `onDoneAutoHide`, etc.) are non-trivial and would benefit from tests. `CapsuleModeTest` covers `displayThought`, `isExpanded`, `sanitizeThought` but not `TakeoverPending` or `WaitingForAction` for `displayThought`/`isExpanded`.

**Fix**: Add `CapsuleStateHolderTest` for state transitions. Add `displayThought` test for `TakeoverPending` and `isExpanded` test for `WaitingForAction`.

---

## LOW

### [LOW] sanitizeThought used in CapsuleStateHolder but different truncation

**File**: `CapsuleStateHolder.kt` line 41, `CapsuleMode.kt` line 63–66

**Problem**: `onTaskStarted` uses `input.take(30) + "..."` while `sanitizeThought` uses 40 chars. `onError` uses `message.take(40)`. Inconsistent truncation limits.

**Fix**: Use `sanitizeThought(input)` in `onTaskStarted` for consistency, or extract shared constants.

---

### [LOW] supplementInputArea hint mismatch

**File**: `SmartCapsuleLayoutBuilder.kt` line 206, `SmartCapsuleRenderer.kt` line 204

**Problem**: LayoutBuilder sets `hint = "输入补充信息..."`; Renderer overwrites with `hint = "输入你的答复..."` in `renderWaitingForInput`. The layout default is never seen for WaitingForInput. Minor redundancy.

**Fix**: Either remove the layout default or document that renderer always overrides for WaitingForInput.

---

## INFO

### [INFO] SupplementInput removal is complete

**Files**: `CapsuleMode.kt`, `SmartCapsuleRenderer.kt`

**Observation**: `SupplementInput` mode is cleanly removed. `displayThought()`, `isExpanded()`, and renderer branches no longer reference it. `supplementInputArea` in layout is reused for `WaitingForInput` only. No dead code.

---

### [INFO] Architecture is clear

**Files**: `CapsuleStateHolder.kt`, `SmartCapsuleManager.kt`, `ServiceOverlayController.kt`

**Observation**: Single source of truth in `CapsuleStateHolder`, push-based rendering via `pushModeToOverlayCapsule()`, and pure renderer in `SmartCapsuleManager` are well separated. Event flow is understandable. Fixing the missing callback wirings will complete the design.

---

## Checklist Summary

| Category | Status |
|----------|--------|
| **Correctness** | ❌ State transitions lost: onTakeoverRequested, onUserResponseSent, onDismissError, onDoneAutoHide |
| **Architecture** | ✅ CapsuleStateHolder centralized; no leaked state in other classes |
| **Code quality** | ✅ Clean, Kotlin idioms, sealed classes, extension functions |
| **Thread safety** | ⚠️ StateFlow OK; platformMode/isAgentMidTurn not synchronized |
| **Missing items** | CapsuleContext unused; CapsuleStateHolder untested |

---

## Approval

**Recommendation: CHANGES_REQUESTED**

Address the 1 Critical and 4 High issues before merge. The refactor direction is sound; the main gaps are callback wiring and state synchronization.

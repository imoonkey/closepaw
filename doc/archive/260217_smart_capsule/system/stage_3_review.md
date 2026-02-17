# Code Review: Stage 3 — ask_user Tool (Smart Capsule V2)

**Date**: 2025-02-12  
**Scope**: CompletableDeferred suspension bridge, `ask_user` tool, Op.UserResponse, AskUser event, capsule WaitingForInput/WaitingForAction states.

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High     | 2 |
| Medium   | 4 |
| Low      | 3 |

**Recommendation**: **CHANGES_REQUESTED** — Address 2 High issues before merge.

---

## [HIGH] VIRTUAL_DISPLAY mode: No way to respond to ask_user

**File**: `ServiceOverlayController.kt`, `AgentService.kt`  
**Lines**: 304–316, 381–382

**Problem**: In `PlatformMode.VIRTUAL_DISPLAY`, `onAskUser` only updates the status island (`statusIslandManager?.updateStatus("❓ ${message.take(20)}", ...)`). There is no text input or “完成” button. The user cannot submit text for questions or confirm actions. `ask_user` will always time out (5 minutes) in VD mode.

**Fix**: Add a way to respond in VD mode, e.g.:
- Surface an input / confirmation UI in the StatusIsland flow, or
- Open the main app to a minimal AskUser response screen when `ask_user` fires in VD mode, or
- Document that `ask_user` is effectively disabled in VD mode and treat it as a known limitation.

---

## [HIGH] UserResponseChannel lacks thread synchronization

**File**: `UserResponseChannel.kt`  
**Lines**: 14–56

**Problem**: `pending`, `pendingCallId`, and `hasPending` are read/written from multiple threads (agent coroutine, `submit()` scope, `validate()`). There is no synchronization. A TOCTOU race between two `awaitResponse` calls could allow both to pass `check(pending == null)` before either sets `pending`, causing the first deferred to be orphaned. In practice this is mitigated by `validate()` and single-agent execution, but the design is fragile.

**Fix**: Use `@Volatile` for visibility, or wrap all mutations in `synchronized(this)` / `Mutex`, or use `AtomicReference` for `pending` and `pendingCallId`. Example:

```kotlin
@Volatile
private var pending: CompletableDeferred<String>? = null

@Volatile
private var pendingCallId: String? = null

// Or use synchronized block for deliver/cancel/awaitResponse mutations
```

---

## [MEDIUM] CompletableDeferred.cancel() vs completeExceptionally

**File**: `UserResponseChannel.kt`  
**Lines**: 49–54

**Problem**: `cancel()` calls `pending?.cancel()` on the `Deferred`. This cancels the coroutine awaiting it. The `AskUserInvocation` catches `CancellationException` and returns `ToolExecutionResult.Cancelled`. Documentation says “stop/timeout” but there is no explicit timeout cancellation path — timeout uses `withTimeoutOrNull`, which returns `null` rather than cancelling the deferred. The deferred stays pending until the next `deliver` or `cancel`. If the user responds after timeout but before another `ask_user`, `deliver` would complete an already-“timed out” call. The current design avoids that by returning `null` and letting the tool return; the deferred is cleared in `awaitResponse`’s `finally` when the timeout path returns. So behavior is correct, but the interplay between timeout and cancellation is subtle.

**Fix**: Add a clarifying comment in `UserResponseChannel` and `AskUserTool` explaining that (1) timeout returns `null` and exits via `finally`, and (2) `cancel()` is for stop/shutdown.

---

## [MEDIUM] SmartCapsuleManager: Handler runnable not cancelled on hide

**File**: `SmartCapsuleManager.kt`  
**Lines**: 352–355, 414–417

**Problem**: `showAnswerInputArea` and `showSupplementInputArea` use `handler.postDelayed(..., 200)` to show the keyboard. The runnable captures `editText`. If the capsule is hidden (e.g. user taps stop) before 200ms, the runnable still runs and may reference detached views. Low risk but can cause transient issues.

**Fix**: Store the runnable reference and cancel it in `hide()`:

```kotlin
private var showKeyboardRunnable: Runnable? = null

// In showAnswerInputArea:
showKeyboardRunnable?.let { handler.removeCallbacks(it) }
showKeyboardRunnable = Runnable {
    val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    showKeyboardRunnable = null
}
handler.postDelayed(showKeyboardRunnable!!, 200)

// In hide():
showKeyboardRunnable?.let { handler.removeCallbacks(it) }
showKeyboardRunnable = null
```

---

## [MEDIUM] SessionServices.cleanup() does not cancel UserResponseChannel

**File**: `SessionServices.kt`  
**Lines**: 288–315

**Problem**: `cleanup()` does not call `userResponseChannel.cancel()`. If `cleanup()` is ever invoked without `handleShutdown()` (e.g. via a future code path), a pending `ask_user` would remain uncancelled.

**Fix**: `AgentSession.handleShutdown()` already calls `cancel()` before `cleanup()`, so ordering is correct. Add `userResponseChannel.cancel()` in `cleanup()` as a defensive step so cleanup is always safe:

```kotlin
suspend fun cleanup() {
    userResponseChannel.cancel()
    // ... rest of cleanup
}
```

---

## [MEDIUM] Test coverage gaps

**File**: `UserResponseChannelTest.kt`, `AskUserTool.kt`

**Problem**: No tests for:
- Timeout path (response returns `null` after 5 minutes)
- Cancellation propagation (stop during `ask_user`)
- `validate()` rejection when `hasPending` is true
- `deliver()` returning `false` after `cancel()` / concurrent `cancel()` race

**Fix**: Add tests:
- `timeout returns null and tool returns Success with timeout message`
- `cancel during await returns Cancelled`
- `validate rejects when hasPending`
- `deliver after cancel returns false`

---

## [LOW] Magic number for timeout

**File**: `AskUserTool.kt`  
**Line**: 32

**Problem**: `TIMEOUT_MS = 5 * 60 * 1000L` is hardcoded. Different environments may want different timeouts.

**Fix**: Make configurable via `SessionConfig` or `AskUserTool` constructor, with a default of 5 minutes.

---

## [LOW] StatusIsland: AskUser message in Chinese context

**File**: `ServiceOverlayController.kt`  
**Line**: 308

**Problem**: Status text is truncated in English (`"❓ ${message.take(20)}"`). If the app is localized for Chinese, the status text may be inconsistent.

**Fix**: Minor; align with localization strategy if/when status strings are localized.

---

## [LOW] WaitingForInput stop button: hideAnswerInputArea vs onStop order

**File**: `SmartCapsuleManager.kt`  
**Lines**: 414–418

**Problem**: In `handleStopClick` for `WaitingForInput`, `hideAnswerInputArea` is called before `onStop`. The keyboard is hidden and input cleared before `Op.Shutdown` is submitted. That is correct; the only concern is that `onUserResponse` is never invoked, so the channel stays pending until `cancel()` runs. Since `onStop` leads to `cancel()`, this is fine.

**Fix**: None; optional comment to clarify that `onStop` triggers `cancel()` and clears the pending request.

---

## Checklist — Passed

| Check | Status |
|-------|--------|
| Hardcoded secrets | ✅ None |
| Context leaks | ✅ No static Context; service passed via constructor |
| Main thread blocking | ✅ Heavy work on suspend functions; no blocking |
| Null safety | ✅ `?.` used; no `!!` |
| Lifecycle | ✅ `cancel()` on Interrupt/Shutdown; cleanup in `handleShutdown` |
| Coroutine scope | ✅ Pending deferred cleared in `finally`; `cancel()` on stop |
| Error handling | ✅ `CancellationException` caught; timeout returns `Success` with message |
| Tool registration | ✅ `ensureAskUserToolRegistered` in `SessionAgentRunner`; `AgentEventDispatcher` passed correctly |
| AskUser event flow | ✅ AgentService → overlayController → SmartCapsuleManager |
| Edge cases | ✅ Stop: `cancel()` in handleInterrupt/handleShutdown; double ask_user: `validate()` rejects |
| Timeout handling | ✅ `withTimeoutOrNull`; returns `Success` with timeout message |
| Memory | ✅ Capsule views held by `overlayView`; cleared on `hide()` |

---

## Files Reviewed

| File | Notes |
|------|-------|
| `UserResponseChannel.kt` | Core suspension bridge; thread-safety concern |
| `AskUserTool.kt` | Tool + invocation; timeout + cancellation handled |
| `Op.kt` | `Op.UserResponse` added |
| `AgentEvent.kt` | `AskUser` event, `AskUserType` enum |
| `AgentSession.kt` | `handleUserResponse`, `cancel()` on Interrupt/Shutdown |
| `SessionAgentRunner.kt` | `ensureAskUserToolRegistered` with correct dispatcher |
| `SessionServices.kt` | `UserResponseChannel` in constructor |
| `AgentEventDispatcher.kt` | `emitAskUser()` added |
| `AgentService.kt` | `AskUser` event routed to overlay |
| `ServiceOverlayController.kt` | `onAskUser`; VD mode lacks response UI |
| `SmartCapsuleManager.kt` | `WaitingForInput`, `WaitingForAction` rendering |
| `UserResponseChannelTest.kt` | Basic cases; missing timeout/cancel/validate tests |

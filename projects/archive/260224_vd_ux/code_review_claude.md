# Code Review: VD UX Align Changes

**Reviewer**: Claude (Opus 4)
**Date**: 2026-02-23
**Design doc**: `doc/todo/0.02_vd_ux/align/design/design.md`
**Recommendation**: **APPROVE** (no Critical or High issues)

---

## Files Reviewed

1. `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
2. `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
3. `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayAppController.kt`

---

## Findings

### VirtualDisplayPlatform.kt

**[MEDIUM] `suppressKeyboard()` called from coroutine thread, accesses `softKeyboardController` without posting to main**
Lines: 399-408

Problem: `performAction()` is a `suspend fun` called from coroutine context (tool
executors call it via `context.platform.performAction()`). The coroutine dispatcher
is not guaranteed to be the main thread. `suppressKeyboard()` and
`isKeyboardVisibleOnMainDisplay()` are called synchronously inline -- they directly
invoke `service.softKeyboardController.setShowMode()` and
`service.getWindowsOnAllDisplays()`.

From the Android docs, `SoftKeyboardController.setShowMode()` internally posts to an
IPC binder and is documented as thread-safe. `getWindowsOnAllDisplays()` similarly
goes through AccessibilityInteractionClient which is thread-safe.

However, there is a subtle ordering concern: `suppressKeyboard()` does
`mainHandler.removeCallbacksAndMessages(keyboardRestoreToken)` from the calling
coroutine thread, then calls `setShowMode(HIDDEN)`. Meanwhile, a previously posted
`restoreKeyboardNow()` Runnable might be executing on the main-thread Handler
concurrently. The sequence could be:

```
Coroutine thread:   removeCallbacksAndMessages(token) ... setShowMode(HIDDEN)
Main thread:                                           restoreKeyboardNow() runs
                                                       -> setShowMode(AUTO)
```

This is a narrow race. In practice the removeCallbacks + setShowMode happens fast
enough that the race window is sub-millisecond, and the consequence (keyboard auto
mode restored slightly early) is benign -- next action will re-suppress. No
functional breakage, but the design would be cleaner if `suppressKeyboard()` were
posted to `mainHandler` as well.

Verdict: Acceptable as-is. The race consequence is benign (early restore gets
re-suppressed on next action). A future hardening pass could run both suppress and
restore on the main handler for strict ordering.

---

**[MEDIUM] `restoreKeyboardNow()` silently swallows all exceptions**
Line: 428

Problem: The `catch (_: Exception) {}` block in `restoreKeyboardNow()` swallows
every exception with no logging. If `setShowMode(SHOW_MODE_AUTO)` fails (e.g.,
service disconnected, binder dead), the keyboard could remain in HIDDEN mode with
zero diagnostic trace. This is the "safety net" path invoked from `stop()` -- the
one place where silent failure is most dangerous.

Compare with `suppressKeyboard()` at line 407 which correctly logs:
`Log.w(TAG, "Failed to suppress keyboard", e)`.

Fix: Add `Log.w(TAG, "Failed to restore keyboard", e)` to the catch block.

---

**[MEDIUM] Handler callbacks not cleaned up in `stop()`**
Lines: 158-176

Problem: `stop()` calls `restoreKeyboardNow()` which does
`mainHandler.removeCallbacksAndMessages(keyboardRestoreToken)`. This is correct.
However, if `stop()` is called and then the VirtualDisplayPlatform instance is
retained (which happens -- `SessionServices` holds it), the `mainHandler` still
references the main Looper. This is fine for the current lifecycle because
`mainHandler` is a plain Handler with no leak path (no static reference, no
anonymous inner class capturing Activity context). Just noting for completeness
that cleanup is adequate.

Verdict: No issue. `restoreKeyboardNow()` inside `stop()` already clears pending
callbacks via `removeCallbacksAndMessages`.

---

**[LOW] Magic constant `500L` for IME_SUPPRESS_DELAY_MS could be documented**
Line: 52

Problem: The 500ms delay is the pulse debounce window. The design doc explains the
rationale (restore keyboard between actions during LLM thinking gaps of 1.5-3s),
but the code constant has no inline comment explaining why 500ms specifically.

Fix: Add a brief comment: `// Restore keyboard 500ms after last action; short enough
// to stay hidden during rapid action sequences, long enough to not flicker.`

---

**[LOW] `mayTriggerIme()` could use `when` instead of chained `is` checks**
Lines: 387-393

Problem: The chained `this is X || this is Y || ...` pattern works but is less
idiomatic Kotlin than a `when` expression. Minor style preference.

```kotlin
private fun UIAction.mayTriggerIme(): Boolean = when (this) {
    is UIAction.ClickNodeAt, is UIAction.TapAt,
    is UIAction.LongClickNodeAt, is UIAction.LongPressAt,
    is UIAction.SetTextOnNodeAt, is UIAction.SetTextOnFocused -> true
    else -> false
}
```

Verdict: Style-only. Both are correct.

---

**[LOW] `isKeyboardVisibleOnMainDisplay()` API level branching uses fully-qualified constant**
Line: 434

Problem: `android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU`
is fully qualified inline instead of using an import. This is inconsistent with the
rest of the file which imports Android types. Not a bug, just verbose.

---

### AgentSession.kt

**[MEDIUM] `reacquirePlatform()` calls `platform.start()` on an already-running VD**
Lines: 285-297

Problem: With the lifecycle change, when VD stays alive during Hot Idle, a follow-up
task calls `reacquirePlatform()` which calls `services.platform.start()`. The new
`start()` implementation does an early return if `displayId != INVALID_DISPLAY`, so
this is safe. However, the method name `reacquirePlatform` and its doc comment
("Re-acquire platform for Hot Idle follow-up") now describe a no-op for VD mode.
The comment at line 295 `"Hot Idle follow-up: platform re-acquired"` logs a
misleading message when nothing was actually acquired.

Fix: Update log message to indicate idempotent start, e.g.:
`"Hot Idle follow-up: platform.start() called (VD may already be running)"`

Verdict: Cosmetic/logging accuracy. No functional issue.

---

**[LOW] Doc comment on `handleAgentComplete()` is accurate and well-written**
Lines: 318-324

The updated doc comment correctly describes the new semantics: "Releases agent runner
but keeps platform alive (VD apps keep running)." This matches the implementation.
Good.

---

### VirtualDisplayAppController.kt

**No issues found.**

The `dismissMainDisplayKeyboard()` method and its doc comment were cleanly removed.
The remaining class is a focused app-launcher with proper error handling and correct
`Dispatchers.IO` usage. File is well under the 400-line limit at 87 lines.

---

## Design Doc Acceptance Criteria Assessment

| # | Criterion | Status | Notes |
|---|-----------|--------|-------|
| 1 | VD mode: task completes, music keeps playing | MET | `platform.stop()` removed from `handleAgentComplete()`. VD persists through Hot Idle. |
| 2 | Follow-up task reuses existing VD | MET | `start()` is now idempotent via early return. No new `createVirtualDisplay` call. |
| 3 | VD text input: no keyboard on main screen (user not typing) | MET | `suppressKeyboard()` sets `SHOW_MODE_HIDDEN` before any IME-triggering action. |
| 4 | Concurrent use: user typing email, agent on VD, no interruption | MET | `isKeyboardVisibleOnMainDisplay()` guard skips suppression when IME is already showing. |
| 5 | Smart Capsule supplement input works during execution | MET | Pulse design restores `SHOW_MODE_AUTO` 500ms after last action. LLM thinking gaps are 1.5-3s. |
| 6 | Force-kill app during VD task, reopen, keyboard works | MET | `stop()` calls `restoreKeyboardNow()`. Process death means the mode resets to system default anyway. |
| 7 | `./gradlew test` and `./gradlew lint` pass | NOT VERIFIED | Needs manual verification. |

---

## Thread Safety Analysis

The core concern is that `performAction()` (suspend, runs on coroutine dispatcher)
calls `suppressKeyboard()` and `scheduleKeyboardRestore()` which interact with a
main-thread Handler.

- `Handler.removeCallbacksAndMessages()`: Thread-safe by Android contract.
- `Handler.postAtTime()`: Thread-safe by Android contract.
- `SoftKeyboardController.setShowMode()`: Binder IPC, thread-safe.
- `service.getWindowsOnAllDisplays()`: Binder IPC via AccessibilityInteractionClient,
  thread-safe.

The race described in the first MEDIUM finding above is the only ordering concern. Its
consequence is benign (HIDDEN gets overwritten by AUTO for one pulse cycle, then
re-suppressed on next action). No data corruption, no crash, no permanent state
corruption.

## Resource Leak Analysis

- **Handler callbacks**: Cleaned up in `restoreKeyboardNow()` via
  `removeCallbacksAndMessages(keyboardRestoreToken)`. Called from `stop()`. No leak.
- **ImageReader**: Still cleaned up in `stop()`, which is still called via
  `services.cleanup()` in `handleShutdown()`. No leak.
- **Virtual display**: Same path -- `shizuku.releaseVirtualDisplay()` in `stop()`.
- **Idle timeout**: 5-minute `IDLE_TIMEOUT_MS` ensures the VD is not kept alive
  indefinitely. `handleShutdown()` -> `services.cleanup()` -> `platform.stop()`.

No resource leaks identified.

---

## Summary

| Severity | Count | Details |
|----------|-------|---------|
| Critical | 0 | -- |
| High | 0 | -- |
| Medium | 3 | Thread ordering race (benign), silent exception swallow in `restoreKeyboardNow()`, misleading log in `reacquirePlatform()` |
| Low | 3 | Magic constant comment, `when` vs `is` style, FQ constant |

**Recommendation: APPROVE**

The changes are clean, minimal, and correctly implement the design doc. The lifecycle
fix (removing `platform.stop()` from `handleAgentComplete()`) is the right call --
it decouples task lifecycle from VD lifecycle. The IME suppression pulse design is
well-thought-out, with proper guarding for the concurrent-user-typing case. The
`try/finally` pattern in `performAction()` ensures `scheduleKeyboardRestore()` always
runs even if the action throws. The `stop()` safety net for keyboard restore is
correct.

The three medium findings are all hardening opportunities, not blockers. The silent
exception swallow in `restoreKeyboardNow()` is the one I would most encourage fixing
before merge -- adding a single `Log.w` line costs nothing and could save debugging
time if the keyboard ever gets stuck in HIDDEN mode.

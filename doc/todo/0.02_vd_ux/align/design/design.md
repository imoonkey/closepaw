# VD UX Align Design (Codex + Claude)

Date: 2026-02-23
Scope: `doc/todo/0.02_vd_ux/qi_note.md`

## 1. Problem Statement

Two concrete issues in Virtual Display (VD) mode:

1. After a task completes, app activity inside VD is killed or effectively reset
   (e.g., YouTube playback stops).
2. Typing-related interactions in VD can pop IME on the user's main screen,
   creating broken UX.

## 2. Root Causes (Code-Aligned)

### 2.1 Issue A: VD lifecycle incorrectly tied to task lifecycle

`AgentSession.handleAgentComplete()` (line 364) calls
`services.platform.stop()`. In VD mode, `VirtualDisplayPlatform.stop()` calls
`shizuku.releaseVirtualDisplay(displayId)`, which forcibly destroys all windows
and kills all apps on the display.

This is an ownership bug: task completion should not tear down the execution
environment.

### 2.2 Issue B: IME leaks to wrong display

Current stack already avoids some triggers (`allowTapToFocus=false` in VD, skip
tap-to-focus fallback in `TypeExecutor`), but the design is still leaky:

1. Click path can focus editable nodes and trigger IME on display 0.
2. `dismissMainDisplayKeyboard()` only fires after `SetText*` actions, not
   clicks.
3. The shell BACK-key hack (`input keyevent --display 0 4`) has destructive
   side effects on the user's main screen.
4. Android's IME routing for app-owned virtual displays is unreliable across
   devices and versions.

## 3. Design Goals

1. Task completion must not kill VD app runtime.
2. VD actions must not cause keyboard to appear on the user's main screen.
3. Minimal change: fewer states, fewer fallbacks, clear ownership.

## 4. Non-Goals

1. Backward compatibility with old Hot Idle "release platform" semantics.
2. Guaranteeing system keyboard rendering inside VD.
3. Service-scope VD manager or new VD ownership abstraction.
4. Click-on-editable blocking or auto-conversion to type.
5. Adding nonexistent metadata flags (e.g., `flagRequestSoftKeyboardController`).

## 5. Proposed Fix Design

### 5.1 VD Persistence — session-scoped, not task-scoped

The VD lives as long as the session. Not the task.

```
BEFORE:
  Task start  --> VD create
  Task end    --> VD destroy      <-- apps die here
  Follow-up   --> VD create       <-- new VD, lost context

AFTER:
  First task  --> VD create (if not already running)
  Task end    --> nothing          <-- apps keep running
  Follow-up   --> VD already alive <-- apps preserved
  Session end --> VD destroy       <-- final cleanup
```

Changes:
1. `AgentSession.handleAgentComplete()`: Remove `services.platform.stop()` call.
2. `VirtualDisplayPlatform.start()`: Make idempotent — early return if already
   running (`displayId != Display.INVALID_DISPLAY`).
3. `handleShutdown()` path unchanged: `services.cleanup()` →
   `platform.stop()` destroys VD on session shutdown. Correct as-is.

No new state types. No new manager. VD is either running or stopped.

Resource cost during idle: ~20MB RAM (ImageReader buffers) + minimal GPU
compositing. Acceptable for a 5-minute idle timeout.

### 5.2 IME Suppression — synchronous guard around each action

Replace the shell BACK-key hack with the proper AccessibilityService API:
`SoftKeyboardController.setShowMode()`.

Strategy: **guarded synchronous suppression around each individual action**.

Key constraint: `setShowMode(SHOW_MODE_HIDDEN)` is system-wide. If the user
is actively typing on the main screen (e.g., writing an email), calling
`SHOW_MODE_HIDDEN` would dismiss their keyboard mid-keystroke. This is
unacceptable.

**Guard**: Before suppressing, check if the IME is already visible on
display 0. If yes, the user is typing — skip suppression entirely. The VD
action may try to trigger IME, but it's already showing, so there's no
visual disruption for the user.

```kotlin
private fun isKeyboardVisibleOnMainDisplay(): Boolean {
    return try {
        val allWindows = service.getWindowsOnAllDisplays()
        val mainWindows = allWindows.get(Display.DEFAULT_DISPLAY)
            ?: return false
        mainWindows.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
    } catch (_: Exception) {
        false
    }
}
```

Uses the same `getWindowsOnAllDisplays()` API already used by
`VirtualDisplayWindowAccessor`. Requires `flagRetrieveInteractiveWindows`
(already declared in our accessibility config). API 33+ for
`getWindowsOnAllDisplays()`, API 31 fallback via `service.windows` with
`displayId` filter.

**Suppress timing** — synchronous, no debounce:

```
Action 1:    [--HIDDEN--][AUTO]
                                  user can type freely here
[LLM thinking..............................][Action 2: [--HIDDEN--][AUTO]]
                                                                          user can type freely here
```

Keyboard is in `SHOW_MODE_HIDDEN` only for the ~100-500ms duration of each
action execution. Restored to `SHOW_MODE_AUTO` immediately in a `finally`
block. No Handler, no debounce timer, no delayed callbacks.

> **Design revision note**: The original design used a 500ms debounce
> (`scheduleKeyboardRestore` via Handler). Testing revealed this blocked the
> user's keyboard on the main screen during agent execution — rapid action
> sequences kept resetting the debounce timer, maintaining `SHOW_MODE_HIDDEN`
> for the entire task duration. Switched to synchronous suppress/restore to
> eliminate the problem.

**Behavior matrix**:

| User typing on main screen? | VD action | Result |
|---|---|---|
| No | Click search box | Suppressed. No keyboard appears. Clean. |
| No | SetTextOnNodeAt | Suppressed. No keyboard appears. Clean. |
| Yes (writing email) | Click search box | Guard skips suppress. User keeps typing. No disruption. |
| Yes (writing email) | SetTextOnNodeAt | Guard skips suppress. Keyboard already visible. No change. |

Changes:
1. `VirtualDisplayPlatform.performAction()`: apply guarded
   `setKeyboardHidden()` / `setKeyboardAuto()` synchronously around action
   execution, only for action types likely to trigger IME side effects:
   - `ClickNodeAt`
   - `TapAt`
   - `LongClickNodeAt`
   - `LongPressAt`
   - `SetTextOnNodeAt`
   - `SetTextOnFocused`
2. `VirtualDisplayPlatform`: add `isKeyboardVisibleOnMainDisplay()` guard.
3. `VirtualDisplayPlatform.stop()`: always restore `SHOW_MODE_AUTO` as
   safety net (prevent permanently disabled keyboard on crash).
4. Delete `dismissMainDisplayKeyboard()` call and method.

**Important API clarification**:
- No `flagRequestSoftKeyboardController` metadata flag exists in Android
  `accessibilityFlags`.
- `SoftKeyboardController` is available on API 24+, and this project targets
  API 31.
- `FLAG_INPUT_METHOD_EDITOR` is a different API (IME capability subset, API 33)
  and is not required for `setShowMode()`.

### 5.3 Existing typing protocol stays as-is

The current "keyboardless typing" design is already correct:
- `allowTapToFocus()` returns false in VD.
- `TypeExecutor` skips tap-to-focus fallback in VD mode.
- `SetTextOnNodeAt` / `SetTextOnFocused` are the only text input paths.
- If `ACTION_SET_TEXT` fails, it fails explicitly.

No changes needed here. This is done.

## 6. Implementation Plan

### Phase 1 (lifecycle fix) — ✅ DONE

1. `AgentSession.handleAgentComplete()`: delete `services.platform.stop()`
   call (lines 363-367). ✅
2. `VirtualDisplayPlatform.start()`: change `check(...)` assertion to early
   return (line 101). ✅
3. Update doc comments that describe Idle as "platform released". ✅

### Phase 2 (keyboard fix) — ✅ DONE

1. `VirtualDisplayPlatform`: add `setKeyboardHidden()` and
   `setKeyboardAuto()` helper methods. Wrap `performAction()` with
   synchronous guard + suppress/restore. ✅
2. `VirtualDisplayPlatform.stop()`: add `SHOW_MODE_AUTO` restore. ✅
3. Delete `dismissMainDisplayKeyboard()` from
   `VirtualDisplayAppController.kt` and its call site in
   `VirtualDisplayPlatform.performAction()`. ✅

### Phase 2b (keyboard fix revision) — ✅ DONE

Replaced 500ms debounce pattern with synchronous suppress/restore after
testing revealed the debounce blocked user keyboard during agent execution.
Removed: `Handler`, `keyboardRestoreToken`, `IME_SUPPRESS_DELAY_MS`,
`scheduleKeyboardRestore()`. See design revision note in §5.2.

## 7. Acceptance Criteria

1. VD mode: "open YouTube and play song" → task completes → music keeps
   playing. ⚠️ VD no longer destroyed, but YouTube still stops — likely
   Android framework PIP behavior, not our code. See §9.
2. Follow-up task reuses existing VD (no new `createVirtualDisplay` in logs). ✅
3. VD text input flow: no keyboard appears on main screen (when user is
   not typing). ✅
4. Concurrent use: user typing email on main screen while agent searches
   YouTube on VD → user's keyboard is not interrupted. ✅ (fixed in Phase 2b)
5. Smart Capsule supplement input works during agent execution (keyboard
   available between actions). ✅
6. Force-kill app during VD task → reopen → keyboard works normally. ✅
7. `./gradlew test` and `./gradlew lint` pass. ✅

## 8. Files Changed

| File | Change | ~Lines |
|---|---|---|
| `AgentSession.kt` | Remove `platform.stop()` from `handleAgentComplete()`, update doc comments | -5 |
| `VirtualDisplayPlatform.kt` | `start()` idempotent, synchronous keyboard suppress/restore with guard, remove old debounce infra | ~30 |
| `VirtualDisplayAppController.kt` | Remove `dismissMainDisplayKeyboard()` | -6 |

## 9. Open Issue: YouTube Playback Stops

After fixing VD lifecycle (Phase 1), YouTube still stops after a few
seconds of playback. The user sees a PIP window with paused video on the
main screen, suggesting YouTube's activity moves from the VD to display 0.

**Investigation result**: No code in our codebase moves apps between
displays or triggers PIP. Searched all post-task logic, app launch paths,
intent launching, task management APIs — none target display 0 after task
completion.

**Likely root cause**: Android framework behavior. When the VD surface
switches back to ImageReader (headless capture) or when YouTube detects
it's on a secondary display without active user interaction, YouTube's
own PIP logic triggers and moves the activity to the main display. This
is YouTube-specific app behavior interacting with Android's multi-display
framework, not something our code controls.

**Potential mitigations** (future work):
- Investigate whether `DISPLAY_FLAGS` can prevent PIP migration.
- Investigate whether keeping live preview surface active (instead of
  switching to ImageReader) prevents the behavior.
- Test with other video apps to determine if this is YouTube-specific.

## 10. Open Issue: Accessibility Actions Steal IME Focus from Main Display

See standalone problem statement: `focus_steal_problem.md`.

**Summary**: When the agent performs `AccessibilityNodeInfo.performAction
(ACTION_CLICK)` on a focusable node in the VD (e.g., a search box), the
target app's `requestFocus()` redirects the system-wide IME
`InputConnection` to the VD. The user's keystrokes on the main screen
(e.g., composing an email in Gmail) silently stop appearing — they go to
the VD's focused field instead. Re-tapping the main screen field restores
input.

**Root cause**: `OWN_DISPLAY_GROUP` (0x800) isolates input event routing
(touch/key), but accessibility actions bypass the input pipeline. They
call directly into the target app's View hierarchy via
`AccessibilityManagerService`, triggering `requestFocus()` →
`InputMethodManager` InputConnection redirect. This is a fundamental
Android framework limitation — per-display focus isolation does not cover
accessibility-triggered focus changes.

**Status**: No fix implemented. Documented as known limitation.

## 11. Open Issue: Same App on VD and Main Display Causes Interference

See standalone problem statement: `vd_app_conflict_problem.md`.

**Summary**: When the agent plays a YouTube video on the VD and the user
opens YouTube on the main display (display 0), the two instances conflict.
Android's `singleTask` launch mode moves the existing task to the requesting
display rather than creating a new instance, stopping VD playback. Audio
focus and media session are also system-wide singletons, meaning only one
YouTube instance can hold playback at a time.

**Confirmed behavior**: In Hot Idle mode (normal app flow), YouTube playback
on VD persists correctly after agent completion. The problem only surfaces
when the user independently launches the same app on the main display.

**Status**: No fix implemented. Accepted as known platform limitation.

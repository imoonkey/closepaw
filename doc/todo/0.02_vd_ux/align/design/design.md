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

### 5.2 IME Suppression — softKeyboardController pulse

Replace the shell BACK-key hack with the proper AccessibilityService API:
`SoftKeyboardController.setShowMode()`.

Strategy: **guarded pulse suppression around focus/typing-related actions**.

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

**Pulse timing** (when guard allows suppression):

```
Action 1:    [--HIDDEN--]
                         [~~~500ms~~~]
Action 2:               [--HIDDEN--]     (cancels pending restore)
                                    [~~~500ms~~~]
                                                [SHOW_MODE_AUTO restored]
                                                |
                                                v  user can type here
[LLM thinking..............................][Action 3]
```

**Behavior matrix**:

| User typing on main screen? | VD action | Result |
|---|---|---|
| No | Click search box | Suppressed. No keyboard appears. Clean. |
| No | SetTextOnNodeAt | Suppressed. No keyboard appears. Clean. |
| Yes (writing email) | Click search box | Guard skips suppress. User keeps typing. No disruption. |
| Yes (writing email) | SetTextOnNodeAt | Guard skips suppress. Keyboard already visible. No change. |

Changes:
1. `VirtualDisplayPlatform.performAction()`: apply guarded
   `suppressKeyboard()` / `scheduleKeyboardRestore()` only for action types
   likely to trigger IME side effects:
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

**Why pulse, not full-task suppression**: Smart Capsule supplement input
("Got ideas? Add a note...") needs keyboard during task execution. Pulse
keeps keyboard available between actions (~1.5-3s gaps during LLM
thinking).

### 5.3 Existing typing protocol stays as-is

The current "keyboardless typing" design is already correct:
- `allowTapToFocus()` returns false in VD.
- `TypeExecutor` skips tap-to-focus fallback in VD mode.
- `SetTextOnNodeAt` / `SetTextOnFocused` are the only text input paths.
- If `ACTION_SET_TEXT` fails, it fails explicitly.

No changes needed here. This is done.

## 6. Implementation Plan

### Phase 1 (lifecycle fix)

1. `AgentSession.handleAgentComplete()`: delete `services.platform.stop()`
   call (lines 363-367).
2. `VirtualDisplayPlatform.start()`: change `check(...)` assertion to early
   return (line 101).
3. Update doc comments that describe Idle as "platform released".

### Phase 2 (keyboard fix)

1. `VirtualDisplayPlatform`: add `suppressKeyboard()` and
   `scheduleKeyboardRestore()` helper methods. Wrap `performAction()`.
2. `VirtualDisplayPlatform.stop()`: add `SHOW_MODE_AUTO` restore.
3. Delete `dismissMainDisplayKeyboard()` from
   `VirtualDisplayAppController.kt` and its call site in
   `VirtualDisplayPlatform.performAction()`.

## 7. Acceptance Criteria

1. VD mode: "open YouTube and play song" → task completes → music keeps
   playing.
2. Follow-up task reuses existing VD (no new `createVirtualDisplay` in logs).
3. VD text input flow: no keyboard appears on main screen (when user is
   not typing).
4. Concurrent use: user typing email on main screen while agent searches
   YouTube on VD → user's keyboard is not interrupted.
5. Smart Capsule supplement input works during agent execution (keyboard
   available between actions).
6. Force-kill app during VD task → reopen → keyboard works normally.
7. `./gradlew test` and `./gradlew lint` pass.

## 8. Files Changed

| File | Change | ~Lines |
|---|---|---|
| `AgentSession.kt` | Remove `platform.stop()` from `handleAgentComplete()` | -5 |
| `VirtualDisplayPlatform.kt` | `start()` idempotent, keyboard suppress/restore, remove old dismiss call | ~25 |
| `VirtualDisplayAppController.kt` | Remove `dismissMainDisplayKeyboard()` | -6 |

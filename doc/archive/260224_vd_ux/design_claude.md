# VD UX Fixes: App Persistence & Keyboard Suppression

## The Problems

Two bugs. Both stem from the same root: the VD lifecycle is stupidly coupled to
the task lifecycle.

### 1. VD apps die between tasks

User plays a YouTube song via the agent. Task completes. Music stops. User is
sad.

**Root cause**: `AgentSession.handleAgentComplete()` calls
`services.platform.stop()`, which calls
`shizuku.releaseVirtualDisplay(displayId)`. Android's DisplayManager forcibly
destroys all windows and kills all apps on that display. Gone. Dead.

When the user sends a follow-up task, `reacquirePlatform()` creates a *brand
new* VD. Fresh display, fresh apps, fresh context. Everything the agent
accomplished is lost.

This is wrong. The virtual display is the agent's workspace. You don't demolish
the office at the end of every meeting.

### 2. Phantom keyboard on main screen

Agent clicks a search box on VD. Android's InputMethodService decides to show
the keyboard on display 0 (the user's main screen). Random keyboard pops up in
the user's face while they're reading an article.

**Root cause**: Android's IME system is fundamentally single-display on most
devices and Android versions. When an EditText gains focus on any display, the
IME shows on the "default" display (display 0). The VD flag
`FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` (0x800) is supposed to route IME to the
VD, but most IME implementations ignore it.

**Current mitigation** (`VirtualDisplayPlatform.kt:298-301`):
```kotlin
if (action is UIAction.SetTextOnNodeAt || action is UIAction.SetTextOnFocused) {
    appController.dismissMainDisplayKeyboard()
}
```

This fires `input keyevent --display 0 4` (BACK key) after text actions. Three
problems:

1. **Incomplete scope**: Only fires after text actions. Clicks on text fields
   also trigger IME but aren't covered.
2. **Destructive side effect**: BACK key navigates backward on the main screen.
   If the user has a dialog open or is in a nested activity, we just dismissed
   it.
3. **Reactive, not preventive**: Keyboard appears, then gets dismissed. The user
   sees a flicker.

---

## The Fix

### 1. VD Persistence: Stop Destroying Things You Need

The VD should live as long as the session. Not the task.

```
BEFORE:
  Task start  --> VD create
  Task end    --> VD destroy      <-- apps die here
  Follow-up   --> VD create       <-- new VD, lost context

AFTER:
  First task  --> VD create (if not already running)
  Task end    --> nothing          <-- apps keep running
  Follow-up   --> VD already alive <-- apps preserved, music still playing
  Session end --> VD destroy       <-- final cleanup
```

#### What changes

**`AgentSession.kt` — `handleAgentComplete()`**: Remove the `platform.stop()`
call. The VD stays alive during Hot Idle.

```kotlin
// BEFORE:
private suspend fun handleAgentComplete(reason: AgentStopReason) {
    // ... emit TaskCompleted, flush checkpoint ...
    _state.value = SessionState.Idle
    currentTaskId = null
    agentRunner.clear()
    try {
        services.platform.stop()   // <-- DELETE THIS
    } catch (e: Exception) { ... }
    scheduleIdleTimeout()
}

// AFTER:
private suspend fun handleAgentComplete(reason: AgentStopReason) {
    // ... emit TaskCompleted, flush checkpoint ...
    _state.value = SessionState.Idle
    currentTaskId = null
    agentRunner.clear()
    // VD stays alive. Apps keep running. Music keeps playing.
    scheduleIdleTimeout()
}
```

**`VirtualDisplayPlatform.kt` — `start()`**: Make idempotent. When
`reacquirePlatform()` calls `start()` for a follow-up task, the VD is already
running. Don't crash — just return.

```kotlin
// BEFORE:
override suspend fun start() {
    check(displayId == Display.INVALID_DISPLAY) { "Already started" }
    // ... create VD ...
}

// AFTER:
override suspend fun start() {
    if (displayId != Display.INVALID_DISPLAY) return  // Already running
    // ... create VD ...
}
```

**`handleShutdown()`**: Unchanged. `services.cleanup()` calls
`platform.stop()` and destroys the VD. This is the correct place. Idle timeout
(5 min) triggers shutdown, which destroys the VD. Session reload creates a new
one. Clean.

#### Resource cost during idle

An active VD with ImageReader costs:
- ~20MB RAM (2 RGBA buffers at phone resolution)
- Minimal GPU compositing

For a 5-minute idle timeout, this is nothing. We already keep session state,
history, LLM client, and scratchpad in memory. Don't optimize what doesn't
matter.

#### What about hibernation?

The Android VirtualDisplay API supports `setSurface(null)` to detach the surface
without destroying the display. We could release the ImageReader during idle and
re-attach on the next task. This saves ~20MB and stops GPU compositing.

Not worth it for V1. The complexity outweighs the savings. If idle duration
becomes configurable or we need to keep VDs alive for longer, revisit then.

#### Edge cases

| Scenario | Behavior |
|---|---|
| Idle timeout (5 min) | `handleShutdown()` → `platform.stop()` → VD destroyed. Correct. |
| Process death during idle | OS cleans up VD. Session reload creates new one. Fine. |
| Shizuku dies during idle | Binder death listener fires. Next `start()` creates new VD. Fine. |
| Multiple rapid tasks | VD stays alive. Agent sees previous task's screen state. Navigates from there. This is correct — same as a real phone. |
| User sends follow-up | `reacquirePlatform()` calls `start()` → idempotent return. Agent runs on existing VD. YouTube is still playing. |

---

### 2. Keyboard: Use the Right API

Replace the shell BACK-key hack with the AccessibilityService's built-in
keyboard control: `SoftKeyboardController.setShowMode()`.

This API exists since API 24. We target API 31+. It tells the system to suppress
or allow the soft keyboard. No shell commands. No side effects. No BACK key
navigating the user out of their app.

#### Strategy: Pulse Suppression

We can't suppress the keyboard for the entire task duration — the user might
need to type a supplement note in the Smart Capsule while the agent is working.
Instead, we suppress around each action execution with a debounced restore:

```
Action 1:    [--HIDDEN--]
                         [~~~500ms~~~]
Action 2:               [--HIDDEN--]     (cancels pending restore)
                                    [~~~500ms~~~]
                                                [SHOW_MODE_AUTO restored]
                                                |
                                                v  user can type here
[LLM thinking..............................][Action 3][...next think...]
```

The keyboard is hidden for the duration of action execution plus 500ms. Between
turns (during LLM thinking, ~2-4 seconds), the keyboard is available. If
actions are rapid-fire, the restore keeps getting debounced — keyboard stays
hidden for the entire action sequence. Clean.

#### What changes

**`VirtualDisplayPlatform.kt`** — add suppress/restore around `performAction()`:

```kotlin
private val mainHandler = Handler(Looper.getMainLooper())
private val keyboardRestoreToken = Any()

override suspend fun performAction(action: UIAction): ActionResult {
    suppressKeyboard()
    try {
        return doPerformAction(action)
    } finally {
        scheduleKeyboardRestore()
    }
}

private fun suppressKeyboard() {
    // Cancel any pending restore — we're about to do another action
    mainHandler.removeCallbacksAndMessages(keyboardRestoreToken)
    try {
        service.softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to suppress keyboard", e)
    }
}

private fun scheduleKeyboardRestore() {
    mainHandler.postAtTime({
        try {
            service.softKeyboardController.setShowMode(SHOW_MODE_AUTO)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore keyboard", e)
        }
    }, keyboardRestoreToken, SystemClock.uptimeMillis() + IME_SUPPRESS_DELAY_MS)
}

private companion object {
    const val IME_SUPPRESS_DELAY_MS = 500L
}
```

**`VirtualDisplayPlatform.kt` — `stop()`**: Always restore keyboard on
shutdown. If we crash during SHOW_MODE_HIDDEN and don't restore, the user's
keyboard is permanently disabled. This must not happen.

```kotlin
override suspend fun stop() {
    // Restore keyboard FIRST, before anything else
    mainHandler.removeCallbacksAndMessages(keyboardRestoreToken)
    try {
        service.softKeyboardController.setShowMode(SHOW_MODE_AUTO)
    } catch (_: Exception) {}

    // ... existing cleanup ...
}
```

**Remove the old hack**: Delete the `dismissMainDisplayKeyboard()` call from
`performAction()` and the `dismissMainDisplayKeyboard()` method from
`VirtualDisplayAppController.kt`. The shell BACK-key hack is dead. Good
riddance.

#### Prerequisite: Accessibility Service Flag

`SoftKeyboardController.setShowMode()` requires the accessibility service to
declare the `flagRequestSoftKeyboardController` capability. Check if our
`AgentService` already declares it in `accessibility_service_config.xml` or via
`setServiceInfo()`. If not, add it:

```xml
<!-- res/xml/accessibility_service_config.xml -->
<accessibility-service
    ...
    android:accessibilityFlags="...|flagRequestSoftKeyboardController"
    ... />
```

Without this flag, `setShowMode()` silently does nothing.

#### Why not suppress for the entire task?

It would be simpler: set HIDDEN at task start, AUTO at task end. But the Smart
Capsule's supplement input ("Got ideas? Add a note...") needs the keyboard
during task execution. Suppressing for the whole task breaks supplement input.
The pulsed approach keeps the keyboard available between actions (~1.5-3s gaps
during LLM thinking).

---

## Summary of Changes

| File | Change | Lines |
|---|---|---|
| `AgentSession.kt` | Remove `platform.stop()` from `handleAgentComplete()` | -5 |
| `VirtualDisplayPlatform.kt` | Make `start()` idempotent (check → early return) | ~1 |
| `VirtualDisplayPlatform.kt` | Add keyboard suppress/restore around `performAction()` | ~25 |
| `VirtualDisplayPlatform.kt` | Restore keyboard in `stop()` safety net | ~5 |
| `VirtualDisplayPlatform.kt` | Remove old `dismissMainDisplayKeyboard()` call | -3 |
| `VirtualDisplayAppController.kt` | Remove `dismissMainDisplayKeyboard()` method | -6 |
| `accessibility_service_config.xml` | Add `flagRequestSoftKeyboardController` (if missing) | ~1 |

Total: ~30 lines changed. No new files. No new abstractions. No new state
machines.

## What We're NOT Doing

- **No hibernate/wake pattern**: `setSurface(null)` to pause VD rendering during
  idle is clever but unnecessary for a 5-minute timeout. Adds complexity for
  negligible savings.
- **No per-display IME routing**: Android doesn't reliably support this. Don't
  fight the system.
- **No new VD lifecycle states**: The VD is either running or stopped. That's it.
  No "hibernating", no "paused", no "suspended". Two states. Clean.
- **No backward compatibility shims**: The old BACK-key hack gets deleted, not
  wrapped. The product isn't released. Clean code > compatibility.

## Testing

### VD Persistence
1. Start a VD task: "Open YouTube and play a song"
2. Wait for task completion (session enters Hot Idle)
3. Verify music is still playing
4. Send a follow-up: "Now open Gmail"
5. Verify agent navigates from the current VD screen (YouTube still visible)
   rather than starting from a blank display

### Keyboard Suppression
1. Start a VD task that involves text input: "Search for restaurants on Google
   Maps"
2. Watch the main screen while agent operates on VD
3. Verify no keyboard appears on the main screen at any point
4. While the agent is thinking (between actions), tap the Smart Capsule
   supplement input and verify the keyboard appears normally
5. Verify keyboard works normally after task completes

### Safety
1. Force-kill the app while a VD task is running
2. Reopen the app
3. Verify the keyboard works normally (SHOW_MODE_AUTO restored)

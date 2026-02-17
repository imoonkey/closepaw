# Virtual Display Review — Deep Analysis

> Reviewer: Cursor (claude-4.6-opus)
> Scope: Phases 1–3 implementation (c974ceb…d78e969) + ad hoc fix commit (0403bd2)
> Method: /ultra-think + /code-review + /coding-standards
> Date: 2026-02-11

---

## Executive Summary

The virtual display implementation is architecturally sound — the `AndroidPlatform`
abstraction held up beautifully, the factory pattern works, and the agent loop is
genuinely unaware of which display it runs on. That's exactly what good abstraction
looks like.

However, the ad hoc fix commit (0403bd2) reveals a class of systemic issues that
go beyond point fixes. The keyboard popup, the display flag corrections, the
`getWindowsOnAllDisplays()` fix — these all stem from one root cause: **virtual
displays are a second-class citizen in Android's framework**, and every API that
assumes "one display" needs to be audited.

This review identifies 4 critical, 5 high, and 6 medium issues. The most important
is the cross-display IME leak, which directly causes the keyboard popup you observed.

---

## Critical (Must Fix)

### C1. Cross-Display IME Leak — The Keyboard Popup

**What happens**: When the agent calls `ACTION_SET_TEXT` on a node in the virtual
display (display 12), the Android `InputMethodManager` sees a focus change and
binds the IME service (`com.bytedance.android.doubaoime/.ImeService`) to that
window. But the IME window *renders on the main display* (display 0) because the
IME service is display-scoped to wherever the system thinks "the foreground" is.

**Evidence from `run_20260210_170907`**:
```
turn_001: InputMethodManager bindService for doubaoime/.ImeService on client system
turn_007: InputMethod: startInput() on displayId=12
          → IME binds to YouTube's search field on display 12
          → IME window renders on display 0 (main screen)
```

**Why it's systemic**: This isn't just about `type`. ANY action that focuses an
editable field (click on search bar, long press on text) will trigger IME binding.
The problem exists in the Android framework's `InputMethodManagerService`, which
routes the IME window to the "default" display unless the IME itself is
multi-display-aware (most aren't).

**Fix — two-pronged**:
1. **After `SetTextOnNodeAt` / `SetTextOnFocused`**: inject `KEYCODE_BACK` on
   display 0 (the main display) to dismiss the leaked keyboard. This is a band-aid
   but solves the user-visible problem immediately.
2. **Proactive**: After any successful `ACTION_SET_TEXT`, clear input focus on the
   virtual display's node via `ACTION_CLEAR_FOCUS`. This prevents the
   `InputMethodManager` from holding a connection.

```kotlin
// In setTextOnNode(), after successful ACTION_SET_TEXT:
if (ok) {
    node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
}
```

**Why point 1 matters**: The agent agent operates in a loop, and the leaked keyboard
on display 0 may capture subsequent key events meant for the user's real session.

### C2. `getCurrentPackageName()` Leaks AccessibilityNodeInfo

```kotlin
override fun getCurrentPackageName(): String? {
    val appWindow = getAppWindowOnDisplay() ?: return null
    return appWindow.root?.packageName?.toString()  // root is NEVER recycled
}
```

`AccessibilityWindowInfo.getRoot()` creates a new `AccessibilityNodeInfo` that must
be `recycle()`d. This is called every turn by the agent framework to track the
foreground app. Over a 20-turn session, that's 20+ leaked node infos with their
backing Binder objects.

**Fix**:
```kotlin
override fun getCurrentPackageName(): String? {
    val appWindow = getAppWindowOnDisplay() ?: return null
    val root = appWindow.root ?: return null
    return try {
        root.packageName?.toString()
    } finally {
        root.recycle()
    }
}
```

**Systemic**: Audit every call to `.root` — `getRootOnDisplay()` callers all
properly recycle in try/finally, but this one was missed.

### C3. `TypeExecutor` Fallback Path Injects Tap on Wrong Display... Sometimes

When `SetTextOnNodeAt` fails and `TypeExecutor` falls through to:
```kotlin
val tapResult = platform.performAction(UIAction.TapAt(point.x, point.y))
```

This `TapAt` uses `injectTap()` which injects via `IInputManager.injectInputEvent`.
The input event has `displayId` set correctly (good). But the tap itself focuses
the field, which triggers IME binding, which shows the keyboard on display 0.

This is the same IME leak as C1, but triggered by a *different code path*. After
the tap focuses the field, `SetTextOnFocused` sets the text via `ACTION_SET_TEXT`,
doubling the IME trigger.

**Fix**: After the full type sequence, dismiss IME:
```kotlin
// At the end of TypeExecutor.execute(), after any successful type:
platform.performAction(UIAction.SystemButton(SystemButtonType.BACK))
// Only on virtual display — need a way to check
```

Better: add a `dismissKeyboard()` method to `AndroidPlatform` (no-op on
`AccessibilityPlatform`, `KEYCODE_BACK` injection on `VirtualDisplayPlatform`).

### C4. Agent Stuck in Loop — Type Succeeds but Text Doesn't Appear

In `run_20260210_170907`, turns 5–10 ALL execute the same action:
```
mobile_action({"action":"type","element_index":1,"input_text":"周深 歌曲","clear":true})
→ "Success: Typed into element at (646,84)"
```

The `ACTION_SET_TEXT` returns `true` every turn, but the agent keeps retrying,
suggesting the text never actually appears in YouTube's search field. This could be
because:

1. The IME connection intercepts the `ACTION_SET_TEXT` and routes it to its own
   input buffer rather than the actual `EditText`.
2. YouTube's search uses a custom `InputConnection` that ignores `ACTION_SET_TEXT`
   from accessibility.
3. The node being targeted is the search icon, not the actual text field (element
   index 1 at coordinates 646,84 might be the search suggestion row, not the input).

**This is the most user-visible bug**: the agent thinks it typed successfully but
nothing happened. The fix:

1. **Verify text was actually set**: After `ACTION_SET_TEXT`, re-read the node's
   `text` property and compare to what we set. If they don't match, return failure
   instead of success.
2. **Alternative text input**: For virtual display, consider character-by-character
   `KeyEvent` injection via Shizuku instead of `ACTION_SET_TEXT`. This bypasses the
   a11y→IME pipeline entirely.

---

## High (Should Fix)

### H1. Verbose Logging in Hot Path — `getWindowsOnDisplay()`

```kotlin
val windowSummary = displayWindows.joinToString(", ") {
    "Window(id=${it.id}, display=${it.displayId}, title=${it.title}, type=${it.type})"
}
Log.d(TAG, "Windows on display $displayId: $windowSummary")
```

This runs on EVERY `captureScreen()` call (every turn), AND again when looking for
the app window. With 10 turns, that's 20+ string allocations and Log.d calls for
debug info. In the trace files, these lines appear on every single turn.

**Fix**: Gate behind a `BuildConfig.DEBUG` check or use `Log.isLoggable()`:
```kotlin
if (Log.isLoggable(TAG, Log.DEBUG)) {
    Log.d(TAG, "Windows on display $displayId: ${displayWindows.map { it.title }}")
}
```

Or even better: log only when the window list *changes* from last time.

### H2. Binder Proxy Not Cached — O(n) Allocation per Swipe Step

Every call to `injectInputEvent()` calls `getInputManagerProxy()`, which:
1. Gets the raw binder via `SystemServiceHelper.getSystemService("input")`
2. Wraps it in `ShizukuBinderWrapper`
3. Reflects on `IInputManager$Stub.asInterface()` to create a proxy

A swipe injects 22 events (1 DOWN + 20 MOVE + 1 UP). That's 22 binder proxy
creations. Each involves 3 reflection calls and object allocations.

**Fix**: Cache the proxy in `ShizukuClient`:
```kotlin
private var cachedInputProxy: Any? = null

private fun getInputManagerProxy(): Any {
    cachedInputProxy?.let { return it }
    val proxy = createInputManagerProxy()
    cachedInputProxy = proxy
    return proxy
}
```

Add invalidation on binder death. Same treatment for `getDisplayManagerProxy()`.

### H3. Dead Code: `createNullCallbackProxy()`

```kotlin
private fun createNullCallbackProxy(): Pair<Any, Class<*>> {
    val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
    val loader = Class.forName("android.hardware.display.IVirtualDisplayCallback\$Stub").classLoader
    val proxy = Proxy.newProxyInstance(loader, arrayOf(callbackClass)) { _, _, _ -> null }
    return proxy to callbackClass
}
```

This was the original approach — creating a dynamic proxy for the callback. The fix
replaced it with a real AIDL implementation (`IVirtualDisplayCallback.Stub`). But
the old method is still in the file, unused.

**Fix**: Delete it.

### H4. `executeShellCommand()` Uses Reflection Unnecessarily

```kotlin
val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
val newProcessMethod = shizukuClass.getMethod("newProcess", ...)
val process = newProcessMethod.invoke(null, command, null, null) as Process
```

`Shizuku.newProcess()` is a public API. No reflection needed:
```kotlin
val process = Shizuku.newProcess(command, null, null)
```

Also: `process.errorStream.bufferedReader().use { it.readText() }` is called AFTER
`process.waitFor()`. On some Android Process implementations, the stream may be
partially consumed or closed by the time `waitFor()` returns. Read stderr
concurrently or before `waitFor()`.

### H5. `launchApp()` Dual-Path Has No Coordination

```kotlin
if (component != null && shizukuAvailable) {
    val cmd = arrayOf("am", "start", "-n", component, "--display", "$displayId", "-W")
    val code = shizuku.executeShellCommand(cmd)
    if (code == 0) return@withContext ...
    Log.w(TAG, "Shell launch failed (code $code), falling back to intent")
}
// Fallback to standard Intent launch
shizuku.launchOnDisplay(service, launchIntent, displayId)
```

If the shell `am start` fails (code != 0) and the intent fallback also fails, we
get one log warning and one exception — but the user sees "Launched... (intent)"
even if the activity didn't actually start. There's no verification that the app
window appeared on the virtual display.

Also: The `am start -W` can block for several seconds. Since this runs on
`Dispatchers.IO`, it blocks a pool thread. Consider a timeout.

---

## Medium (Consider)

### M1. File Size: VirtualDisplayPlatform.kt is 773 Lines

Coding standards say max 400 lines per file. The file has three natural extraction
boundaries:

1. **Input injection** (lines 449–558): `injectTap`, `injectLongPress`,
   `injectSwipe`, `injectSystemButton` → Extract to `VirtualDisplayInputInjector`
2. **Node-based actions** (lines 298–447): `performNodeClickAt`, `performSetText*`,
   `setTextOnNode` → Extract to `VirtualDisplayNodeActions`
3. **Helpers** (lines 675–773): event construction, `setDisplayId` →
   Extract to `VirtualDisplayEventFactory`

This also makes testing easier — you can unit test input injection without
the full platform.

### M2. `captureA11yTree()` Threading

```kotlin
private fun captureA11yTree(): List<PerceptionElement> {
    val root = getRootOnDisplay() ?: return emptyList()
    return try {
        Perceptor.snapshot(root, config.width, config.height).elements
    } catch (e: Exception) { ... }
}
```

This is called from `captureScreen()` which is `suspend` but
`captureA11yTree()` itself is not. It accesses `service.getWindowsOnAllDisplays()`
(an AccessibilityService method) without explicitly dispatching to Main.

In `AccessibilityPlatform`, equivalent code uses `Dispatchers.Main`. The a11y
service methods may not be thread-safe on all OEM implementations.

**Fix**: Wrap in `withContext(Dispatchers.Main)`.

### M3. Display Flags as Magic Numbers

```kotlin
private const val DISPLAY_FLAGS =
        0x1 or   // VIRTUAL_DISPLAY_FLAG_PUBLIC
        0x8 or   // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                0x40 or  // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                0x200 or // VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                0x400 or // VIRTUAL_DISPLAY_FLAG_TRUSTED
                0x800    // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
```

Named constants would be self-documenting and auditable:
```kotlin
private const val FLAG_PUBLIC = 0x1
private const val FLAG_OWN_CONTENT_ONLY = 0x8
private const val FLAG_SUPPORTS_TOUCH = 0x40
private const val FLAG_SHOW_SYSTEM_DECORATIONS = 0x200
private const val FLAG_TRUSTED = 0x400
private const val FLAG_OWN_DISPLAY_GROUP = 0x800

private const val DISPLAY_FLAGS = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or
    FLAG_SUPPORTS_TOUCH or FLAG_SHOW_SYSTEM_DECORATIONS or
    FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP
```

### M4. Formatting Changes Pollute the Diff

The fix commit (0403bd2) changes 567 insertions / 327 deletions, but the vast
majority is formatting changes (4-space → mixed indentation). This makes the
actual logic changes nearly invisible in code review.

Looking at the diff: KDoc comments got reflowed, parameter lists got re-indented
with 8-space alignment, and multi-line expressions got reformatted. This is likely
an auto-formatter with different settings than the rest of the codebase.

**Fix**: Run `ktfmt` or `ktlint --format` with the project's standard settings
once, then commit separately. Never mix formatting with logic changes.

### M5. `AccessibilityWindowInfo` Not Recycled

`getWindowsOnDisplay()` calls `service.getWindowsOnAllDisplays()` which returns
`SparseArray<List<AccessibilityWindowInfo>>`. The `AccessibilityWindowInfo` objects
should be recycled when no longer needed (they hold Binder references).

Currently, the windows are used in `getAppWindowOnDisplay()` and then discarded
without recycling. Over many turns, this accumulates stale window objects.

### M6. `AIDL` Addition Contradicts Design Doc

The design doc (final_design.md §4.4) states:
> "No AIDL files needed. We use reflection on the framework's own stub classes."

But the fix commit adds `IVirtualDisplayCallback.aidl`. This is actually the
*right* choice (dynamic proxy was fragile), but the design doc should be updated
to reflect reality.

---

## Systemic Patterns ("以点见面")

The point fixes in 0403bd2 reveal three systemic themes:

### Pattern 1: "The Display Isolation Gap"

Android's framework assumes one display in many places. Every time you touch an
API that was designed for single-display, you need to verify it works correctly
on a secondary display. Specific areas to audit:

| API / Feature | Risk | Status |
|---|---|---|
| `InputMethodManager` | IME window renders on wrong display | **CONFIRMED BUG** (C1) |
| `service.windows` | Only returns display 0 windows | **FIXED** in 0403bd2 |
| `context.startActivity()` | May launch on display 0 | **FIXED** (shell `am start --display`) |
| `Toast` / `Dialog` | Will show on display 0 | Not yet audited |
| `ClipboardManager` | Shared across displays | May cause confusion |
| Notifications | Will show on display 0 | Acceptable (desired) |
| `MediaSession` / audio focus | Shared across displays | May cause audio conflicts |

### Pattern 2: "Success ≠ Visible Effect"

`ACTION_SET_TEXT` returns `true` but the text doesn't appear. `injectInputEvent`
returns `true` but the touch hits the wrong target. The `ActionResult.Success`
from the platform doesn't guarantee the user-visible effect occurred.

This pattern affects ALL actions, not just type. The fix should be at the
`TypeExecutor` / executor layer: **verify the effect, not just the API return
value**.

For type: re-read the node's text after setting.
For click: re-capture screen and check for change.
For swipe: verify scroll position changed.

The current `ActionOutcome.verified = true` is set unconditionally — it should
be meaningful.

### Pattern 3: "Reflection Fragility"

The codebase uses reflection in 6+ places:
1. `IDisplayManager.createVirtualDisplay` (3 signatures for API 31/33/alt)
2. `IInputManager.injectInputEvent`
3. `ActivityOptions.setLaunchDisplayId`
4. `InputEvent.setDisplayId`
5. `Shizuku.newProcess`
6. `VirtualDisplayConfig.Builder` methods

Each is a potential point of failure on a different OEM/Android version. The current
error handling catches `Exception` broadly, which is correct, but there's no
*reporting* — if `setDisplayId` fails silently (line 769, just a `Log.w`), every
subsequent input event goes to display 0 without anyone knowing.

**Recommendation**: Create a `VirtualDisplayHealthCheck` that runs at `start()`
time and verifies each critical reflection call works. Fail fast with a clear
error rather than discovering problems mid-session.

---

## The "Agent Stuck" Problem Deep Dive

The `run_20260210_170907` trace shows:
- Turn 1: Platform starts, display 12 created
- Turn 2: YouTube launched on display 12 via `am start --display 12 -W`
- Turn 3–4: Agent clicks YouTube search icon (ACTION_CLICK)
- Turn 5–10: Agent repeatedly types "周深 歌曲" with `clear=true`
  → All return "Success" but the text never appears → agent retries

**Root cause hypothesis**: YouTube's search bar is a custom view that uses
`InputConnection` for text input, not `AccessibilityNodeInfo.setText()`. When
`ACTION_SET_TEXT` is called, the accessibility framework calls
`Bundle.putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)` on the node,
which may:
1. Set the text on the `AccessibilityNodeInfo` representation (making the a11y
   layer think it worked → returns `true`)
2. But NOT propagate to the actual `EditText`/`InputConnection` because the
   `InputMethodManager` has an active connection to the IME

This is a fundamental limitation of `ACTION_SET_TEXT` for apps with custom input
handling. The solution is character-by-character `KeyEvent` injection via Shizuku,
which goes through `InputDispatcher` → `InputChannel` → the actual view. This is
how `adb shell input text` works and is universally reliable.

---

## Refactoring Recommendations (Priority Order)

### 1. Add IME Dismissal After Text Input (C1, C3, C4) — Immediate

Add `dismissKeyboard()` to the platform interface. In `VirtualDisplayPlatform`,
implement it as `KEYCODE_BACK` injection on display 0 (the main display, not
the virtual display). Call it after every successful type action.

### 2. Add Text Verification to TypeExecutor (C4) — Immediate

After `ACTION_SET_TEXT`, re-read the node's text and compare. If mismatch,
fall through to the tap-to-focus path or report failure.

### 3. Fix `getCurrentPackageName()` Leak (C2) — Quick Fix

Add `try/finally` with `root.recycle()`.

### 4. Cache Binder Proxies in ShizukuClient (H2) — Short Term

Cache `inputManagerProxy` and `displayManagerProxy`. Invalidate on binder death.
This reduces per-event allocation from 22 proxy creations per swipe to 1.

### 5. Extract VirtualDisplayPlatform into Smaller Files (M1) — Medium Term

Split into:
- `VirtualDisplayPlatform.kt` — lifecycle + captureScreen + dispatch
- `VirtualDisplayInputInjector.kt` — tap, swipe, long press, system buttons
- `VirtualDisplayNodeActions.kt` — click, setText, focus management

### 6. Add KeyEvent-Based Text Input (C4) — Medium Term

For virtual display mode, add a `KeyEvent`-based text input method that injects
characters one at a time. Use as primary for VD (more reliable), fall back to
`ACTION_SET_TEXT` only if injection fails.

### 7. Health Check at Start (Pattern 3) — Medium Term

Verify all reflection targets exist before returning from `start()`. Fail fast
with actionable error message if any are missing.

---

## What The Fix Got Right

Credit where due — several things in 0403bd2 were excellent:

1. **`getWindowsOnAllDisplays()`**: The original `service.windows` only returns
   display 0. This was a critical bug that would have made the entire a11y tree
   empty on the virtual display. Correctly switching to API 33+
   `getWindowsOnAllDisplays()` with legacy fallback is the right call.

2. **Real AIDL callback**: Replacing the fragile `Proxy.newProxyInstance()` null
   callback with a proper `IVirtualDisplayCallback.Stub` implementation is more
   robust. The dynamic proxy could fail if the framework added new methods.

3. **Shell `am start --display`**: Using `am start -n component --display $displayId`
   is more reliable than `ActivityOptions.setLaunchDisplayId()` via intent, because
   the shell command runs as the Shizuku (shell) user which has the
   `START_ACTIVITIES_FROM_BACKGROUND` permission.

4. **`FLAG_RETRIEVE_INTERACTIVE_WINDOWS`**: Adding this flag to the
   `AccessibilityServiceInfo` ensures the service can see windows on all displays,
   not just the focused one. Without this, `getWindowsOnAllDisplays()` would
   return empty for the virtual display.

5. **Display flag corrections**: Fixing `0x800` (was OWN_CONTENT_ONLY, actually
   OWN_DISPLAY_GROUP) to `0x8` (actual OWN_CONTENT_ONLY) and adding `0x400`
   (TRUSTED) + `0x800` (OWN_DISPLAY_GROUP) creates a properly isolated display.

6. **Package name `"com.android.shell"`**: Masquerading as the shell package for
   `createVirtualDisplay` is correct — Shizuku runs as shell UID, so the
   framework's permission check expects the shell package name.

---

## Recommendation

**CHANGES REQUESTED** — The implementation architecture is solid but has critical
runtime issues (IME leak, text verification, node leak) that will affect every
agent run on virtual display. Fix C1–C4 before considering this feature stable.

---

## Quick Reference: All Issues

| ID | Severity | Summary | Effort |
|---|---|---|---|
| C1 | Critical | Cross-display IME keyboard leak | 2h |
| C2 | Critical | `getCurrentPackageName()` leaks root node | 10m |
| C3 | Critical | TypeExecutor tap-to-focus triggers IME on display 0 | Part of C1 |
| C4 | Critical | ACTION_SET_TEXT succeeds but text doesn't appear | 4h |
| H1 | High | Verbose logging on every captureScreen() | 30m |
| H2 | High | Binder proxy created on every input event | 1h |
| H3 | High | Dead code: `createNullCallbackProxy()` | 5m |
| H4 | High | `executeShellCommand` uses unnecessary reflection | 30m |
| H5 | High | `launchApp` dual-path has no launch verification | 1h |
| M1 | Medium | VirtualDisplayPlatform.kt 773 lines (limit 400) | 2h |
| M2 | Medium | `captureA11yTree()` not on Main dispatcher | 15m |
| M3 | Medium | Display flags as magic hex numbers | 15m |
| M4 | Medium | Formatting changes mixed with logic changes | N/A |
| M5 | Medium | AccessibilityWindowInfo not recycled | 30m |
| M6 | Medium | Design doc contradicts AIDL reality | 10m |

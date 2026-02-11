# Virtual Display Post-Implementation Review

> Commit under review: `0403bd2` (bug fixes after Phase 3 implementation)
> Files analyzed: `VirtualDisplayPlatform.kt` (774 lines), `ShizukuClient.kt` (400 lines), `AgentService.kt`, `PlatformFactory.kt`, `AndroidPlatform.kt`, `AccessibilityPlatform.kt`, `debug-run.sh`, `MainActivityIntentPayload.kt`, `MainActivity.kt`
> Debug output: `run_20260210_170907` (10 turns, "play a 周深 song on youtube")

---

## 1. Keyboard Popup Bug — Root Cause Analysis

### What happened

At turn 3, the agent clicked the YouTube search icon on the virtual display via `mobile_action` → `click` → element 3 ("Search"). This used `ClickNodeAt` → `performNodeClickAt` → `AccessibilityNodeInfo.performAction(ACTION_CLICK)`.

The Android IME (`com.bytedance.android.doubaoime`) appeared on the **main display** rather than the virtual display.

**Evidence chain:**
1. agent.log L291: `click, element_index=3` — clicking YouTube search
2. system.log L138-139: `com.bytedance.android.doubaoime, SoftInputWindow` — IME appears
3. Screenshots: turn_003 has no keyboard, turn_004 shows keyboard over agent UI on main display

### Why it happened

The `performAction(ACTION_CLICK)` call goes through the accessibility service, which correctly targets the VD node. But the **InputMethodManager** (IMM) binds to the **app's window token**, not the display. When YouTube's search field gains focus on the VD, the system's IMM shows the soft keyboard on whatever display has the active input connection — which is the main display.

This is a **known Android limitation**: `InputMethodManager` is scoped to the window/process, not the display. Virtual displays don't get independent IME windows unless explicitly configured with `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` + additional InputMethodService configuration.

### Impact assessment

- **Severity: Medium**. The keyboard appears on the wrong display, but the agent still worked — it used `SetTextOnFocused` (accessibility `ACTION_SET_TEXT`) to input text directly, bypassing the keyboard entirely.
- The keyboard obscured the agent UI on the main display (visible in turn_004, turn_005 screenshots), but since the user is not actively using the main display during agent operation, this is cosmetic.

### Proposed fix

```
// Priority 1: Dismiss keyboard after text input via Shizuku
private suspend fun dismissKeyboardOnMainDisplay() {
    val cmd = arrayOf("input", "keyevent", "KEYCODE_BACK")
    shizuku.executeShellCommand(cmd)
}

// Priority 2: Use VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY in VD creation
// This may prevent IME leaking, but needs testing
```

**Recommended approach**: After any `SetTextOnNodeAt`/`SetTextOnFocused` action completes, inject a `KEYCODE_ESCAPE` or `KEYCODE_BACK` to the main display to dismiss the leaked keyboard. This is a pragmatic fix. A deeper fix requires configuring the VD to own its own IME focus, which is a larger architectural change.

---

## 2. Systemic Issues Identified

### 2.1 Screenshot-Display Mismatch

**Problem**: Debug screenshots (`turn_XXX_nN.png`) capture the **main display**, while the a11y tree elements come from the **virtual display**. This makes debugging extremely confusing — the screenshot shows the agent UI while the elements describe YouTube.

**Root cause**: When `VirtualDisplayPlatform` is active, `captureScreen()` captures the VD via ImageReader for the a11y tree, but the debug screenshot persisting logic may fall back to the main display's `takeScreenshot()`.

**Fix**: Ensure `persistDebugScreenshot` uses the VD's ImageReader output, not the main display screenshot.

### 2.2 Missing `start()`/`stop()` lifecycle for ShizukuClient

**Problem**: `ShizukuClient` is created in `PlatformFactory.createVirtualDisplayPlatform()` as a local val and passed to `VirtualDisplayPlatform`. But `ShizukuClient` manages binder connections, death recipients, and possibly cached proxies. There's no cleanup path when the session ends or Shizuku dies.

**Evidence**: `ShizukuClient.kt` registers `addLifecycleCallback` and holds references to `IDisplayManager`, `IInputManager` stubs obtained via reflection. If `VirtualDisplayPlatform.stop()` releases the VD but doesn't clean up ShizukuClient's cached proxies, you can get stale binder references.

**Fix**: Add explicit `close()`/`cleanup()` to `ShizukuClient` and call it from `VirtualDisplayPlatform.stop()`.

### 2.3 Inconsistent error propagation: ShizukuClient × VirtualDisplayPlatform

**Problem**: `ShizukuClient` methods return boolean for success/failure (e.g., `injectInputEvent` returns `Boolean`), while `VirtualDisplayPlatform` wraps these in `ActionResult.Success`/`Failure`. But the boolean gives no diagnostic information — if injection fails, we get "Tap inject failed at (x,y)" with no indication of *why* (Shizuku died? Permission revoked? Wrong display?).

**Fix**: `ShizukuClient` should return a sealed result type instead of Boolean, carrying failure reasons.

### 2.4 Node-based actions don't verify target display

**Problem**: `performNodeClickAt`, `performSetTextOnNodeAt`, etc. use `getRootOnDisplay()` which filters by `displayId`. But `AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)` only checks coordinates relative to the root — it doesn't verify the matched node is actually on the correct display. In multi-display scenarios, the root might inadvertently serve nodes from the wrong display if the filtering is imprecise.

**Evidence**: `getWindowsOnDisplay()` logs available windows but doesn't validate that the found windows are interactive or have active content.

### 2.5 Concurrent display operations are not serialized

**Problem**: `performAction` is called from the agent loop. While the agent loop is single-threaded, `captureScreen()` runs on `Dispatchers.IO` while node actions run on `Dispatchers.Main`. If the agent loop captures screen while a previous action's IME animation is still in progress, the captured state may be inconsistent.

---

## 3. Code Quality Review

### 3.1 Good patterns preserved

- **Clean `AndroidPlatform` interface**: The abstraction is well-designed — `start()`/`stop()`/`captureScreen()`/`performAction()` provide a clean contract
- **`PlatformFactory` as single decision point**: Clean factory with Shizuku fallback
- **Sealed class for `ActionResult`**: Proper typed success/failure
- **Proper node recycling**: `try/finally` blocks consistently recycle `AccessibilityNodeInfo` instances

### 3.2 Areas of concern (spaghetti indicators)

**3.2.1 VirtualDisplayPlatform is too large (774 lines)**

This file handles five distinct concerns:
1. Display lifecycle (create/release VD)
2. Screen capture (ImageReader management)
3. A11y tree filtering (windows/roots by displayId)
4. Node-based actions (click, text, long-click via a11y)
5. Coordinate-based actions (tap, swipe, long-press via Shizuku)

Per project rule (max 400 lines/file), this should be split. Suggested decomposition:

| New file | Lines | Responsibility |
|---|---|---|
| `VirtualDisplayPlatform.kt` | ~120 | Orchestrator + lifecycle, delegates to others |
| `VirtualDisplayCapture.kt` | ~150 | ImageReader, screenshot, a11y tree capture |
| `VirtualDisplayNodeActions.kt` | ~150 | Node-based actions (click, text via a11y) |
| `VirtualDisplayInputInjector.kt` | ~100 | Coordinate-based actions (tap, swipe via Shizuku) |
| `VirtualDisplayWindowHelper.kt` | ~60 | `getWindowsOnDisplay`, `getRootOnDisplay` |

**3.2.2 Duplicated action patterns between AccessibilityPlatform and VirtualDisplayPlatform**

`performNodeClickAt`, `performSetTextOnNodeAt`, `setTextOnNode`, etc. are near-identical between the two platforms. The only difference is how they get the root node:
- `AccessibilityPlatform`: `service.rootInActiveWindow`
- `VirtualDisplayPlatform`: `getRootOnDisplay()` (display-filtered)

This is a textbook case for **template method pattern** or **shared base behavior**.

**3.2.3 ShizukuClient mixes abstraction levels**

`ShizukuClient.kt` (~400 lines) handles:
1. Shizuku lifecycle (availability, permissions, callbacks)
2. Display management (createVirtualDisplay, releaseVirtualDisplay)
3. Input injection (injectInputEvent)
4. Shell command execution
5. Reflection-based binder access

These could be separated into `ShizukuBridge` (lifecycle + reflection) and domain-specific clients.

**3.2.4 The `launchApp` double-path adds complexity**

The commit introduced a shell-based `am start --display` with fallback to `shizuku.launchOnDisplay`. While the two-path approach is pragmatic, the fallback silently uses a different mechanism that may or may not target the VD correctly. The fallback should either be removed (if shell always works) or explicitly tested.

### 3.3 Formatting-only changes in commit

A significant portion of the diff is **spotless/ktfmt reformatting** — indentation changes, argument wrapping, etc. This is noise in the commit history. Recommendation: run formatter as a separate commit.

---

## 4. Fix Categorization: Symptom vs Root Cause

| Fix | Category | Assessment |
|---|---|---|
| `am start --display $displayId` via shell | **Root cause** | Correct — Intent-based launch doesn't respect displayId |
| `getWindowsOnAllDisplays()` for API 33+ | **Root cause** | Correct — `service.windows` only returns default display |
| `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` in AgentService | **Root cause** | Without this, the service can't see any VD windows |
| AIDL callback instead of reflection proxy | **Root cause** | More reliable than dynamic proxy for binder callbacks |
| `com.android.shell` package identity | **Root cause** | Shell commands require correct process identity |
| `--virtual-display` in debug-run.sh | **Infrastructure** | Good — enables easy testing |
| IME keyboard popup | **Not yet fixed** | Need solution (see §1) |

**Verdict**: The fixes in `0403bd2` are **root cause fixes**, not symptom patches. They address fundamental platform API issues (display targeting, window enumeration, binder callbacks). *This is not spaghetti — it's necessary platform adaptation.*

---

## 5. Recommended Action Plan

### Priority 1 — Bug fixes
1. **IME keyboard dismissal** after text actions (§1)
2. **Debug screenshot source**: use VD ImageReader output for screenshots

### Priority 2 — Refactoring
3. **Split VirtualDisplayPlatform.kt** into 4-5 smaller files (§3.2.1)
4. **Extract shared a11y action logic** from AccessibilityPlatform and VirtualDisplayPlatform (§3.2.2)
5. **Add ShizukuClient.close()** lifecycle (§2.2)

### Priority 3 — Robustness
6. **ShizukuClient error types** instead of Boolean (§2.3)
7. **Remove or validate launchApp fallback path** (§3.2.4)
8. **Separate formatting commits** from logic commits (§3.3)

---

## 6. Summary

The virtual display implementation is **functionally correct and the fixes are sound**. The task completed successfully in 10 turns, demonstrating that the VD + a11y tree filtering + Shizuku input injection pipeline works end-to-end.

The keyboard popup is a real cross-display IME limitation in Android, not a code bug — but it needs a pragmatic workaround. The main refactoring need is splitting VirtualDisplayPlatform.kt (774 lines → ~5 focused files) and extracting shared a11y action logic.

The code is not spaghetti — the complexity is inherent to multi-display Android development. But it would benefit from the decomposition outlined above to maintain the project's 400-line-per-file standard.

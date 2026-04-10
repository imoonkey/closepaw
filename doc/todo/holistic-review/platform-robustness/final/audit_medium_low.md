# Platform Robustness Audit: Medium and Low Findings

Independent verification of the Medium and Low findings in `review.md` against actual source code.

---

## Medium #1: Live-preview surface replacement is ignored

**Review claim:** Once the surface controller is already in `LIVE_PREVIEW`, a new `SurfaceView` instance is ignored. Viewer recreation can strand the VD on a dead surface.

**Verdict: REAL BUG**

### Evidence

`VirtualDisplaySurfaceController.kt` line 51:
```kotlin
fun switchToLivePreview(surfaceView: SurfaceView) {
    synchronized(stateLock) {
        if (state.mode == VirtualDisplaySurfaceMode.LIVE_PREVIEW) return  // <-- early return
        ...
    }
}
```

The guard checks mode only, not surface identity. If the mode is already `LIVE_PREVIEW`, any new `SurfaceView` is silently rejected regardless of whether it is a different object backed by a different native Surface.

**Can a new SurfaceView actually arrive while already in LIVE_PREVIEW?**

Yes. The call path is:

1. `VirtualDisplayViewerActivity` creates a `SurfaceView` via Compose `AndroidView` factory.
2. `SurfaceHolder.Callback.surfaceCreated` fires and calls `AgentService.instance?.notifyViewerVisible(sv)`.
3. That flows through `AgentServiceViewerBridge.notifyViewerVisible` -> `VirtualDisplayPlatform.switchToLivePreview` -> `VirtualDisplaySurfaceController.switchToLivePreview`.

The Activity also re-delivers in `onStart()` (line 76-78):
```kotlin
override fun onStart() {
    super.onStart()
    surfaceView?.let { sv -> AgentService.instance?.notifyViewerVisible(sv) }
    AgentService.instance?.onViewerOpened()
}
```

During a configuration change (rotation), Android destroys and recreates the Activity. Because `isChangingConfigurations` is checked in `onStop()` to skip `finish()`, a rotation sequence is:
- Old surface: created -> `switchToLivePreview` succeeds -> mode becomes `LIVE_PREVIEW`.
- Old surface: destroyed -> `notifyViewerHidden()` -> calls `switchToImageReader()` -> mode becomes `IMAGE_READER`.
- New surface: created -> `switchToLivePreview` succeeds because mode is now `IMAGE_READER`.

So under normal rotation, the bug does not manifest because `notifyViewerHidden` resets the mode first. However, there is a real race condition: if `notifyViewerHidden` from the old Activity has not yet executed when the new Activity's `surfaceCreated` fires, the mode is still `LIVE_PREVIEW` and the new surface will be ignored. This is possible because both callbacks run on the main thread but through different scheduling paths (Compose recomposition for the new surface vs. Activity lifecycle for the old).

Additionally, if `switchToImageReader` fails (the shizuku `setVirtualDisplaySurface` call returns false at line 84), the mode stays `LIVE_PREVIEW` with a stale `liveSurfaceView` reference. Any subsequent new SurfaceView would be rejected.

**Severity assessment:** The review rates this Medium, which is accurate. The race window is small in practice (same thread, sequential lifecycle), but the `switchToImageReader` failure path makes the stranded-surface scenario real. A simple fix -- comparing surface identity instead of mode only -- is low-risk.

---

## Medium #2: Viewer shell fallback blocks caller thread

**Review claim:** On devices without hidden display-id injection, the fallback path runs shell input synchronously and waits for command completion, blocking touch forwarding.

**Verdict: REAL BUG**

### Evidence

The call chain:

1. `VirtualDisplayViewerTouchHandler.onViewerTouch()` is called from the Activity's touch listener (main thread).
2. When `inputInjector.supportsDisplayIdInjection()` returns false, it calls `injectViaShell()`.
3. On `ACTION_UP`, `injectViaShell()` calls `shizuku.executeShellCommand(command)` (line 127).
4. `ShizukuClient.executeShellCommand()` delegates to `ShizukuShellExecutor.execute()`.
5. `ShizukuShellExecutor.execute()` calls `newProcessViaShizuku()` and then `waitForProcess(process, 30, TimeUnit.SECONDS)`.

`waitForProcess` (line 43-72) is a polling loop with `Thread.sleep` -- it polls `process.exitValue()` with exponential backoff up to 100ms sleep intervals, for up to 30 seconds total. This blocks the calling thread.

**How likely is this path to be hit?**

`supportsDisplayIdInjection()` returns `setDisplayIdMethod != null`, where `setDisplayIdMethod` is a lazy reflection lookup for `InputEvent.setDisplayId(int)`. This hidden API is available on most Android 10+ devices. The shell fallback primarily targets older or restricted devices where hidden API bypass fails.

When the fallback is hit, the `ACTION_DOWN` and `ACTION_MOVE` events return `true` immediately without running any shell command (they just record coordinates). Only `ACTION_UP` triggers the shell command. So the block happens once per gesture, not per event. But it blocks the main thread for potentially up to 30 seconds (the `input tap` or `input swipe` command must complete).

**Severity assessment:** Medium is correct. The path is a real fallback for devices where hidden API access fails. Blocking the main thread for up to 30 seconds during touch input is a genuine problem -- it would freeze the UI and potentially trigger an ANR. The mitigation is that this only fires on `ACTION_UP` and only when the primary injection path is unavailable, which limits exposure. But when it does fire, it is severe.

---

## Medium #3: Invalid scroll directions normalized silently

**Review claim:** Unknown direction strings currently degrade to forward scrolling.

**Verdict: REAL BUG -- but severity is debatable**

### Evidence

`NodeActionPerformer.kt` line 393-404:
```kotlin
private fun scrollActionIds(direction: String): Pair<Int?, Int> {
    return when (direction) {
        "down" -> ... ACTION_SCROLL_DOWN / ACTION_SCROLL_FORWARD
        "up" -> ... ACTION_SCROLL_UP / ACTION_SCROLL_BACKWARD
        "left" -> ... ACTION_SCROLL_LEFT / ACTION_SCROLL_BACKWARD
        "right" -> ... ACTION_SCROLL_RIGHT / ACTION_SCROLL_FORWARD
        else -> null to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD  // <-- silent fallback
    }
}
```

The `else` branch returns `null to ACTION_SCROLL_FORWARD`. The `null` primary means it skips the directional action (since `primaryId != null` check at line 57 fails), and falls through to just `node.performAction(fallbackId)` which is `ACTION_SCROLL_FORWARD`.

So yes, any unrecognized direction string (typo, garbage, "diagonal", etc.) silently becomes a forward scroll. No log, no error.

**Is this actually harmful?**

The direction string comes from the LLM's tool call. In practice, the tool schema constrains direction to one of four values, so an invalid value would indicate a malformed tool call. The current behavior silently masks this, making debugging harder. However, the scroll still "succeeds" from the LLM's perspective -- the user sees a forward scroll they did not intend.

**Severity assessment:** Medium is slightly overstated. This is a defensive coding issue rather than a user-facing bug, since the LLM tool schema already constrains the input. A more accurate rating would be Low-Medium. The fix (fail fast or log a warning) is trivial and correct, but the practical risk of hitting this in production is low.

---

## Medium #4: Append-mode cursor placement needs proof

**Review claim:** There is a plausible concern that `setTextOnNode()` may compute cursor placement from stale node state after `ACTION_SET_TEXT`. The review explicitly says this is "not yet proven enough to treat as an accepted bug."

**Verdict: OVERSTATED -- the review itself correctly hedges, and the code is likely correct**

### Evidence

`NodeActionPerformer.kt` line 182-233, specifically the cursor placement block (lines 219-232):

```kotlin
// Place cursor after inserted text when not clearing
if (!clear) {
    val insertAt = run {
        val existing = node.text?.toString() ?: ""
        val s = node.textSelectionStart
        if (s >= 0) s.coerceAtMost(existing.length) else combined.length - text.length
    }
    val newCursor = (insertAt.coerceAtLeast(0) + text.length).coerceAtMost(combined.length)
    node.performAction(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        Bundle().apply {
            putInt(ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            putInt(ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
    )
}
```

**The key question: does `node.text` and `node.textSelectionStart` reflect pre- or post-SET_TEXT state?**

After `ACTION_SET_TEXT` is performed at line 213, `node` is a cached `AccessibilityNodeInfo` object. Android accessibility nodes are snapshots -- they do NOT auto-update after performing actions on them. The node's `text` and `textSelectionStart` properties reflect the state at the time the node was obtained (before SET_TEXT), unless `node.refresh()` is called.

However, looking at the logic carefully:

- `combined` is the full text string that was just set via SET_TEXT (computed at lines 196-203).
- The cursor code at line 220-223 reads `node.text` and `node.textSelectionStart` again, which are the PRE-SET_TEXT values.
- If `textSelectionStart` is valid (>= 0), it uses the PRE-SET_TEXT selection start position. This is actually the correct insertion point, because the code at line 201 already used `selStart` as the insertion point.
- The fallback `combined.length - text.length` calculates the same insertion point (end of pre-existing text).
- `newCursor = insertAt + text.length` places the cursor after the newly inserted text.

**So the pre-action state IS the correct state to use for calculating where the text was inserted.** The code is computing: "I inserted `text` at position `insertAt` (which I know from the pre-action state), so the cursor should go to `insertAt + text.length`." This is correct logic even without refreshing the node.

The one edge case: if `ACTION_SET_TEXT` internally moves the cursor (which some implementations do), then `textSelectionStart` on the REFRESHED node might differ. But the code does not care about the post-action cursor position -- it is SETTING the cursor, not reading it.

**Severity assessment:** The review is right to hedge this as "needs proof." The current code appears correct because it uses pre-action state intentionally to compute the cursor position. The cursor placement logic is sound. This should be downgraded from Medium to Non-Finding with a note that a regression test would be valuable but the logic is not buggy.

---

## Low #1: Dead private helpers

**Review claim:** `AccessibilityGestureInjector.gestureDisplayId()` and `NodeActionPerformer.performNodeActionAt()` are unused dead code.

**Verdict: REAL -- both are genuinely dead code**

### Evidence

**`gestureDisplayId()`** -- `AccessibilityGestureInjector.kt` line 153-157:
```kotlin
private fun gestureDisplayId(gesture: GestureDescription?): Int {
    if (gesture == null) return Display.DEFAULT_DISPLAY
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
    return gesture.displayId
}
```

Grep confirms the only reference is the declaration itself. No caller anywhere in the codebase. This is a private function -- it cannot be called from outside the class.

**`performNodeActionAt()`** -- `NodeActionPerformer.kt` line 251-273:
```kotlin
private suspend fun performNodeActionAt(
    nodeFinder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    notFoundMessage: String,
    action: Int,
    successMessage: String,
    failureMessage: String
): ActionResult { ... }
```

Grep confirms the only reference is the declaration itself. This is also a private function. It appears to be an earlier generic version of the action methods that was superseded by the specific `performNodeClickAt`, `performScrollAt`, etc.

**Severity assessment:** Low is correct. Dead code, no functional impact, straightforward cleanup.

---

## Low #2: DISPLAY_FLAGS documentation

**Review claim:** The raw bitmask in `VirtualDisplayPlatform` is opaque and should be documented inline.

**Verdict: REAL -- the constant is genuinely undocumented**

### Evidence

`VirtualDisplayPlatform.kt` line 46:
```kotlin
private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800
```

No comment. No reference to Android constant names. The hex values correspond to:
- `0x1` = `VIRTUAL_DISPLAY_FLAG_PUBLIC` (1)
- `0x8` = `VIRTUAL_DISPLAY_FLAG_SECURE` (8)
- `0x40` = `VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH` (64)
- `0x200` = `VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` (512)
- `0x400` = `VIRTUAL_DISPLAY_FLAG_TRUSTED` (1024) -- hidden API
- `0x800` = `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` (2048) -- hidden API, API 31+

There is no grep match for these constant names anywhere in the codebase. The bitmask uses hidden/undocumented flags that cannot be referenced by name without reflection, which explains the raw hex. But an inline comment explaining each flag would be valuable.

**Severity assessment:** Low is correct. Documentation-only issue, no behavioral impact.

---

## Missed Issues: Findings Not Called Out in the Review

### Missed Issue #1: `getCurrentPackageName()` leaks AccessibilityNodeInfo on success path (AccessibilityPlatform)

`AccessibilityPlatform.kt` line 341-347:
```kotlin
override fun getCurrentPackageName(): String? {
    return try {
        service.rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get package name", e)
        null
    }
}
```

The `rootInActiveWindow` node is obtained but never recycled. This is called on every `captureScreen()` invocation (lines 63, 66, 75), meaning every turn leaks one `AccessibilityNodeInfo`. The VD path (`VirtualDisplayPlatform.getCurrentPackageName()` lines 370-376) correctly recycles the root in a `finally` block.

**Severity: Medium.** Repeated binder object leaks on a hot path. Over a long session with many turns, this adds up. The fix is trivial -- match the VD pattern with `try/finally { root.recycleCompat() }`.

### Missed Issue #2: `VirtualDisplayAppController.launchApp` intent fallback reports success when launch may throw silently

`VirtualDisplayAppController.kt` lines 77-80:
```kotlin
shizuku.launchOnDisplay(service, launchIntent, displayId)
ActionResult.Success("Launched $packageName on display $displayId (intent)")
```

`ShizukuActivityLauncher.launchOnDisplay` (line 13-29) catches ALL exceptions and only logs them:
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Failed to launch on display $displayId", e)
}
```

It does not rethrow, so the caller unconditionally reports success. This is exactly the "reports success when may have failed" pattern noted in review High #7, but the review describes it vaguely. The concrete evidence is here: `ShizukuActivityLauncher` swallows the exception, and `VirtualDisplayAppController` blindly returns `ActionResult.Success`. Note: the outer `try/catch` at line 81 in `VirtualDisplayAppController` would only catch an exception FROM `launchOnDisplay` if it threw one, which it never does.

The review calls this out as High #7 but attributes it to `VirtualDisplayAppController` reporting success "after `launchOnDisplay(...)`", which is correct. This is already covered, though the evidence chain is worth making explicit.

### Missed Issue #3: `collectRootsOnActiveDisplay` window recycle race in AccessibilityPlatform

`AccessibilityPlatform.kt` line 268-269:
```kotlin
.sortedBy { it.layer }
.mapNotNull { it.root }
```

Then in the `finally` block at line 274-278:
```kotlin
windows.forEach { window ->
    runCatching { window.recycle() }
}
```

The problem: `it.root` on line 269 obtains a new `AccessibilityNodeInfo` from the window. But `window.recycle()` is called in `finally`, which may invalidate the window object before the root reference is fully resolved if there is any interleaving. In practice, since this runs on a single thread, there is no actual race -- but the pattern of recycling windows while roots obtained from them are still in use is fragile. The VD `VirtualDisplayWindowAccessor` has the same pattern (lines 70-77, 86-99). This is more of a fragility concern than a concrete bug.

---

## Summary Table

| Finding | Review Severity | Verdict | Adjusted Severity |
|---------|----------------|---------|-------------------|
| Medium #1: Live-preview surface replacement | Medium | **REAL BUG** | Medium (confirmed) |
| Medium #2: Shell fallback blocks caller | Medium | **REAL BUG** | Medium (confirmed) |
| Medium #3: Invalid scroll direction fallback | Medium | **REAL BUG** | Low-Medium (slightly overstated) |
| Medium #4: Append-mode cursor placement | Medium (hedged) | **OVERSTATED** | Non-Finding (logic is correct) |
| Low #1: Dead private helpers | Low | **REAL** | Low (confirmed) |
| Low #2: DISPLAY_FLAGS documentation | Low | **REAL** | Low (confirmed) |
| Missed: A11y rootInActiveWindow leak | -- | **REAL BUG** | Medium |

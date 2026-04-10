# Audit: Critical and High Findings Verification

Independent code audit against the review at `review.md`.
Each finding verified by reading the cited source files and tracing execution paths.

---

## Critical #1: Callback-driven capture paths are unbounded

**Review claim**: `AccessibilityScreenshotCapturer` waits on `takeScreenshot`/`takeScreenshotOfWindow` without a timeout. `VirtualDisplayCaptureCoordinator` does the same for `PixelCopy.request`. A lost framework callback can wedge `captureScreen()` forever.

**Verdict: REAL BUG**

### Evidence

**AccessibilityScreenshotCapturer.kt lines 60-88 and 90-121:**
Both `takeDisplayScreenshot()` and `takeWindowScreenshot()` use `suspendCancellableCoroutine` with no timeout. The continuation resumes only when the framework calls `onSuccess` or `onFailure`. There is no `withTimeout` wrapper here, and no `withTimeout` at any call site (`captureAccessibilityTree` at `AccessibilityPlatform.kt:93`, `TurnExecutionPhaseRunner.kt:215`, `AgentTurnRunner.kt:130`, `PostActionAnalysis.kt:86`). If the accessibility service dies or the framework never calls back, the coroutine suspends indefinitely.

The code does check `continuation.isActive` before resuming, which is good for cancellation safety, but that only protects against double-resume -- it does not bound the wait.

**VirtualDisplayCaptureCoordinator.kt lines 169-176:**
`captureFromPixelCopy()` uses `suspendCancellableCoroutine` for `PixelCopy.request` with no timeout. Additionally, the resume call (`cont.resume(copyResult)`) does not check `cont.isActive`, which means if the coroutine is cancelled while PixelCopy is in flight, the callback will call `resume` on a cancelled continuation and throw `IllegalStateException`. This is worse than the accessibility path -- it is both unbounded AND not cancellation-safe.

**Mitigating factor**: `PixelCopy.request` and `AccessibilityService.takeScreenshot` are framework APIs that, in practice, nearly always call back. But "nearly always" is not "always." Service death, surface teardown during copy, or binder death can prevent callback delivery.

**Conclusion**: The finding is accurate. No timeout exists at any level. Both paths can hang indefinitely under framework failure conditions. The PixelCopy path additionally has a cancellation bug (missing `isActive` check).

---

## Critical #2: Virtual-display gestures are not cancellation-safe

**Review claim**: `VirtualDisplayInputInjector` injects DOWN, suspends (via `delay`), then injects UP. If the coroutine is cancelled mid-gesture, no cleanup event is sent. The target app can be left in a stuck pressed/dragging state.

**Verdict: REAL BUG**

### Evidence

**VirtualDisplayInputInjector.kt lines 56-89 (`injectLongPress`):**
```kotlin
val down = motionEvent(...)
if (!shizuku.injectInputEvent(down)) { ... return Failure }
down.recycle()

delay(durationMs)  // <-- SUSPENSION POINT: cancellation happens here

val up = motionEvent(...)
val ok = shizuku.injectInputEvent(up)
```

If the coroutine is cancelled during `delay(durationMs)`, the `CancellationException` propagates immediately. No `finally` block exists. No `ACTION_UP` or `ACTION_CANCEL` is sent. The target app sees `ACTION_DOWN` but never `ACTION_UP`, leaving it in a pressed state.

**VirtualDisplayInputInjector.kt lines 92-145 (`injectSwipe`):**
Same pattern. `ACTION_DOWN` is injected, then a loop of `delay(stepMs)` + `ACTION_MOVE`, then `ACTION_UP`. Cancellation during any `delay` leaves the gesture incomplete. The review also notes that `MOVE` injection failures are silently swallowed (line 123: `shizuku.injectInputEvent(move)` return value is ignored), which means the gesture can be broken before it finishes.

There is no `try/finally` in either method. There is no `invokeOnCancellation` handler.

**Mitigating factor**: In practice, coroutine cancellation during agent gestures may be rare since the agent loop typically waits for actions to complete. But session stop/timeout can cancel the coroutine scope.

**Conclusion**: The finding is accurate. Both `injectLongPress` and `injectSwipe` can leave the target app in a stuck touch state on cancellation. The swipe path also ignores MOVE failures.

---

## High #1: VD stack has no single lifecycle owner

**Review claim**: `VirtualDisplayPlatform` has no serialized lifecycle owner. `stop()` can race with `captureScreen()` or `performAction()`. Surface switching can race with screenshot capture.

**Verdict: REAL BUG, but with an important qualification**

### Evidence

**VirtualDisplayPlatform.kt concurrency controls:**
- `displayId` and `imageReader` are `@Volatile` (lines 52-53)
- No `Mutex`, `ReentrantLock`, or `synchronized` blocks exist in `VirtualDisplayPlatform` itself
- `VirtualDisplaySurfaceController` has a `synchronized(stateLock)` for surface mode switching (its own internal state), but this does not coordinate with capture or action paths in `VirtualDisplayPlatform`

**The race scenario**:
1. `captureScreen()` reads `imageReader` (non-null, valid) and enters `captureFromImageReader()`
2. Concurrently, `stop()` calls `imageReader?.close()` and sets `imageReader = null`
3. `captureFromImageReader()` calls `reader.acquireLatestImage()` on a closed `ImageReader`

Similarly:
1. `captureFromPixelCopy()` checks `surfaceController.liveSurfaceView()` is valid
2. `switchToImageReader()` is called from another thread, switching the surface
3. `PixelCopy.request(sv, ...)` executes on a surface that is no longer the display target

**Qualification**: The `stop()` comment on line 148-149 explicitly states: "Not safe to call concurrently with captureScreen/performAction." This is a documented constraint, not an accidental omission. The code expects the caller to serialize `stop()` after the agent loop exits. If the caller obeys this contract, the race does not occur.

However, the review's broader point stands: there is no enforcement mechanism. The binder death listener (line 134) can fire asynchronously on any thread and currently only logs. If binder death is ever expanded to do cleanup (as it should be), that cleanup will race with ongoing capture/action work. The `switchToLivePreview`/`switchToImageReader` paths can be called from the UI thread while capture runs on a coroutine, and the `surfaceController` lock only protects mode switching, not the capture path that reads the mode and surface view outside that lock.

**Conclusion**: The finding is real for the surface-switching race (no coordination between surface switch and capture), and for the binder-death expansion case. The stop-vs-capture race is documented as a caller responsibility, so calling it "unhandled" overstates the current risk slightly, but the lack of enforcement is still a design weakness.

---

## High #2: Shizuku binder death not handled

**Review claim**: The binder-death listener only logs. `ShizukuClient.clearCachedProxies()` exists but is never called during binder death or stop. Dead binder wrappers survive.

**Verdict: REAL BUG (binder death), PARTIALLY TRUE (stop)**

### Evidence

**VirtualDisplayPlatform.kt lines 133-136 (binder death listener):**
```kotlin
binderDeadListener = Shizuku.OnBinderDeadListener {
    Log.e(TAG, "Shizuku binder died! displayId=$displayId")
}
```

This is indeed a pure log statement. On binder death:
- No state transition occurs (`displayId` stays valid, `imageReader` stays open)
- `clearCachedProxies()` is not called
- Subsequent Shizuku calls will fail deep in reflection/transport code with opaque errors
- The platform cannot distinguish "Shizuku died" from "random action failure"

**ShizukuClient.kt lines 151-155 (`clearCachedProxies`):**
```kotlin
fun clearCachedProxies() {
    proxyProvider.clear()
    displayTransport.clear()
    Log.d(TAG, "Cleared cached binder proxies")
}
```

This method exists and works, but is never called. Not in `stop()`, not in the binder death listener, nowhere in the codebase.

**VirtualDisplayPlatform.stop() lines 151-168:**
`stop()` removes the binder death listener, resets the surface controller, releases the virtual display, and closes the ImageReader. But it does NOT call `shizuku.clearCachedProxies()`. So stale proxy objects survive after stop.

**Conclusion**: The finding is accurate. The binder-death listener is effectively a no-op. `clearCachedProxies()` is dead code that is never called anywhere. Stale proxies can survive both binder death and normal stop.

---

## High #3: Window selection wrong under multi-window

**Review claim**: The accessibility path sorts windows by ascending layer, biasing toward the lowest (bottom-most) window for `takeScreenshotOfWindow`. The VD path picks an application window without explicit layer ordering.

**Verdict: REAL BUG for screenshot targeting; OVERSTATED for tree capture**

### Evidence

**AccessibilityPlatform.kt lines 263-268 (`collectRootsOnActiveDisplay`):**
```kotlin
val roots = windows
    .filter { ... }
    .sortedBy { it.layer }
    .mapNotNull { it.root }
```

`AccessibilityWindowInfo.getLayer()` returns the Z-order: **higher layer = closer to user (on top)**. `sortedBy { it.layer }` sorts ascending, so lowest-layer (bottom-most) windows come first.

Note that `service.windows` already returns windows in descending layer order (top first), per [Android documentation](https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.windows). The `sortedBy` is redundant with the default order but reversed -- it actively reorders from top-first to bottom-first.

**AccessibilityPlatform.kt line 156:**
```kotlin
val windowId = roots.firstOrNull()?.windowId
```

This picks the `windowId` of the first root in the sorted list, which is the bottom-most window. This `windowId` is then passed to `takeScreenshotOfWindow` at line 93. So the screenshot targets the background window, not the foreground dialog/popup.

**For tree capture**: All roots are passed to `Perceptor.snapshot()`, which traverses them all with dedup. So the tree contains elements from all windows regardless of ordering. The sort order affects deterministic ordering but not completeness. This is correct behavior.

**For screenshot targeting**: The windowId selection is wrong. When a dialog appears over the main app, the dialog window has a higher layer. `sortedBy { it.layer }` puts it last. `roots.firstOrNull()?.windowId` picks the main app window, and the screenshot shows the app behind the dialog, not the dialog itself. The fallback to `takeDisplayScreenshot()` (full display) would be correct, but the code tries window-specific screenshot first.

**VirtualDisplayWindowAccessor.kt lines 64-77 (`getRootOnDisplay`):**
```kotlin
val appWindow =
    windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        ?: windows.firstOrNull()
```

This picks the first TYPE_APPLICATION window without layer sorting. Since `getWindowsOnDisplay()` returns windows in the order provided by the framework (descending layer from `service.windows`, or framework-order from `getWindowsOnAllDisplays`), the first APPLICATION window is likely the topmost app window. However, the `getRootsOnDisplay()` method (line 85-101) that feeds tree capture does sort by ascending layer, same as the accessibility path.

**Conclusion**: The screenshot windowId targeting in `AccessibilityPlatform` is a real bug -- it selects the bottom-most window's ID for `takeScreenshotOfWindow`. The tree capture is correct (all windows are included). The VD path's `getRootOnDisplay()` does not have the same issue since it uses type-based selection rather than layer-based ordering for its single-root accessor. The VD `getRootsOnDisplay()` has the same ascending sort but that only affects tree traversal order, not screenshot targeting (VD screenshots use ImageReader/PixelCopy of the whole display, not window-specific capture).

---

## Summary

| Finding | Verdict | Severity Assessment |
|---------|---------|-------------------|
| Critical #1: Unbounded callbacks | **REAL BUG** | Correctly rated Critical. PixelCopy path also has cancellation bug. |
| Critical #2: Gesture cancellation unsafe | **REAL BUG** | Correctly rated Critical. Both longPress and swipe affected. |
| High #1: No lifecycle owner | **REAL BUG** | Slightly overstated -- stop/capture race is documented as caller constraint. Surface-switch race and binder-death expansion risk are real. High is fair. |
| High #2: Binder death not handled | **REAL BUG** | Accurately described. `clearCachedProxies()` is dead code. High is fair. |
| High #3: Window selection wrong | **REAL BUG for screenshot; OVERSTATED for tree** | Screenshot windowId selection is genuinely wrong (picks bottom window). Tree capture includes all windows so ordering is cosmetic. VD path unaffected for screenshots. High is fair for the screenshot impact. |

All five findings are substantively correct. No false claims were found. Two findings have minor qualifications (High #1's documented caller constraint, High #3's tree-vs-screenshot distinction), but neither changes the overall severity rating.

# Platform Module Robustness Review

Scope: 28 files in `app/src/main/kotlin/com/moonkey/androidagent/platform/`

---

## Perspective A: Reliability & Edge Cases

### A1. VirtualDisplayPlatform.stop() does not clear cached binder proxies

**File:** `VirtualDisplayPlatform.kt` line 150-168
**Scenario:** `stop()` releases the virtual display and ImageReader but never calls `shizuku.clearCachedProxies()`. If the platform is stopped and re-started (or if the Shizuku binder dies and reconnects), the `ShizukuServiceProxyProvider` still holds stale proxy objects that point at dead binders. The next `createVirtualDisplay()` or `injectInputEvent()` call will throw a `DeadObjectException`.
**Evidence:** `ShizukuClient.clearCachedProxies()` exists specifically for this case but is never called.
**Severity:** HIGH -- causes hard crash on Shizuku restart.

### A2. Shizuku binder death listener is a no-op

**File:** `VirtualDisplayPlatform.kt` lines 132-135
**Scenario:** When Shizuku dies mid-session, the `OnBinderDeadListener` only logs. No recovery, no state cleanup, no signal to the session. Every subsequent `performAction()` and `captureScreen()` call will fail silently or throw, producing confusing error messages. The agent loop doesn't know the platform is dead.
**Evidence:** The listener body is `Log.e(TAG, "Shizuku binder died! displayId=$displayId")` and nothing else.
**Severity:** MEDIUM -- degrades gracefully only by accident (individual calls return Failure), but the agent will keep retrying indefinitely without understanding why.

### A3. VirtualDisplayPlatform.start() creates ImageReader before display -- orphaned resource on failure

**File:** `VirtualDisplayPlatform.kt` lines 106-127
**Scenario:** `ImageReader.newInstance()` succeeds, but `createVirtualDisplay()` returns -1. The code correctly does `reader.close()` and throws. No issue there. However, `createVirtualDisplay()` could throw an exception (vs. returning -1), and in that case the ImageReader leaks because there is no try/finally around the pair.
**Evidence:** `createVirtualDisplay` calls deep into reflection (`ShizukuDisplayTransport`) which can throw `InvocationTargetException`, `NoSuchMethodException`, etc.
**Severity:** LOW -- only happens on startup failure, and the process likely dies anyway. But the fix is trivial.

### A4. VirtualDisplayCaptureCoordinator.captureFromImageReader() -- bitmap leak on `copyPixelsFromBuffer` failure

**File:** `VirtualDisplayCaptureCoordinator.kt` lines 126-148
**Scenario:** `Bitmap.createBitmap(...)` at line 127 succeeds. `bitmap.copyPixelsFromBuffer(plane.buffer)` at line 132 can throw if the buffer is in a bad state. The bitmap is never recycled in that case because the `try/finally` only closes the `image`, not the bitmap.
**Evidence:** `plane.buffer` can be invalidated if the ImageReader is closed concurrently.
**Severity:** LOW -- transient bitmap leak, GC will recover. But adds to memory pressure during already-stressed conditions.

### A5. IME suppression race in VirtualDisplayPlatform.performAction()

**File:** `VirtualDisplayPlatform.kt` lines 269-282
**Scenario:** `performAction()` sets keyboard hidden before the action and restores to auto after. If two actions execute concurrently (the interface is `suspend`, callers could launch them in parallel), the second action's `setKeyboardAuto()` could restore the keyboard while the first action is still in progress. Also, if `dispatchAction()` is cancelled via coroutine cancellation, the `finally` block runs `setKeyboardAuto()` correctly -- that part is fine.
**Evidence:** No mutex or synchronization on the suppress/restore pair.
**Severity:** LOW -- in practice the agent loop is sequential, so this is theoretical. But the code doesn't enforce single-action semantics.

### A6. AccessibilityNodeFinder.findNodeAtLocation() recycles parent when child subtree matches

**File:** `AccessibilityNodeFinder.kt` lines 165-203
**Scenario:** In the `search()` function, when a child subtree returns a match, lines 183-185 recycle the current `node` if `shouldRecycle` is true. This is correct. However, the DFS visits children in reverse order (line 176), and if the first matching child is found, all previously visited (non-matching) children were already recycled inside `search()`. This is actually fine -- the comment at line 188 confirms it. No bug here.
**Note:** Reviewed carefully; the recycle pattern is correct throughout this file.

### A7. ShizukuShellExecutor reads errorStream after waitForProcess returns true

**File:** `ShizukuShellExecutor.kt` lines 13-36
**Scenario:** When `waitForProcess()` returns `true` with a non-zero exit code, the code reads `process.errorStream`. This works. But when the process was killed by `process.destroy()` (timeout case, line 17), the method returns -1 without reading or closing the streams. Since `Process.destroy()` on Android closes the underlying file descriptors, this is fine for the JVM but not explicitly documented.
**Evidence:** Standard Java `Process` contract handles this.
**Severity:** NONE -- no actual bug.

### A8. VirtualDisplayConfig.fromPhysicalDisplay uses content area metrics, not real metrics

**File:** `VirtualDisplayConfig.kt` lines 32-40
**Scenario:** `context.resources.displayMetrics` returns the content area dimensions (excluding nav bar, cutout). This means the virtual display will be slightly smaller than the physical display. `AccessibilityPlatform.getDisplayInfo()` explicitly uses `wm.maximumWindowMetrics.bounds` for the real dimensions. The virtual display is internally consistent (it creates the display at those dimensions and uses those dimensions for bounds checking), so this is not a correctness bug. But apps may render with different insets than on the real screen.
**Evidence:** Compare `AccessibilityPlatform.getDisplayInfo()` (lines 338-358) vs `VirtualDisplayConfig.fromPhysicalDisplay()`.
**Severity:** LOW -- functional difference, not a bug. Apps may have slightly different layout on VD.

### A9. NodeActionPerformer.setTextOnNode() cursor positioning reads stale node state

**File:** `NodeActionPerformer.kt` lines 219-233
**Scenario:** After `ACTION_SET_TEXT` succeeds (line 213), the code tries to set cursor position. At line 221, it calls `node.text` and `node.textSelectionStart` to compute the new cursor position. But `ACTION_SET_TEXT` may not have updated the node's cached properties yet (AccessibilityNodeInfo is a snapshot). The `insertAt` computation may use the pre-SET_TEXT values, leading to incorrect cursor placement.
**Evidence:** Lines 220-223 read `node.text` and `node.textSelectionStart` without calling `node.refresh()` first. The later verification block (line 237) does call `node.refresh()`, but that's too late for cursor positioning.
**Severity:** MEDIUM -- causes incorrect cursor placement when appending text (not clearing). The LLM may produce garbled text in append mode.

### A10. AccessibilityPlatform.collectRootsOnActiveDisplay() -- window objects recycled in finally, but roots extracted first

**File:** `AccessibilityPlatform.kt` lines 240-259
**Scenario:** `roots` is obtained via `mapNotNull { it.root }` at line 248, then `windows` are recycled in `finally`. Recycling a window does NOT invalidate its root node -- they are independent objects. This is correct.
**Severity:** NONE -- verified correct.

### A11. OverlayTouchGate interface has no timeout protection

**File:** `OverlayTouchGate.kt`
**Scenario:** If `beginGesturePassThrough()` is called but the `AutoCloseable.close()` is never called (e.g., due to an unhandled exception between `beginGesturePassThrough` and the gesture dispatch), the overlay stays in FLAG_NOT_TOUCHABLE forever. The user cannot interact with the overlay.
**Evidence:** `AccessibilityGestureInjector.dispatchGesture()` wraps this in `try/finally` (lines 113-146), so the close always happens. But the interface itself has no self-healing.
**Severity:** NONE -- the caller (AccessibilityGestureInjector) correctly handles this with try/finally.

### A12. VirtualDisplayInputInjector.injectSwipe() doesn't recycle MotionEvent on MOVE inject failure

**File:** `VirtualDisplayInputInjector.kt` lines 116-125
**Scenario:** In the swipe loop, if `shizuku.injectInputEvent(move)` fails, the code continues the loop and recycles the move event. Fine. But the DOWN event was already injected, and if the MOVE or UP fails, the virtual display has a "stuck" touch-down with no up event. The system will eventually time out the touch, but it can cause UI glitches.
**Evidence:** The swipe function doesn't bail out early on MOVE failure (line 123 ignores the return value).
**Severity:** LOW -- transient, self-healing via system touch timeout.

### A13. VirtualDisplayViewerTouchHandler -- mutable state without synchronization

**File:** `VirtualDisplayViewerTouchHandler.kt` lines 23-26
**Scenario:** `viewerDownX`, `viewerDownY`, `viewerDownTime`, `viewerMoved` are plain vars without any synchronization. `onViewerTouch()` is called from the UI thread (touch events), so this is fine as long as only one thread calls it.
**Evidence:** Touch dispatch is single-threaded (main thread).
**Severity:** NONE -- correct for the expected calling context.

---

## Perspective B: Design Cleanliness

### B1. Duplication between AccessibilityPlatform and VirtualDisplayPlatform screen capture logic

**Observation:** Both platforms implement their own `captureAccessibilityTree()` / `captureA11yTreeWithArtifacts()` methods that are structurally identical: get roots, dump raw tree, run Perceptor.snapshot(), dump sanitized tree, return result. The data classes `A11yCaptureResult` / `CaptureQuality` are defined separately in each.
**Counterpoint:** The accessibility platform has retry logic (3 attempts), keyboard detection, and bounds diagnostics that the VD platform doesn't need. The VD platform catches exceptions from `Perceptor.snapshot()` while the accessibility platform doesn't.
**Verdict:** The differences are real but the shared structure is significant. A shared capture pipeline with hooks for platform-specific behavior would reduce the surface area for bugs.

### B2. ShizukuClient is a pure pass-through facade

**Observation:** `ShizukuClient` delegates every method 1:1 to one of its five internal objects (`runtimeGateway`, `shellExecutor`, `proxyProvider`, `displayTransport`, `inputTransport`, `activityLauncher`). It adds zero logic. Callers could depend on the internal classes directly.
**Counterpoint:** The facade provides a single injection point and hides the internal decomposition. Callers (VirtualDisplayPlatform, VirtualDisplayAppController, etc.) only need to know one type. This is actually clean design -- the facade is the API boundary.
**Verdict:** Keep as-is. The facade earns its existence by simplifying the dependency graph.

### B3. VirtualDisplayPlatform has too many lambda-based providers

**Observation:** `displayIdProvider`, `imageReaderProvider` are `() -> Int` and `() -> ImageReader?` lambdas passed to 6+ sub-objects. This creates implicit coupling and makes the data flow hard to trace.
**Counterpoint:** The lambdas avoid passing `VirtualDisplayPlatform` (this) to sub-objects, preventing circular references and keeping sub-objects testable.
**Verdict:** The lambdas are the right call for avoiding circular deps. No change needed.

### B4. PlatformFactory doesn't pass visualizer/overlayTouchGate to VirtualDisplayPlatform

**File:** `PlatformFactory.kt` lines 59-89
**Observation:** When creating `VirtualDisplayPlatform`, the factory doesn't pass `visualizer` or `overlayTouchGate`. VD mode doesn't need the overlay touch gate (it injects directly), and doesn't use the visualizer (it uses input injection, not gesture dispatch). This is intentionally asymmetric.
**Verdict:** Correct by design.

### B5. AccessibilityGestureInjector.gestureDisplayId() is unused

**File:** `AccessibilityGestureInjector.kt` lines 153-157
**Observation:** `gestureDisplayId()` is a private method that's never called anywhere in the file.
**Evidence:** No references to `gestureDisplayId` in the file.
**Severity:** LOW -- dead code.

### B6. NodeActionPerformer.performNodeActionAt() is unused

**File:** `NodeActionPerformer.kt` lines 251-273
**Observation:** The generic `performNodeActionAt()` method is private and never called. Each action (click, scroll, setText, etc.) has its own specific method. This was likely an early abstraction that was superseded.
**Evidence:** The method is `private` and has no callers within the file.
**Severity:** LOW -- dead code.

### B7. VirtualDisplayPlatform DISPLAY_FLAGS magic number

**File:** `VirtualDisplayPlatform.kt` line 45
**Observation:** `DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800` is opaque. These are `DisplayManager.VIRTUAL_DISPLAY_FLAG_*` constants, but using raw hex makes the intent unclear.
**Verdict:** Minor readability issue. Adding a comment documenting each flag would help.

### B8. Architecture is well-decomposed

**Observation:** The module follows a clear pattern:
- `AndroidPlatform` interface defines the contract
- `AccessibilityPlatform` and `VirtualDisplayPlatform` are two clean implementations
- Shared logic (`NodeActionPerformer`, `BitmapUtils`, `AppManager`) is extracted
- VD internals are decomposed into single-responsibility classes
- `PlatformFactory` is the single decision point

The decomposition is solid. Each VD sub-class (transport, surface controller, capture coordinator, etc.) has a clear single responsibility. The accessibility side is more monolithic but the code is simpler so it doesn't need the same decomposition.

---

## Synthesis

### Critical findings (should fix):

1. **A1 -- Stale binder proxies after stop():** `VirtualDisplayPlatform.stop()` must call `shizuku.clearCachedProxies()`. One-line fix.

2. **A9 -- Stale cursor position after setText:** Call `node.refresh()` before computing cursor position in `setTextOnNode()`. Small targeted fix.

### Important findings (should fix when touching related code):

3. **A2 -- Binder death is silent:** The binder death listener should set `displayId = Display.INVALID_DISPLAY` so subsequent calls fail fast with clear messages instead of throwing DeadObjectException.

4. **A3 -- ImageReader leak on createVirtualDisplay exception:** Wrap the ImageReader creation and display creation in a try/catch that closes the reader on any exception.

5. **B5/B6 -- Dead code:** Remove `gestureDisplayId()` and `performNodeActionAt()`.

### Low-priority observations:

6. **A4 -- Bitmap leak in captureFromImageReader:** Wrap bitmap creation in its own try/catch inside the existing try block.

7. **A12 -- Swipe stuck touch:** Log MOVE injection failures and consider aborting the swipe with an UP event.

8. **B1 -- A11y capture duplication:** Consider extracting shared capture logic. Low priority since each platform has meaningful differences.

9. **B7 -- Magic display flags:** Add a comment.

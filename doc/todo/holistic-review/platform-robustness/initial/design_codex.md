# Platform Robustness Review (Codex)

## Scope
Reviewed all 28 files under:

- `app/src/main/kotlin/com/moonkey/androidagent/platform/`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`

Focus areas:

- accessibility-service death and stale node/window handling
- rotation and display-size churn
- app launch failure paths
- resource ownership and cleanup
- race conditions and bounded failure behavior

No other `doc/todo` design files were read for this review.

## Summary
The module is decomposed well enough to reason about, but robustness is uneven. The accessibility path is mostly optimistic about framework callbacks and tree validity. The virtual-display path is more modular, but it still relies on shared mutable state (`displayId`, `imageReader`, surface mode, Shizuku binder state) without a single lifecycle owner.

In the happy path this likely works. Under edge conditions called out in the review prompt, it can hang, leak resources, target the wrong window, or enter a zombie state that the current session cannot recover from cleanly.

## Critical
1. Screenshot capture can block forever because callback-based waits are unbounded.
   Evidence: `AccessibilityScreenshotCapturer.kt:60-121` waits on `takeScreenshot` / `takeScreenshotOfWindow` with `suspendCancellableCoroutine` and no timeout. `VirtualDisplayCaptureCoordinator.kt:167-177` does the same for `PixelCopy.request`.
   Why it matters: if the accessibility service dies mid-call, the platform callback is lost, or the SurfaceView is torn down in an unlucky state, `captureScreen()` can suspend indefinitely and wedge the agent turn.
   Extra risk: `VirtualDisplayCaptureCoordinator.kt:169-175` resumes the continuation without checking `cont.isActive`, so cancellation can turn into a late-resume crash.
   Fix direction: move all callback waits behind one bounded helper with timeout, cancellation-safe resume checks, and explicit fallback behavior.

2. Virtual-display gestures are not cancellation-safe and can leave input state stuck.
   Evidence: `VirtualDisplayInputInjector.kt:56-89` injects DOWN, suspends with `delay`, then injects UP for long-press. `VirtualDisplayInputInjector.kt:92-145` does the same for swipe across multiple delays. Neither path has a `try/finally` that sends `ACTION_CANCEL` or best-effort `ACTION_UP` when the coroutine is cancelled.
   Why it matters: session stop, tool cancellation, or timeout during a gesture can leave the target app thinking a finger is still down. That is exactly the kind of "platform got wedged after interruption" failure that is hard to recover from automatically.
   Extra risk: `VirtualDisplayInputInjector.kt:116-125` ignores failed MOVE injections and still reports success if the final UP succeeds.
   Fix direction: track whether DOWN was delivered, treat any mid-gesture injection failure as fatal, and send a best-effort cancel in `finally`.

## High
1. The virtual-display lifecycle has no single owner and no serialization boundary.
   Evidence: `VirtualDisplayPlatform.kt:51-57` stores live session state in volatiles. `VirtualDisplayPlatform.kt:101-168`, `176-192`, `204-341` exposes `start`, `stop`, `switchToLivePreview`, `switchToImageReader`, `captureScreen`, `performAction`, and `onViewerTouch` without a shared mutex or state machine. `VirtualDisplayCaptureCoordinator.kt:99-196` and `VirtualDisplayInputInjector.kt:29-241` read that mutable state indirectly while work is in flight.
   Why it matters: `stop()` can invalidate `displayId` or close the `ImageReader` while capture/action code is still using them. Surface switches can race with screenshot reads. The code comments already admit `stop()` is "not safe to call concurrently"; the implementation does not enforce that contract.
   Fix direction: centralize lifecycle transitions behind one serialized owner with explicit states such as `Stopped`, `Starting`, `Running`, `Stopping`, and `Broken`.

2. Shizuku binder death is only logged; the platform never transitions to a recoverable or failed-closed state.
   Evidence: `VirtualDisplayPlatform.kt:132-136` registers a binder-death listener that only logs. `VirtualDisplayPlatform.kt:150-166` does not clear Shizuku proxies during stop. `ShizukuClient.kt:151-155` exposes `clearCachedProxies()`, but it is never called. `ShizukuServiceProxyProvider.kt:8-37` caches display/input proxies indefinitely.
   Why it matters: after binder death or Shizuku restart, the platform can keep dead binder wrappers and a stale `displayId`, so future calls fail in ad hoc ways instead of triggering deterministic teardown/recovery.
   Fix direction: on binder death, mark the platform broken, clear cached proxies, release local state, and force callers to recreate the session or reinitialize the platform.

3. Window selection is inconsistent and wrong under multi-window, popup, and dialog conditions.
   Evidence: `AccessibilityPlatform.kt:242-249` sorts windows by layer ascending, then `AccessibilityPlatform.kt:135` uses `roots.firstOrNull()?.windowId` for `takeScreenshotOfWindow`. That chooses the lowest-layer window, not the topmost one. `VirtualDisplayWindowAccessor.kt:64-70` chooses the first application window without sorting by layer at all.
   Why it matters: screenshots can be taken from the wrong window, and node actions can search the wrong root. When a dialog, popup, or split window is present, the tree and screenshot can diverge or actions can target the background window.
   Fix direction: make window selection explicit. Use highest-layer eligible window for single-window operations, or fall back to full-display capture when multiple relevant windows are present.

4. Rotation and display-size changes are not handled after virtual-display startup.
   Evidence: `VirtualDisplayConfig.kt:32-39` snapshots size from `context.resources.displayMetrics` once. `VirtualDisplayPlatform.kt:101-143` creates the `ImageReader` and virtual display once at startup. `VirtualDisplayPlatform.kt:318-361` and `VirtualDisplayViewerTouchHandler.kt:41-45` continue using the original dimensions forever. There is no display listener, configuration listener, or recreate path.
   Why it matters: after rotation or density change, coordinate mapping, screenshot cropping, and app layout can drift apart. This is exactly the kind of latent bug that only appears on real devices under orientation churn.
   Fix direction: source real display metrics the same way the accessibility path does, watch for display/config changes, and recreate the virtual display when width/height/density change.

5. The accessibility capture path does not fail soft when the tree becomes invalid.
   Evidence: `AccessibilityPlatform.kt:157-206` dumps the raw tree, runs `Perceptor.snapshot`, writes trace artifacts, and only guarantees `roots.recycleCompat()` in `finally`. Unlike `VirtualDisplayCaptureCoordinator.kt:90-92`, it does not catch and downgrade snapshot/dump failures.
   Why it matters: one stale root, binder hiccup, or trace serialization failure can throw out of `captureScreen()` and abort a turn instead of returning an empty or partial snapshot.
   Fix direction: mirror the VD behavior here: bound debug work, catch tree/snapshot failures, and return the best safe snapshot instead of throwing from the platform boundary.

6. Resource ownership is inconsistent; recyclable objects and debug artifacts are leaked on hot paths.
   Evidence: `AccessibilityPlatform.kt:320-326` reads `service.rootInActiveWindow` and never recycles it. `VirtualDisplayPlatform.kt:407-418` scans `AccessibilityWindowInfo` objects for IME visibility and never recycles them. `AccessibilityScreenshotCapturer.kt:189-203` persists debug screenshots without any retention limit, unlike `VirtualDisplayScreenshotProcessor.kt:75-81`.
   Why it matters: these are called on repeated paths. Leaked nodes/windows add binder pressure; unbounded debug screenshots become a disk leak.
   Fix direction: add audited ownership helpers for nodes/windows and apply the same bounded debug retention policy in both screenshot paths.

7. Virtual-display app launch can silently fail while returning success.
   Evidence: `VirtualDisplayAppController.kt:73-80` always returns `ActionResult.Success` after `shizuku.launchOnDisplay(...)`. `ShizukuActivityLauncher.kt:13-27` catches launch exceptions and only logs them. `VirtualDisplayAppController.kt:33-35` also accepts `Display.INVALID_DISPLAY` without early rejection.
   Why it matters: callers cannot distinguish "launch succeeded on the VD" from "hidden API call threw and we logged it." That breaks recovery logic and makes app-launch failures look like downstream UI problems.
   Fix direction: make `launchOnDisplay` return success/failure, validate `displayId` first, and propagate failures through `ActionResult`.

## Medium
1. Live-preview surface replacement is ignored once the controller is already in `LIVE_PREVIEW`.
   Evidence: `VirtualDisplaySurfaceController.kt:49-52` returns early whenever the mode is already `LIVE_PREVIEW`, even if the incoming `SurfaceView` is a new instance after activity recreation.
   Why it matters: a recreated viewer can leave the VD rendering into a dead surface while the controller still believes live preview is active.
   Fix direction: compare surface identity, not just mode, and reswitch when the surface instance changes.

2. The shell fallback for viewer touch forwarding is synchronous and can block its caller for up to 30 seconds.
   Evidence: `VirtualDisplayViewerTouchHandler.kt:100-127` runs `shizuku.executeShellCommand(...)` directly in the touch path. `ShizukuShellExecutor.kt:13-35` waits synchronously for completion with a 30-second timeout.
   Why it matters: on devices where hidden `setDisplayId` injection is unavailable, the fallback path can freeze whichever thread called `onViewerTouch`.
   Fix direction: either move shell fallback off-thread or redesign the viewer path so it is not request/response on the caller thread.

3. Invalid scroll directions are silently normalized to "forward".
   Evidence: `NodeActionPerformer.kt:50-71` accepts any direction string. `NodeActionPerformer.kt:392-404` maps unknown values to `ACTION_SCROLL_FORWARD`.
   Why it matters: the platform boundary should validate bad inputs, not reinterpret them into a possibly destructive action.
   Fix direction: fail fast for unsupported directions.

4. Test coverage is thin where the reliability risk is highest.
   Evidence: platform tests currently cover `app/src/test/kotlin/com/moonkey/androidagent/platform/AppManagerTest.kt` and `app/src/test/kotlin/com/moonkey/androidagent/platform/NodeActionPerformerTest.kt`. There are no scoped tests for screenshot timeout behavior, binder death, surface switching, window selection, rotation, or virtual-display lifecycle recovery.
   Why it matters: the hardest failures here are regressions in edge handling, and those paths are currently unguarded.
   Fix direction: add focused unit/instrumentation tests before refactoring the lifecycle.

## Target Robustness Design
The platform layer needs a simpler contract:

- one lifecycle owner per active platform instance
- one serialized state machine for virtual-display resources
- bounded waits for every callback-driven framework API
- deterministic rules for window/root selection
- explicit ownership for every recyclable Android object
- fail-closed semantics when binder/display/service state becomes invalid

The current code already has the right building blocks (`VirtualDisplayCaptureCoordinator`, `VirtualDisplaySurfaceController`, `NodeActionPerformer`, `ShizukuClient`). The missing piece is a hard boundary that decides who owns state transitions and cleanup.

## Recommendation
CHANGES_REQUESTED

# Platform Robustness Improvement Plan (Codex)

## Goals
- no unbounded waits in platform code
- no leaked nodes, windows, bitmaps, binder proxies, or debug artifacts
- deterministic recovery or fail-closed behavior when the a11y service, Shizuku binder, or display state changes
- deterministic window targeting under popups, dialogs, and rotation
- regression coverage for the failure paths above

## Recommended Order
1. Introduce a serialized virtual-display lifecycle state machine.
   Files: `VirtualDisplayPlatform.kt`, `VirtualDisplaySurfaceController.kt`, `VirtualDisplayCaptureCoordinator.kt`, `VirtualDisplayInputInjector.kt`, `ShizukuClient.kt`
   Change: guard `start`, `stop`, `captureScreen`, `performAction`, `switchToLivePreview`, `switchToImageReader`, and binder-death handling behind one `Mutex` or actor-style owner; replace raw volatiles with explicit states such as `Stopped`, `Starting`, `Running`, `Stopping`, and `Broken`.
   Acceptance: no public VD operation can observe half-stopped state; binder death moves the instance to `Broken` and prevents further optimistic calls.

2. Make all callback-based capture paths bounded and cancellation-safe.
   Files: `AccessibilityScreenshotCapturer.kt`, `VirtualDisplayCaptureCoordinator.kt`
   Change: add one shared helper for callback-to-suspend conversion with timeout, `continuation.isActive` checks, and cleanup hooks; use it for `takeScreenshot`, `takeScreenshotOfWindow`, and `PixelCopy.request`.
   Acceptance: `captureScreen()` always completes within a fixed deadline even if the framework callback never arrives; cancellation never causes late-resume crashes or leaked bitmaps.

3. Make virtual-display gestures self-cleaning.
   Files: `VirtualDisplayInputInjector.kt`
   Change: once DOWN is injected, track gesture ownership and send best-effort `ACTION_CANCEL` or `ACTION_UP` in `finally`; fail the whole gesture on any MOVE failure instead of only checking the final UP.
   Acceptance: cancelling a long-press or swipe does not leave the target app in a pressed or dragging state.

4. Fix window selection and screenshot/window coherence.
   Files: `AccessibilityPlatform.kt`, `VirtualDisplayWindowAccessor.kt`
   Change: use explicit topmost-window selection for single-window operations, or fall back to full-display capture when more than one relevant window is present; sort VD windows by layer before choosing a root; keep tree and screenshot selection rules aligned.
   Acceptance: dialogs, popups, and transient windows do not cause screenshot/tree mismatches or node actions against background windows.

5. Add rotation-aware virtual-display recreation.
   Files: `VirtualDisplayConfig.kt`, `VirtualDisplayPlatform.kt`, `VirtualDisplayViewerTouchHandler.kt`, `VirtualDisplayCaptureCoordinator.kt`
   Change: source real display metrics from `WindowManager` rather than app-window `displayMetrics`; detect width/height/density changes and recreate the virtual display, `ImageReader`, and coordinate mapping when they change.
   Acceptance: rotating the device does not break screenshot cropping, viewer touch scaling, or node bounds interpretation.

6. Audit all platform-owned resources and make cleanup uniform.
   Files: `AccessibilityPlatform.kt`, `VirtualDisplayPlatform.kt`, `AccessibilityScreenshotCapturer.kt`, `VirtualDisplayScreenshotProcessor.kt`, `ShizukuShellExecutor.kt`, `ShizukuClient.kt`
   Change: recycle every temporary node/window via small ownership helpers; add bounded debug screenshot retention to the accessibility path; call `clearCachedProxies()` during shutdown and binder death; close process streams in the shell executor.
   Acceptance: repeated capture/action cycles do not grow binder object count, file count, or stale proxy state.

7. Make launch and viewer fallback semantics truthful.
   Files: `VirtualDisplayAppController.kt`, `ShizukuActivityLauncher.kt`, `VirtualDisplayViewerTouchHandler.kt`, `VirtualDisplaySurfaceController.kt`
   Change: make `launchOnDisplay` return a real success/failure result, reject `Display.INVALID_DISPLAY` early, move shell fallback off the caller thread, and allow live-preview surface replacement when a new `SurfaceView` arrives.
   Acceptance: failed VD app launches are reported as failures, old devices do not block the touch caller thread, and viewer recreation does not strand the display on a dead surface.

8. Add regression tests before and during the refactor.
   Files: new tests under `app/src/test/kotlin/com/moonkey/androidagent/platform/` and targeted instrumentation where framework fakes are insufficient
   Change: cover screenshot timeout, cancellation during VD gestures, binder death proxy invalidation, window selection under multiple windows, rotation-driven resize, live-preview surface replacement, and app-launch failure propagation.
   Acceptance: the new lifecycle is protected by tests for the exact failure modes that motivated the refactor.

## Suggested Milestones
1. Stabilize lifecycle and callback waits first.
   This removes the highest-severity hangs and zombie-state failures.

2. Fix window selection and rotation next.
   This removes the biggest correctness drift between perception, screenshots, and injected actions.

3. Finish with cleanup and regression coverage.
   That locks in the gains and prevents the platform layer from slowly drifting back toward optimistic, leak-prone behavior.

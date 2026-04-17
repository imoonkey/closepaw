# Platform Robustness Improvement Plan

## Goals
- no unbounded waits at the platform boundary
- no half-started or half-stopped VD state
- no stale binder proxies after stop, binder death, or restart
- no screenshot/tree/action mismatch under dialogs, popups, split windows, or rotation
- no silent success when the underlying platform action failed
- regression coverage for the failure paths above

## Non-Goals
- do not refactor `ShizukuClient` away from its facade role
- do not remove lambda providers in the VD stack unless a concrete bug requires it
- do not extract a shared capture abstraction just for style
- cursor placement in `setTextOnNode()` is verified correct — the pre-action snapshot logic is intentional, not stale state

## State Machine To Implement

### States
- `Stopped`
- `Starting`
- `Running(image_reader)`
- `Running(live_preview)`
- `Broken`
- `Stopping`

### Required invariants
- only one lifecycle transition runs at a time
- operational calls only run from `Running(*)`
- operational calls acquire a `Running` lease or equivalent before touching VD resources
- binder death moves the platform to `Broken`
- `stop()` is idempotent from every state
- partial startup failure always cleans up and returns to `Stopped`

## Phase 1: Serialize the VD lifecycle

### Changes
- Add one lifecycle arbiter for all public VD operations.
- Lifecycle transitions `start`, `stop`, `switchToLivePreview`, `switchToImageReader`, and binder-death handling take exclusive access.
- Operational calls `captureScreen` and `performAction` run under a shared `Running` lease or equivalent guard. They do not need a single global mutex with each other by default, but they also must not rely on a one-time state check while teardown can still proceed underneath them.
- Replace loose `displayId` / `imageReader` ownership with explicit state.
- Call `clearCachedProxies()` during both normal shutdown and broken-state teardown.
- Add rollback to `start()`: if cancellation or exception occurs after VD creation, release the display, close the reader, remove the binder listener, and reset state to `Stopped`. Currently `start()` assigns `displayId` and `imageReader` with no try/finally, so a failure after that point leaves a half-started platform that the next `start()` will no-op on.

### Files
- `VirtualDisplayPlatform.kt`
- `VirtualDisplaySurfaceController.kt`
- `VirtualDisplayCaptureCoordinator.kt`
- `VirtualDisplayInputInjector.kt`
- `ShizukuClient.kt`

### Acceptance
- No public VD method can observe half-stopped state.
- Cancellation or exception during `start()` does not leave resources allocated with no owner.
- Agent-initiated lifecycle transitions cannot invalidate resources under an in-flight operational call.
- Binder death transitions the platform to `Broken`.
- Restart after stop or Shizuku reconnect does not reuse stale proxies.
- Operational calls fail fast with a clear message if the platform is not Running.

## Phase 2: Bound every callback wait

### Changes
- Add one helper for callback-to-suspend bridging with timeout and `invokeOnCancellation` cleanup.
- Use it for:
  - `takeScreenshot`
  - `takeScreenshotOfWindow`
  - `PixelCopy.request`
- Make timeout behavior explicit: fail closed or fall back, but never wait forever.
- Note: with coroutines 1.7.3, late resume on a cancelled continuation does not crash (it silently discards). The primary risk is unbounded waits and leaked resources (e.g., preallocated Bitmap in the PixelCopy path), not crashes.

### Files
- `AccessibilityScreenshotCapturer.kt`
- `VirtualDisplayCaptureCoordinator.kt`

### Acceptance
- `captureScreen()` always completes inside a bounded deadline.
- Cancellation cleans up preallocated resources (e.g., Bitmap).
- PixelCopy failure falls back cleanly without wedging capture.

## Phase 3: Make input injection self-cleaning

### Changes
- Make VD long-press and swipe cancellation-safe.
- Once DOWN is sent, track gesture ownership until completion.
- Treat MOVE failure as a failed gesture, not a success-in-progress.
- Send best-effort `ACTION_CANCEL` or `ACTION_UP` in `finally`.
- Keep IME suppression inside the serialized lifecycle so suppress/restore cannot interleave incorrectly.

### Files
- `VirtualDisplayInputInjector.kt`
- `VirtualDisplayPlatform.kt`

### Acceptance
- Cancelling a long-press or swipe does not leave the target UI in a stuck touch state.
- IME suppression and restore cannot race across overlapping calls.

## Phase 4: Fix window selection and screenshot/root coherence

### Changes
- Define explicit eligible-window rules for both platforms.
- For single-window operations, use the topmost relevant window.
- When multiple relevant windows are present, choose a deterministic fallback rather than mixing tree and screenshot sources.
- Sort VD windows by layer before choosing roots.
- Fix `VirtualDisplayWindowAccessor.getRootOnDisplay()` single-root selection to use layer ordering — this affects `NodeActionPerformer` action targeting and `getCurrentPackageName()` foreground-package/privacy gating, not just screenshots.
- On the accessibility side, align the root policy used by capture (`collectRootsOnActiveDisplay()` — all roots), actions (`service.rootInActiveWindow` — single root), and privacy gating (`getCurrentPackageName()` — single root). Currently capture sees all windows while actions and gating see only one, so a blocked app behind an allowed dialog can pass the package check while its background nodes leak into the tree.

### Files
- `AccessibilityPlatform.kt`
- `VirtualDisplayWindowAccessor.kt`

### Acceptance
- Dialogs and popups do not cause screenshot/tree mismatch.
- Node actions do not target background windows when a higher-layer window is active.
- `getCurrentPackageName()` returns the correct foreground package under multi-window conditions.
- Capture, action, and privacy-gating root policies are aligned on both platforms.

## Phase 5: Handle rotation and display-size churn

### Changes
- Source real display metrics via `WindowManager.maximumWindowMetrics.bounds`, not app content metrics from `context.resources.displayMetrics`.
- Detect width, height, or density change.
- Recreate the VD, `ImageReader`, and coordinate mapping when geometry changes.
- Keep viewer touch scaling and screenshot processing aligned with the recreated geometry.

Note: The VD is a self-contained coordinate space, so agent actions within it do not drift on rotation. The primary impact is viewer UX degradation and initial sizing. This phase is Medium priority.

### Files
- `VirtualDisplayConfig.kt`
- `VirtualDisplayPlatform.kt`
- `VirtualDisplayViewerTouchHandler.kt`
- `VirtualDisplayCaptureCoordinator.kt`

### Acceptance
- Rotating the device does not break screenshot cropping, touch scaling, or bounds interpretation.
- VD geometry matches current real display geometry after rotation.

## Phase 6: Harden platform boundary correctness

### Changes
- Make the accessibility capture path fail soft on tree, trace, and snapshot failures.
- Fix `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` to rethrow `CancellationException` instead of swallowing it as an empty snapshot (the current `catch (Exception)` swallows cancellation).
- Move `Perceptor.snapshot()` and `Perceptor.toPromptJson()` off `Dispatchers.Main` in `captureA11yTreeWithArtifacts()` — currently the full capture flow runs in `withContext(Dispatchers.Main)`, blocking the service/viewer main thread during large-tree perception.
- Make app launch return truthful success/failure results.
- Reject invalid display state early in VD launch and action paths.
- Fix live-preview surface replacement so a recreated viewer surface can take over.
- Move the shell touch fallback off the caller thread or redesign it so the caller does not wait synchronously.

### Files
- `AccessibilityPlatform.kt`
- `VirtualDisplayCaptureCoordinator.kt`
- `VirtualDisplayAppController.kt`
- `ShizukuActivityLauncher.kt`
- `VirtualDisplaySurfaceController.kt`
- `VirtualDisplayViewerTouchHandler.kt`

### Acceptance
- Accessibility capture errors return best-effort platform results instead of aborting the turn.
- VD capture cancellation propagates correctly instead of producing misleading empty results.
- Failed VD app launch is visible to callers.
- Viewer recreation does not strand the display on a dead surface.

## Phase 7: Normalize cleanup and low-risk hygiene

### Changes
- Audit temporary node and window ownership and centralize recycling helpers where useful.
- Fix `AccessibilityPlatform.getCurrentPackageName()` to recycle `rootInActiveWindow` — this is a hot-path leak called every `captureScreen()` turn.
- Fix `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` to recycle `AccessibilityWindowInfo` objects — this is a hot-path leak called before many IME-sensitive actions in `performAction()`.
- Add retention limits to accessibility debug screenshots to match the VD path.
- Remove dead private helpers:
  - `AccessibilityGestureInjector.gestureDisplayId()`
  - `NodeActionPerformer.performNodeActionAt()`
- Document the `DISPLAY_FLAGS` bitmask inline.
- If the touched code makes it cheap, also harden small cleanup edges such as bitmap cleanup on exceptional screenshot-copy paths.

### Files
- `AccessibilityPlatform.kt`
- `AccessibilityScreenshotCapturer.kt`
- `VirtualDisplayScreenshotProcessor.kt`
- `AccessibilityGestureInjector.kt`
- `NodeActionPerformer.kt`
- `VirtualDisplayPlatform.kt`

### Acceptance
- Repeated platform use does not grow unrecycled object pressure or unbounded debug artifacts.
- `getCurrentPackageName()` no longer leaks a root node on every call.
- `isKeyboardVisibleOnMainDisplay()` no longer leaks window objects on every call.
- The remaining private helpers are intentional.
- `DISPLAY_FLAGS` is readable without reverse-engineering the bitmask.

## Phase 8: Lock it down with tests

### Changes
- Add targeted tests for:
  - bounded screenshot timeout behavior
  - cancellation during VD gestures
  - binder death and stale proxy invalidation
  - window selection under multiple windows
  - rotation-driven resize and remap
  - live-preview surface replacement
  - truthful app-launch failure propagation

### Files
- tests under `app/src/test/kotlin/com/moonkey/androidagent/platform/`
- instrumentation where framework fakes are insufficient

### Acceptance
- The highest-risk edge cases are covered by regression tests.

## Implementation Order
1. Phase 1 and Phase 2 together.
   This removes the biggest hang and zombie-state risks.
2. Phase 3.
   This fixes interruption safety and cleans up IME sequencing.
3. Phase 4 and Phase 5.
   This fixes correctness drift between perception, screenshot, and injection.
4. Phase 6.
   This makes platform outputs truthful and viewer behavior deterministic.
5. Phase 7 and Phase 8.
   This cleans up remaining debt and prevents regressions.

## Done Criteria
- No platform call can wait forever on a framework callback.
- No VD session can remain half-alive after binder death, stop, or failed start.
- No screenshot/tree/action mismatch remains under common multi-window cases.
- Rotation does not break VD viewer geometry.
- Launch and input results are truthful.
- Hot-path resource leaks (`getCurrentPackageName()`, `isKeyboardVisibleOnMainDisplay()`) are fixed.
- Coroutine cancellation propagates correctly through capture paths.

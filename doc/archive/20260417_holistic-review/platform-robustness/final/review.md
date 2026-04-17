# Platform Robustness Review

## Scope
Reviewed the full `platform/` module:

- `app/src/main/kotlin/com/moonkey/androidagent/platform/`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`

Focus:

- accessibility-service death and stale tree handling
- virtual-display lifecycle, Shizuku binder loss, and surface/display churn
- node/window correctness under dialogs, popups, and rotation
- resource ownership and cleanup
- race conditions, bounded failure behavior, and truthful result reporting

## Summary
The module is reasonably decomposed. `AndroidPlatform` is a clean boundary, shared logic is extracted where it should be, and the Shizuku facade plus lambda providers are acceptable design choices. They are not the main problem.

The real problem is robustness at the platform boundary. The accessibility and virtual-display implementations both assume the framework will keep calling back, objects will stay valid long enough, and lifecycle edges will be serialized by callers. That is too optimistic. Under service death, binder death, rotation, live-preview churn, or cancellation, the platform can hang, drift out of sync, leak resources, or report success when the underlying action failed.

## Critical Findings

### 1. Callback-driven capture paths are unbounded
`AccessibilityScreenshotCapturer` waits on `takeScreenshot` / `takeScreenshotOfWindow` without a timeout. `VirtualDisplayCaptureCoordinator` does the same for `PixelCopy.request`. The PixelCopy path also does not register `invokeOnCancellation` cleanup for its preallocated `Bitmap`, so cancellation leaks the bitmap even though it does not crash (coroutines 1.7.3 silently discards late resumes on cancelled continuations).

Why this matters:

- a lost framework callback can wedge `captureScreen()` forever
- no caller (`AgentTurnRunner`, `TurnExecutionPhaseRunner`, `ToolRouter`) wraps platform capture in a timeout, so a stuck callback stalls the entire agent turn
- cancellation leaks resources even though it does not crash

Required fix:

- one shared bounded callback helper with `invokeOnCancellation` cleanup
- timeout on every callback-to-suspend bridge
- deterministic fallback or failure result when the callback never arrives

### 2. Virtual-display gestures are not cancellation-safe
`VirtualDisplayInputInjector` injects DOWN, suspends, and later injects UP for long-press and swipe. If the coroutine is cancelled mid-gesture, there is no best-effort cleanup event.

Why this matters:

- the target app can be left in a stuck pressed or dragging state
- partial MOVE failures are ignored, so a gesture can already be broken before the code reports success

Required fix:

- track whether DOWN was delivered
- fail the gesture on any critical mid-stream injection failure
- send best-effort `ACTION_CANCEL` or `ACTION_UP` in `finally`

## High Findings

### 1. The virtual-display stack has no single lifecycle owner
`VirtualDisplayPlatform` spreads live state across `displayId`, `imageReader`, surface mode, callback tokens, and cached binder proxies, but there is no serialized owner for `start`, `stop`, `captureScreen`, `performAction`, `switchToLivePreview`, `switchToImageReader`, and binder-death handling. The code comment on `stop()` already documents "Not safe to call concurrently with captureScreen/performAction" — this is a known caller contract, but nothing enforces it.

Why this matters:

- `stop()` can race with capture or action work and no guard prevents it
- surface switching can race with screenshot capture
- IME suppression is implicitly racy if actions ever overlap
- half-started and half-stopped states are possible
- `start()` has no rollback: cancellation/exception after VD creation leaves `displayId` and `imageReader` assigned while the session believes startup failed; the next `start()` no-ops because `displayId` is already set

Required fix:

- one serialized lifecycle owner
- explicit states instead of loosely related volatiles
- no public operation allowed to observe half-started state

### 2. Shizuku binder death is not handled as a lifecycle transition
The binder-death listener only logs. Cached Shizuku proxies are never cleared during stop or binder death, even though `ShizukuClient.clearCachedProxies()` exists for that job.

Why this matters:

- dead binder wrappers can survive after Shizuku restart
- later failures happen deep in reflection and transport code instead of at the platform boundary
- the session cannot distinguish recoverable platform loss from random action failure

Required fix:

- on binder death, transition to a broken state
- clear cached proxies
- release local VD resources
- make later calls fail closed with a clear reason

### 3. Window selection is wrong under multi-window conditions
The accessibility path sorts windows by ascending layer (`.sortedBy { it.layer }`), then uses `roots.firstOrNull()?.windowId` for `takeScreenshotOfWindow`, which picks the lowest-layer (bottom) window instead of the topmost. Meanwhile, `captureAccessibilityTree()` collects all roots from all non-overlay/non-IME windows, but actions use `service.rootInActiveWindow` (a single root) and `getCurrentPackageName()` also uses `rootInActiveWindow`. This means capture, actions, and privacy gating each use a different window/root policy. On the VD side, `VirtualDisplayWindowAccessor.getRootOnDisplay()` picks the first `TYPE_APPLICATION` window with no layer ordering — this single-root accessor is used by `NodeActionPerformer` for action targeting and by `getCurrentPackageName()` for foreground-package detection and privacy gating.

Why this matters:

- accessibility screenshots target the background window when a dialog or popup is present (Android U+ only)
- the accessibility tree includes all window roots, but actions and privacy gating use only `rootInActiveWindow` — a blocked app behind an allowed dialog can pass the package check while its background nodes leak into the tree
- VD node actions can target the wrong window under dialogs/popups
- `getCurrentPackageName()` can return the wrong package, affecting privacy gating in `captureScreen()`

Required fix:

- explicit topmost-window selection rules
- consistent screenshot/root targeting policy
- full-display fallback when single-window assumptions do not hold

### 4. Accessibility capture does not fail soft
The accessibility path does not downgrade tree dump, trace, or `Perceptor.snapshot()` failures into a safe platform-level result. The VD path wraps `Perceptor.snapshot()` in try/catch and returns empty on failure; the accessibility path does not.

Why this matters:

- one stale root or trace failure can throw out of `captureScreen()`
- error handling is asymmetrical between accessibility and VD paths

Required fix:

- bound debug work
- catch tree/snapshot failures at the platform boundary
- return best-effort empty or partial snapshots instead of crashing the turn

### 5. Some platform calls report success when they may have failed
`VirtualDisplayAppController` reports success after `launchOnDisplay(...)`, but `ShizukuActivityLauncher.launchOnDisplay()` catches all exceptions and only logs them. `ShizukuClient.launchOnDisplay()` returns `Unit`, so the controller unconditionally returns `ActionResult.Success`.

Why this matters:

- callers cannot distinguish app-launch failure from later UI failure
- retry and recovery logic gets bad input

Required fix:

- let launch exceptions propagate so the outer catch handles them
- reject invalid display state early
- propagate failure through `ActionResult`

## Medium Findings

### 1. Virtual-display geometry becomes stale after rotation or display-size change
`VirtualDisplayConfig` snapshots app content-area metrics (via `context.resources.displayMetrics`) once at startup and the VD stack keeps using them forever. Note: the VD is a self-contained coordinate space, so agent actions within the VD do not drift when the physical screen rotates. The real impact is limited to viewer UX degradation and initial sizing using content-area metrics instead of real display metrics (`WindowManager.maximumWindowMetrics.bounds`).

Why this matters:

- viewer touch scaling becomes wrong after rotation
- the VD may be slightly smaller than the physical display from the start
- app layout on the VD can diverge from current device geometry in the viewer

Required fix:

- source real display metrics via `WindowManager`, not app content metrics
- detect width, height, or density change
- recreate the VD and `ImageReader` when geometry changes

### 2. Resource ownership has specific gaps
Resource recycling is mostly consistent across the codebase, but there are specific hot-path gaps:

- `AccessibilityPlatform.getCurrentPackageName()` acquires `rootInActiveWindow` but never recycles it — called every `captureScreen()` turn
- `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` acquires `AccessibilityWindowInfo` objects but never recycles them — called before many IME-sensitive actions in `performAction()`
- accessibility debug screenshots have no retention cap (VD path caps at 20)
- `ShizukuClient.clearCachedProxies()` exists but is never called — it is dead code

Why this matters:

- both hot-path leaks add binder pressure on every turn
- disk usage grows without bound in debug mode on the accessibility path
- stale proxy state can survive beyond session lifetime

Required fix:

- recycle the root node in `getCurrentPackageName()`
- recycle window objects in `isKeyboardVisibleOnMainDisplay()`
- add bounded debug screenshot retention to the accessibility path
- call `clearCachedProxies()` during shutdown and broken-state handling

### 3. VD accessibility capture swallows coroutine cancellation
`VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` catches `Exception` and converts it to an empty result. In Kotlin, `CancellationException` is an `Exception`, so cancellation is also swallowed here. This does not wedge the platform or corrupt lifecycle state, but it turns cancellation into a misleading empty capture and can delay clean shutdown.

Required fix:

- rethrow `CancellationException` instead of swallowing it

### 4. VD accessibility capture runs heavy work on Dispatchers.Main
`VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` wraps the full flow in `withContext(Dispatchers.Main)`, including `Perceptor.snapshot()` and `Perceptor.toPromptJson(snapshot)`. The accessibility path limits Main-thread work to display/window collection and performs perception work outside the Main block. This violates the project's main-safe rule.

Why this matters:

- large-tree perception and sanitized-tree serialization can block the service/viewer main thread
- increases the odds of jank or delayed framework callbacks in VD mode

Required fix:

- move perception and serialization work off `Dispatchers.Main`

### 5. The viewer shell fallback can block its caller thread
On devices without hidden display-id injection, the fallback path runs shell input synchronously via `ShizukuShellExecutor.waitForProcess()` which blocks with a polling `Thread.sleep` loop for up to 30 seconds.

Why this matters:

- touch forwarding can freeze the UI thread on older or degraded devices

Required fix:

- move shell fallback off the caller thread or redesign the fallback path

## Low Findings

### 1. Live-preview surface replacement is ignored
Once the surface controller is already in `LIVE_PREVIEW`, a new `SurfaceView` instance is ignored. The normal viewer lifecycle calls `notifyViewerHidden()` on `surfaceDestroyed` / `onStop`, which flips back to `IMAGE_READER` first, so triggering this requires unusual callback ordering or a prior switch-back failure.

### 2. Dead private helpers should be removed
Low-risk cleanup:

- `AccessibilityGestureInjector.gestureDisplayId()`
- `NodeActionPerformer.performNodeActionAt()`

### 3. `DISPLAY_FLAGS` needs documentation
The raw bitmask in `VirtualDisplayPlatform` (6 hex flags including hidden APIs) is opaque and should be documented inline. Not a robustness bug, readability only.

### 4. Invalid scroll directions are normalized silently
Unknown direction strings degrade to forward scrolling. Production tool input already validates direction via `MobileActionTool` schema enum, so this only affects direct/debug construction paths. Low practical risk.

## Verified Non-Issues

These were investigated and confirmed NOT to be bugs:

- **Append-mode cursor placement in `setTextOnNode()`**: The code intentionally computes cursor position from pre-`ACTION_SET_TEXT` snapshot state. The `combined` string and selection offset are derived from the same pre-action values, which is coherent. Independent audit verified this is correct behavior, not stale state.
- **Node-recycling in `AccessibilityNodeFinder`**: DFS pattern is correct throughout.
- **Root/window lifetime in `collectRootsOnActiveDisplay()`**: Recycling a window does not invalidate its root node.
- **`OverlayTouchGate` timeout**: The caller (`AccessibilityGestureInjector`) correctly handles close in try/finally.

## Non-Findings and Deliberate Non-Changes
These are not the current robustness problem and should not drive refactors:

- keep `ShizukuClient` as the facade boundary
- keep lambda providers used to avoid circular dependencies in the VD stack
- do not extract a shared accessibility-capture abstraction yet

## Target Robustness Design

### Platform boundary rules
- Every callback-driven framework API must be bounded.
- Every temporary Android object must have clear ownership.
- Every public VD operation must go through one lifecycle arbiter: lifecycle transitions are exclusive, and operational calls run under a coordinated `Running` lease.
- Binder death, service death, and geometry change must be first-class transitions, not incidental log lines.

### Virtual-display lifecycle state machine

#### States
- `Stopped`
- `Starting`
- `Running(image_reader)`
- `Running(live_preview)`
- `Broken`
- `Stopping`

#### Transitions
- `Stopped -> Starting`
  Side effects: create `ImageReader`, create virtual display, register binder-death listener, initialize surface mode.
- `Starting -> Running(image_reader)`
  Guard: all startup steps succeed.
- `Starting -> Stopped`
  Guard: startup fails.
  Side effects: close reader, clear callbacks, clear cached proxies as needed.
- `Running(*) -> Running(live_preview)`
  Guard: new valid preview surface.
- `Running(*) -> Running(image_reader)`
  Guard: preview hidden or pixel-copy fallback requires it.
- `Running(*) -> Broken`
  Guard: binder death or unrecoverable platform loss.
  Side effects: invalidate public state, clear proxies, release local resources, fail later calls closed.
- `Running(*) -> Stopping -> Stopped`
  Side effects: idempotent cleanup of listeners, surfaces, display, reader, IME state, and proxies.

### Design target
Turn edge cases into canonical cases:

- one broken state instead of many partial-failure states
- one bounded callback adapter instead of bespoke suspensions
- one cleanup path per owned resource class
- one lifecycle arbiter instead of scattered state checks

## Recommendation

Recommendation: CHANGES_REQUESTED.

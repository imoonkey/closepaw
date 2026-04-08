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
`AccessibilityScreenshotCapturer` waits on `takeScreenshot` / `takeScreenshotOfWindow` without a timeout. `VirtualDisplayCaptureCoordinator` does the same for `PixelCopy.request`.

Why this matters:

- a lost framework callback can wedge `captureScreen()` forever
- service death or surface teardown can turn one bad turn into a stuck session
- cancellation safety is inconsistent, especially in the PixelCopy path

Required fix:

- one shared bounded callback helper
- timeout on every callback-to-suspend bridge
- cancellation-safe resume checks
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
`VirtualDisplayPlatform` spreads live state across `displayId`, `imageReader`, surface mode, callback tokens, and cached binder proxies, but there is no serialized owner for `start`, `stop`, `captureScreen`, `performAction`, `switchToLivePreview`, `switchToImageReader`, and binder-death handling.

Why this matters:

- `stop()` can race with capture or action work
- surface switching can race with screenshot capture
- IME suppression is implicitly racy if actions ever overlap
- half-started and half-stopped states are possible

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
The accessibility path sorts windows by ascending layer, then uses the first root for `takeScreenshotOfWindow`, which biases toward the lowest layer rather than the topmost window. The VD path chooses an application window without explicit layer ordering.

Why this matters:

- screenshot, tree, and action targeting can diverge
- dialogs, popups, and split-window layouts can send actions to the background window

Required fix:

- explicit topmost-window selection rules
- consistent screenshot/root targeting policy
- full-display fallback when single-window assumptions do not hold

### 4. Virtual-display geometry becomes stale after rotation or display-size change
`VirtualDisplayConfig` snapshots app metrics once at startup and the VD stack keeps using them forever.

Why this matters:

- coordinate mapping drifts after rotation
- screenshot cropping and viewer touch scaling become wrong
- app layout on the VD can diverge from current device geometry

Required fix:

- source real display metrics, not app content metrics
- detect width, height, or density change
- recreate the VD and `ImageReader` when geometry changes

### 5. Accessibility capture does not fail soft
The accessibility path does not downgrade tree dump, trace, or `Perceptor.snapshot()` failures into a safe platform-level result.

Why this matters:

- one stale root or trace failure can throw out of `captureScreen()`
- error handling is asymmetrical between accessibility and VD paths

Required fix:

- bound debug work
- catch tree/snapshot failures at the platform boundary
- return best-effort empty or partial snapshots instead of crashing the turn

### 6. Resource ownership is inconsistent
There are repeated hot-path ownership issues:

- temporary roots and windows are not consistently recycled
- accessibility debug screenshots have no retention cap
- stale binder proxies survive beyond session lifetime

Why this matters:

- binder pressure rises on repeated use
- disk usage grows without bound in debug mode
- stale proxy state survives into later sessions

Required fix:

- audited ownership helpers for temporary nodes and windows
- explicit proxy cleanup during shutdown and broken-state handling
- bounded retention for debug artifacts in both screenshot paths

### 7. Some platform calls report success when they may have failed
`VirtualDisplayAppController` reports success after `launchOnDisplay(...)`, but the launcher swallows exceptions and only logs them.

Why this matters:

- callers cannot distinguish app-launch failure from later UI failure
- retry and recovery logic gets bad input

Required fix:

- make launch return a truthful success/failure result
- reject invalid display state early
- propagate failure through `ActionResult`

## Medium Findings

### 1. Live-preview surface replacement is ignored
Once the surface controller is already in `LIVE_PREVIEW`, a new `SurfaceView` instance is ignored.

Why this matters:

- viewer recreation can strand the VD on a dead surface

Required fix:

- compare surface identity, not only mode

### 2. The viewer shell fallback can block its caller thread
On devices without hidden display-id injection, the fallback path runs shell input synchronously and waits for command completion.

Why this matters:

- touch forwarding can block badly on older or degraded devices

Required fix:

- move shell fallback off the caller thread or redesign the fallback path

### 3. Invalid scroll directions are normalized silently
Unknown direction strings currently degrade to forward scrolling.

Why this matters:

- the platform boundary should not reinterpret invalid input into a different action without at least surfacing it

Required fix:

- validate inputs explicitly
- either fail fast or log-and-fallback intentionally

### 4. Append-mode cursor placement needs proof before changing
There is a plausible concern that `NodeActionPerformer.setTextOnNode()` may compute cursor placement from stale node state after `ACTION_SET_TEXT`. That is worth testing, but it is not yet proven enough to treat as an accepted bug.

Required fix:

- add a focused regression test for append-mode caret placement
- change the cursor logic only if the test demonstrates the current behavior is wrong

## Low Findings

### 1. Dead private helpers should be removed
Low-risk cleanup:

- `AccessibilityGestureInjector.gestureDisplayId()`
- `NodeActionPerformer.performNodeActionAt()`

### 2. `DISPLAY_FLAGS` needs documentation
The raw bitmask in `VirtualDisplayPlatform` is opaque and should be documented inline.

## Non-Findings and Deliberate Non-Changes
These are not the current robustness problem and should not drive refactors:

- keep `ShizukuClient` as the facade boundary
- keep lambda providers used to avoid circular dependencies in the VD stack
- do not extract a shared accessibility-capture abstraction yet
- do not change text-cursor semantics without a failing test

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
Use the Codex design as the base. Carry forward Claude's valid additions as follow-up items:

- dead code removal
- `DISPLAY_FLAGS` documentation
- a test-gated review of append-mode cursor placement

Recommendation: CHANGES_REQUESTED.

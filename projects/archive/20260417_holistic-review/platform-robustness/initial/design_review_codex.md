# Cross-Review: Claude vs Codex Platform Robustness Designs

## Bottom Line
Claude's review is useful as a small-fix pass, but it is not a sufficient base for a platform-robustness effort. It catches a few real issues and correctly declines some unnecessary refactors, but it misses the main session-wedging failure modes and over-prioritizes at least one claim that is weak or possibly wrong.

Codex is the better base because it identifies the dominant correctness risks, groups them into a coherent lifecycle model, and turns that into a plan that matches the actual failure surface of this module.

## Findings

### 1. Claude misses the most severe failure modes
Claude does not call out two issues that should dominate the review:

1. Unbounded callback waits in screenshot capture.
   `AccessibilityScreenshotCapturer` waits on `takeScreenshot` / `takeScreenshotOfWindow` with no timeout, and `VirtualDisplayCaptureCoordinator` does the same for `PixelCopy.request`. If the accessibility service dies, the callback is lost, or the live-preview surface is in a bad state, `captureScreen()` can suspend forever.

2. Cancellation-unsafe virtual-display gestures.
   `VirtualDisplayInputInjector` injects DOWN, suspends, and later injects UP for long-press and swipe. If the coroutine is cancelled in the middle, there is no best-effort cleanup event. That can leave the target app in a stuck pressed/dragging state.

These are more severe than stale binder proxies or dead code because they can wedge a session even when everything else is nominal. A robustness plan that does not front-load them is mis-prioritized.

### 2. Claude's binder-death remedy is directionally right but incomplete
Claude is correct that the current binder-death listener is too weak and that cached Shizuku proxies need cleanup. The problem is that the proposed fix is too small for the real failure mode.

Setting `displayId = Display.INVALID_DISPLAY` on binder death is not enough. It still leaves:

- cached display/input proxies
- local `ImageReader` state
- surface-controller mode
- virtual-display callback tokens
- in-flight calls that are already reading stale state

The more correct model is to move the virtual-display platform into a broken state, clear proxies, release local resources, and make future calls fail closed at the platform boundary. This is the core reason the Codex design is stronger: it treats binder death as a lifecycle transition, not just a flag write.

### 3. Claude overweights a likely non-bug in `NodeActionPerformer`
Claude's P0 recommendation to call `node.refresh()` before cursor positioning in `setTextOnNode()` is not well grounded.

The current code computes `combined` from the pre-`ACTION_SET_TEXT` snapshot, then uses the same node snapshot to derive the insertion point for `ACTION_SET_SELECTION`. That is actually coherent: the insertion point it wants is the pre-action selection position. Refreshing before cursor placement can make the selection logic depend on post-action framework behavior, which may already have moved the cursor and can produce different semantics.

This item needs a failing test before it should be treated as a fix, much less as a P0 fix. As written, Claude's plan risks changing behavior without strong evidence that the current behavior is wrong.

### 4. Claude spends review budget on low-yield or weak issues
Two examples stand out:

1. The `ImageReader` leak on `createVirtualDisplay()` exception is weak.
   `ShizukuDisplayTransport.createVirtualDisplay()` already catches `Exception` and returns `-1`, so the claimed reflection-exception path is not the dominant startup risk. The real problems are stale binder state, concurrency, and recovery after partial startup/teardown.

2. The bitmap leak on `copyPixelsFromBuffer` failure is real but secondary.
   It is worth fixing eventually, but it is not in the same class as "capture can hang forever" or "gesture can leave the target UI stuck."

This matters because Claude's improvement plan promotes these smaller issues while leaving larger correctness risks untouched.

### 5. Claude misses several important correctness gaps
The Claude design does not account for multiple issues that should shape the implementation plan:

1. Wrong or unstable window targeting.
   The accessibility path uses the first root after ascending layer sort when choosing the window for `takeScreenshotOfWindow`, which biases toward the lowest layer, not the topmost window. The virtual-display path also chooses an application window without layer ordering. This can make screenshot, tree, and action targeting diverge under dialogs, popups, and split-window conditions.

2. No rotation or display-size churn handling after VD startup.
   `VirtualDisplayConfig` snapshots dimensions once from app metrics, and the VD stack never recreates the display on width/height/density change. That leaves coordinate mapping and screenshot geometry stale after rotation.

3. Accessibility capture does not fail soft.
   The accessibility path does not downgrade tree/trace/perceptor failures into a safe empty or partial snapshot. One bad root or trace serialization failure can throw out of the platform boundary.

4. Some platform calls report success when the action may have failed.
   `VirtualDisplayAppController` returns success after `launchOnDisplay(...)` even though the launcher method swallows exceptions and only logs them.

5. Viewer surface replacement and shell fallback behavior are under-reviewed.
   The live-preview controller ignores a new `SurfaceView` instance once it is already in live-preview mode, and the viewer touch shell fallback can block the caller thread while waiting for shell completion.

6. Repeated ownership leaks are not addressed.
   There are recyclable node/window paths and unbounded debug screenshot retention paths that should be normalized as part of robustness hardening.

These are not optional polish. They affect correctness under exactly the edge conditions named in the review prompt.

### 6. Where Claude is strong
Claude's review has value in three places:

1. It correctly rejects some false positives.
   The node-recycling pattern in `AccessibilityNodeFinder`, the root/window lifetime handling, and the overlay touch gate usage are all treated carefully rather than being flagged reflexively.

2. It is right not to overreact to benign design patterns.
   Keeping `ShizukuClient` as a facade and using lambda providers in the VD stack are reasonable trade-offs. Those are not the source of current robustness failures.

3. It identifies cleanup tasks worth preserving.
   Removing dead code and documenting the virtual-display flags are legitimate maintenance tasks. They just should not outrank lifecycle hardening.

## Trade-Offs

### Claude
Strengths:

- lower churn
- quick to land
- avoids unnecessary abstraction
- useful cleanup bias

Weaknesses:

- misses the highest-risk hangs and cancellation failures
- does not give the VD stack a coherent lifecycle model
- under-specifies binder-death recovery
- puts too much weight on small or weakly justified issues

### Codex
Strengths:

- matches the real failure modes in this module
- treats binder death, stop/start, capture, and input as one lifecycle problem
- addresses session-wedging hangs first
- captures window-selection, rotation, and truthful result semantics

Weaknesses:

- larger implementation cost
- requires disciplined sequencing and test coverage
- easier to overbuild if the state-machine refactor is done without tight scope

That trade-off is acceptable here. The user asked for a platform-robustness review, not a small cleanup pass.

## Recommendation
Use the Codex design as the base.

Carry forward a few Claude points as secondary follow-ups:

- keep the Shizuku facade and lambda-provider structure unless a concrete problem appears
- remove the dead private helpers
- document `DISPLAY_FLAGS`
- prove any `NodeActionPerformer` cursor change with a failing test before landing it

Better base: CODEX.

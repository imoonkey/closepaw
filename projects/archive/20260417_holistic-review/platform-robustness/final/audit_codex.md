# Platform Robustness Audit (Codex)

## Method
- Read `doc/todo/holistic-review/platform-robustness/final/review.md` and `doc/todo/holistic-review/platform-robustness/final/improvement_plan.md`.
- Verified each finding against the current source under `app/src/main/kotlin/com/moonkey/androidagent/platform/` and `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`.
- Traced real call paths through session shutdown, tool execution, viewer lifecycle, and app launch code where that changed severity or scope.
- For the PixelCopy cancellation claim, also checked the actual coroutine dependency version in `app/build.gradle.kts:72` and decompiled `kotlinx.coroutines.CancellableContinuationImpl` from coroutines `1.7.3`.
- Ran `./gradlew testDebugUnitTest --tests com.moonkey.androidagent.platform.NodeActionPerformerTest` on 2026-04-09. It passed.

## Executive Summary
- The two top-priority buckets are real: unbounded callback waits and cancellation-unsafe VD gestures both need fixing first.
- The most important correction: the review's PixelCopy "late resume will throw" claim is not true on the coroutine version this app uses. The unbounded-wait risk is real; the specific cancellation-crash explanation is not.
- Most High findings are real. High #3 needs scope correction: the accessibility side is mostly a screenshot-selection bug, but the VD side is more serious for action root selection and current-package/privacy-gating than for screenshots.
- The Medium section is mixed. The hot-path resource leak is worth fixing. Geometry churn is real but lower urgency than the review's overall emphasis might suggest. Surface replacement and invalid scroll direction handling are both overstated.
- The Verified Non-Issues section is mostly correct. Cursor placement is correctly treated as a non-bug.
- The final review missed real issues:
  1. `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` leaks `AccessibilityWindowInfo` objects on a hot path.
  2. `VirtualDisplayPlatform.start()` has no rollback if cancellation/exception happens after the VD is created.
  3. `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` swallows coroutine cancellation by catching `Exception`.

## Critical Findings

### Critical #1: Callback-driven capture paths are unbounded
**Verdict:** REAL BUG, but one important sub-claim is false.

**What the code does**
- `AccessibilityScreenshotCapturer.takeDisplayScreenshot()` bridges `AccessibilityService.takeScreenshot(...)` to `suspendCancellableCoroutine` with no timeout in `AccessibilityScreenshotCapturer.kt:60-87`.
- `AccessibilityScreenshotCapturer.takeWindowScreenshot()` does the same for `takeScreenshotOfWindow(...)` in `AccessibilityScreenshotCapturer.kt:90-121`.
- `VirtualDisplayCaptureCoordinator.captureFromPixelCopy()` bridges `PixelCopy.request(...)` to `suspendCancellableCoroutine` with no timeout in `VirtualDisplayCaptureCoordinator.kt:157-177`.
- None of the main `captureScreen()` call sites wrap platform capture in a timeout:
  - pre-turn capture: `AgentTurnRunner.kt:125-161`
  - post-action capture: `TurnExecutionPhaseRunner.kt:209-235`
  - post-approval refresh: `ToolRouter.kt:251-259`

**Execution path**
1. Agent/tool code calls `platform.captureScreen()`.
2. Accessibility mode can suspend forever waiting for `takeScreenshot(...)` or `takeScreenshotOfWindow(...)`.
3. VD live-preview mode can suspend forever waiting for `PixelCopy.request(...)`.
4. There is no outer timeout in the agent turn, tool router, or post-action observation path, so a lost callback can stall the turn until the whole coroutine is externally cancelled.

**What is true**
- The unbounded wait is real.
- A missing callback can wedge `captureScreen()` and therefore wedge the agent turn, post-action observation, or approval refresh.
- This is first-batch fix material.

**What is not true**
- The review says the PixelCopy path "`cont.resume()` without `cont.isActive` will throw on a cancelled continuation."
- This app uses coroutines `1.7.3` (`app/build.gradle.kts:72`). In that version, a late resume on a `CancelledContinuation` goes through the `CancelledContinuation.makeResumed()` path and returns without throwing. So the specific "cancellation crash" claim is false.

**What is still missing on cancellation**
- `captureFromPixelCopy()` allocates a `Bitmap` before suspension and does not register `invokeOnCancellation` cleanup in `VirtualDisplayCaptureCoordinator.kt:165-177`.
- So even though the late resume does not crash, cancellation still is not fully clean.

**Severity**
- The "Critical" priority is justified by impact in this codebase, but the rationale should be rewritten as "unbounded waits plus incomplete cancellation cleanup," not "unbounded waits plus guaranteed late-resume crash."

### Critical #2: Virtual-display gestures are not cancellation-safe
**Verdict:** REAL BUG.

**What the code does**
- `VirtualDisplayInputInjector.injectLongPress()` sends `ACTION_DOWN`, suspends in `delay(durationMs)`, then sends `ACTION_UP` in `VirtualDisplayInputInjector.kt:56-89`.
- `VirtualDisplayInputInjector.injectSwipe()` sends `ACTION_DOWN`, then loops over delayed `ACTION_MOVE`s, then sends `ACTION_UP` in `VirtualDisplayInputInjector.kt:92-145`.
- Neither method uses `try/finally`.
- Neither method sends best-effort `ACTION_CANCEL` or `ACTION_UP` on coroutine cancellation.
- `injectSwipe()` ignores every MOVE injection result in `VirtualDisplayInputInjector.kt:116-125` and returns success if the final `UP` succeeds.

**Why cancellation is a real path here**
- Session shutdown calls `agentRunner.shutdown()` and then immediately calls `platform.stop()` in `AgentSession.kt:477-517` and `SessionServices.kt:210-227`.
- `SessionAgentRunner.shutdown()` cancels the agent job but does not wait for in-flight platform work to unwind in `SessionAgentRunner.kt:183-195`.
- That means cancellation during the `delay(...)` windows in `injectLongPress()` and `injectSwipe()` is not theoretical.

**Impact**
- A cancelled long-press can leave the target UI in a pressed state until Android or the app self-recovers.
- A cancelled swipe can leave a drag stream unfinished.
- Ignored MOVE failures can already break the gesture before the code reports success.

**Severity**
- I agree this belongs in the first fix batch. If you want to be strict about naming, this is on the Critical/High boundary, but it is definitely not overstated as a real platform problem.

## High Findings

### High #1: The virtual-display stack has no single lifecycle owner
**Verdict:** REAL BUG.

**What the code does**
- `VirtualDisplayPlatform` stores live mutable session state in `displayId`, `imageReader`, binder-death listener, and subcontrollers in `VirtualDisplayPlatform.kt:52-100`.
- `start()`, `stop()`, `switchToLivePreview()`, `switchToImageReader()`, `captureScreen()`, `performAction()`, and `launchApp()` are exposed with no shared lifecycle arbiter in `VirtualDisplayPlatform.kt:102-392`.
- `VirtualDisplaySurfaceController` synchronizes only its own internal `state`, not the wider platform lifecycle in `VirtualDisplaySurfaceController.kt:31-99`.
- The class comment on `stop()` explicitly says it is "Not safe to call concurrently with captureScreen/performAction" in `VirtualDisplayPlatform.kt:147-150`.

**Execution paths that make this real**
- Viewer lifecycle can call `switchToLivePreview()` / `switchToImageReader()` asynchronously through `AgentServiceViewerBridge.kt:35-40` while the agent is capturing.
- Shutdown can cancel the agent and then call `platform.stop()` without waiting, via `AgentSession.kt:477-517`, `SessionAgentRunner.kt:183-195`, and `SessionServices.kt:210-227`.
- Binder death can happen asynchronously at any point, but the current listener only logs in `VirtualDisplayPlatform.kt:133-137`.

**Important nuance**
- Normal agent turns are mostly sequential. The app is not constantly doing arbitrary concurrent `captureScreen()` and `performAction()` calls.
- So the review's broad wording is slightly wider than the actual concurrency envelope.
- But the actual race set that does exist is enough to make this a real bug: stop/cancel, viewer surface switches, and binder death can all invalidate resources under in-flight work.

**Additional concrete evidence the review did not cite**
- `start()` itself has no rollback once it has created the VD and assigned `displayId` / `imageReader` in `VirtualDisplayPlatform.kt:130-139`. Cancellation after that point can leave a half-started platform alive.

### High #2: Shizuku binder death is not handled as a lifecycle transition
**Verdict:** REAL BUG.

**What the code does**
- The binder-death listener only logs in `VirtualDisplayPlatform.kt:133-137`.
- `stop()` never calls `shizuku.clearCachedProxies()` in `VirtualDisplayPlatform.kt:151-168`.
- `ShizukuClient.clearCachedProxies()` exists specifically to clear cached proxies in `ShizukuClient.kt:147-155`.
- `ShizukuServiceProxyProvider` caches display/input proxies indefinitely in `ShizukuServiceProxyProvider.kt:8-37`.
- `VirtualDisplayPlatform.start()` exits early whenever `displayId != Display.INVALID_DISPLAY` in `VirtualDisplayPlatform.kt:102-103`.

**Execution path**
1. Binder dies.
2. Platform only logs; it does not clear proxies, invalidate `displayId`, or transition to a broken state.
3. A later restart attempt can no-op because `displayId` still looks valid.
4. Later transport calls fail deep inside reflection/IPC instead of failing closed at the platform boundary.

**Severity**
- High is correct.

### High #3: Window selection is wrong under multi-window conditions
**Verdict:** REAL BUG, but the scope in the review needs correction.

**Accessibility side**
- `collectRootsOnActiveDisplay()` sorts windows by ascending `layer` in `AccessibilityPlatform.kt:263-269`.
- `captureAccessibilityTree()` then uses `roots.firstOrNull()?.windowId` in `AccessibilityPlatform.kt:154-157`.
- `AccessibilityScreenshotCapturer.takeScreenshotResult()` prefers `takeScreenshotOfWindow(windowId)` on Android U+ in `AccessibilityScreenshotCapturer.kt:48-57`.
- So on Android U+, when screenshots are enabled, the code can target the lowest-layer window for the screenshot instead of the topmost window.

**VD side**
- `VirtualDisplayWindowAccessor.getRootOnDisplay()` picks the first `TYPE_APPLICATION` window with no layer ordering in `VirtualDisplayWindowAccessor.kt:64-70`.
- That single-root accessor is used by:
  - `NodeActionPerformer` root lookup via `VirtualDisplayPlatform.kt:60-63`
  - `VirtualDisplayPlatform.getCurrentPackageName()` via `VirtualDisplayPlatform.kt:369-375`
- The VD screenshot path itself is full-display (`ImageReader` / `PixelCopy`), so the VD part is not primarily a screenshot bug.

**Why the scope correction matters**
- For accessibility mode, this is mostly a screenshot-selection bug, and only on Android U+ when screenshot capture is actually active.
- For VD mode, the bigger problem is action/root selection and current-package selection, not screenshot selection.
- That means the VD half can affect:
  - node action targeting under dialogs/popups
  - foreground package detection
  - privacy gating in `VirtualDisplayPlatform.captureScreen()`, which checks `getCurrentPackageName()` before deciding whether to mask the capture in `VirtualDisplayPlatform.kt:227-246`

**Severity**
- Still High.
- The improvement plan should explicitly cover VD action-root/current-package policy, not just screenshot/root coherence.

### High #4: Accessibility capture does not fail soft
**Verdict:** REAL BUG.

**What the code does**
- `AccessibilityPlatform.captureAccessibilityTree()` wraps root recycling in `finally`, but it does not catch failures from:
  - raw tree dumping
  - `Perceptor.snapshot(...)`
  - sanitized-tree serialization
  - capture-quality artifact storage
  in `AccessibilityPlatform.kt:178-230` and `AccessibilityPlatform.kt:446-470`.
- `captureScreen()` uses that result directly in `AccessibilityPlatform.kt:83-123`.
- The VD path explicitly catches and downgrades snapshot failures to an empty result in `VirtualDisplayCaptureCoordinator.kt:53-96`.

**Impact**
- A stale root, tree-dump failure, or perception failure can throw out of accessibility `captureScreen()`.
- This asymmetry versus the VD path is real.

**Severity**
- High is fair.

### High #5: Some platform calls report success when they may have failed
**Verdict:** REAL BUG.

**What the code does**
- `VirtualDisplayPlatform.launchApp()` simply delegates in `VirtualDisplayPlatform.kt:390-392`.
- `VirtualDisplayAppController.launchApp()` returns `ActionResult.Success(...)` after `shizuku.launchOnDisplay(...)` in `VirtualDisplayAppController.kt:73-80`.
- `ShizukuActivityLauncher.launchOnDisplay()` catches all exceptions and only logs in `ShizukuActivityLauncher.kt:13-27`.

**Extra scope note**
- `VirtualDisplayAppController.launchApp()` also does not reject `Display.INVALID_DISPLAY` early in `VirtualDisplayAppController.kt:33-84`.
- So if the platform is stopped or broken, the fallback intent path can still report success while having failed or launched onto the wrong display.

**Severity**
- High is correct.

## Medium Findings

### Medium #1: Virtual-display geometry becomes stale after rotation or display-size change
**Verdict:** REAL BUG, but lower urgency than the earlier phases.

**What the code does**
- `VirtualDisplayConfig.fromPhysicalDisplay()` snapshots `context.resources.displayMetrics` once in `VirtualDisplayConfig.kt:32-39`.
- `PlatformFactory` constructs that config once and passes it into `VirtualDisplayPlatform` in `PlatformFactory.kt:78-91`.
- The platform and viewer keep using that immutable config forever:
  - VD creation: `VirtualDisplayPlatform.kt:107-123`
  - display info: `VirtualDisplayPlatform.kt:378-383`
  - swipe clamping: `VirtualDisplayPlatform.kt:339-346`
  - tree sizing and screenshot sizing: `VirtualDisplayCaptureCoordinator.kt:73`, `VirtualDisplayCaptureCoordinator.kt:135-141`, `VirtualDisplayCaptureCoordinator.kt:165`
  - viewer touch remap: `VirtualDisplayViewerTouchHandler.kt:41-45`

**Assessment**
- The configuration is undeniably stale after a real geometry change.
- The review's own caveat is important: the VD is its own coordinate space, so agent actions inside the VD remain mostly self-consistent.
- The main impact is fidelity drift versus the real device, viewer mismatch, and initial sizing accuracy, not immediate action-coordinate corruption.

**Worth fixing?**
- Yes, but after lifecycle, callback, gesture, and window-selection fixes.

### Medium #2: Resource ownership has specific gaps
**Verdict:** REAL BUG, but the review is incomplete and one item duplicates a High issue.

**Verified parts**
- `AccessibilityPlatform.getCurrentPackageName()` leaks `rootInActiveWindow` because it never recycles it in `AccessibilityPlatform.kt:341-348`.
- Accessibility debug screenshots have no retention cap in `AccessibilityScreenshotCapturer.kt:189-203`.
- `clearCachedProxies()` is dead code in practice because nothing calls it; that is true, but it is really part of High #2.

**What the final review missed**
- `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` also leaks `AccessibilityWindowInfo` objects on a hot path in `VirtualDisplayPlatform.kt:428-442`.
- `performAction()` calls that method before many IME-sensitive actions in `VirtualDisplayPlatform.kt:290-302`.
- So the resource-ownership problem is broader than the final review says.

**Worth fixing?**
- Yes. The `getCurrentPackageName()` leak and the missed `isKeyboardVisibleOnMainDisplay()` leak are both worth fixing.

### Medium #3: Live-preview surface replacement is ignored
**Verdict:** OVERSTATED.

**What the code does**
- `VirtualDisplaySurfaceController.switchToLivePreview()` returns immediately if `state.mode == LIVE_PREVIEW` in `VirtualDisplaySurfaceController.kt:49-52`.
- It does not compare the incoming `SurfaceView` or underlying `Surface`.

**Why I am downgrading it**
- The bug shape exists in code.
- But the normal viewer lifecycle already calls `notifyViewerHidden()` on `surfaceDestroyed()` and on `onStop()`, through `VirtualDisplayViewerActivity.kt:52-57` and `VirtualDisplayViewerActivity.kt:83-88`.
- In the common recreate/destroy path, that usually flips the platform back to `IMAGE_READER`, so the next `notifyViewerVisible()` can switch cleanly.
- The bad case mostly requires unusual ordering or an already-bad state, such as failure to switch back to the reader surface.

**Worth fixing?**
- Yes, but only after the higher-impact items.

### Medium #4: The viewer shell fallback can block its caller thread
**Verdict:** REAL BUG.

**What the code does**
- Viewer touches start on the UI thread in `VirtualDisplayViewerActivity.kt:58-67`.
- They flow into `AgentServiceViewerBridge.onViewerTouch()` in `AgentServiceViewerBridge.kt:43-64`.
- If display-id injection is unavailable, `VirtualDisplayViewerTouchHandler.onViewerTouch()` falls back to `injectViaShell(...)` in `VirtualDisplayViewerTouchHandler.kt:47-64`.
- On `ACTION_UP`, that path calls `shizuku.executeShellCommand(...)` synchronously in `VirtualDisplayViewerTouchHandler.kt:100-127`.
- `ShizukuShellExecutor.execute()` blocks until process exit, with a polling `Thread.sleep(...)` loop and a 30-second timeout in `ShizukuShellExecutor.kt:13-75`.

**Scope**
- This only hits when `supportsDisplayIdInjection()` is false in `VirtualDisplayInputInjector.kt:210`.
- It also only matters in viewer Takeover mode.

**Worth fixing?**
- Yes, but it is clearly behind the earlier lifecycle/callback issues.

### Medium #5: Invalid scroll directions are normalized silently
**Verdict:** OVERSTATED.

**What the code does**
- `NodeActionPerformer.scrollActionIds()` falls back to `ACTION_SCROLL_FORWARD` on unknown strings in `NodeActionPerformer.kt:392-404`.

**Why I am downgrading it**
- Production tool input already validates scroll direction:
  - validation: `MobileActionTool.kt:155-167`
  - schema enum: `MobileActionTool.kt:297-301`
- The only obvious remaining path for bad directions is direct/debug construction, such as `DebugActionExecutor`.
- So the implementation detail is real, but the practical risk in the main agent path is much lower than a Medium robustness issue.

**Worth fixing?**
- Yes, but as a low-priority cleanup.

## Low Findings

### Low #1: Dead private helpers should be removed
**Verdict:** REAL BUG, cleanup only.

**What I verified**
- `AccessibilityGestureInjector.gestureDisplayId()` is declared in `AccessibilityGestureInjector.kt:153-157` and has no call sites.
- `NodeActionPerformer.performNodeActionAt()` is declared in `NodeActionPerformer.kt:251-273` and has no call sites.

**Assessment**
- The review is correct. This is dead code, but not a runtime robustness defect.

### Low #2: `DISPLAY_FLAGS` needs documentation
**Verdict:** OVERSTATED.

**What I verified**
- `DISPLAY_FLAGS` is an opaque raw bitmask in `VirtualDisplayPlatform.kt:46`.

**Assessment**
- This is true as a readability/documentation note.
- It is not a robustness bug.

## Verified Non-Issues

### Append-mode cursor placement in `setTextOnNode()`
**Review status:** Correct non-issue.

**Why**
- `setTextOnNode()` builds the `combined` string from the pre-action text/selection snapshot in `NodeActionPerformer.kt:182-204`.
- The cursor placement logic in `NodeActionPerformer.kt:218-233` is computing "cursor after inserted text," which is logically anchored to that same insertion point.
- It does not need a refreshed node to know where the inserted text should end.
- Existing unit tests in `NodeActionPerformerTest.kt:371-460` cover append/hint/no-hint cases, and the test class passed.

### Node-recycling in `AccessibilityNodeFinder`
**Review status:** Correct non-issue.

**Why**
- `findActionableNodeAtLocation()` recycles non-winning candidates and non-matching traversed children in `AccessibilityNodeFinder.kt:44-80`.
- `findEditableWithFocus()` correctly transfers ownership when returning a deeper descendant in `AccessibilityNodeFinder.kt:100-118`.
- `findNodeAtLocation()` correctly recycles unsuccessful DFS branches in `AccessibilityNodeFinder.kt:140-206`.

### Root/window lifetime in `collectRootsOnActiveDisplay()`
**Review status:** Correct non-issue.

**Why**
- `collectRootsOnActiveDisplay()` and `VirtualDisplayWindowAccessor` obtain `window.root` while the window object is still live, then recycle the windows later in `finally`:
  - `AccessibilityPlatform.kt:261-279`
  - `VirtualDisplayWindowAccessor.kt:65-76`
  - `VirtualDisplayWindowAccessor.kt:86-100`
- The returned roots are then owned and recycled separately by their callers:
  - `AccessibilityPlatform.kt:228-230`
  - `VirtualDisplayCaptureCoordinator.kt:93-95`
  - `NodeActionPerformer.kt:275-281`
- I do not see a code-side use-after-recycle problem here.

### `OverlayTouchGate` timeout handling
**Review status:** Correct non-issue.

**Why**
- `AccessibilityGestureInjector.dispatchGesture()` acquires the pass-through token before dispatch and closes it in a `finally` block in `AccessibilityGestureInjector.kt:104-146`.
- That is the right ownership pattern for `OverlayTouchGate`.

## Additional Real Problems Missing From the Final Review

### 1. `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` leaks window objects
**Severity:** Medium.

**Evidence**
- `performAction()` calls `isKeyboardVisibleOnMainDisplay()` before many IME-sensitive actions in `VirtualDisplayPlatform.kt:295-302`.
- `isKeyboardVisibleOnMainDisplay()` obtains windows via `getWindowsOnAllDisplays()` or `service.windows` and never recycles them in `VirtualDisplayPlatform.kt:428-442`.

**Why it matters**
- This is a hot path for clicks/taps/long-presses/text actions in VD mode.
- The final review caught the accessibility `rootInActiveWindow` leak but missed this parallel VD leak.

### 2. `VirtualDisplayPlatform.start()` has no rollback after partial startup
**Severity:** High.

**Evidence**
- `start()` assigns `displayId` and `imageReader` in `VirtualDisplayPlatform.kt:130-131`, then registers binder-death listener and suspends in `delay(...)` in `VirtualDisplayPlatform.kt:133-139`.
- There is no `try/finally` rollback around the startup sequence.
- `AgentSession.initializeForFirstTask()` catches the startup exception and reports failure, but does not call `platform.stop()` in `AgentSession.kt:285-300`.

**Why it matters**
- Cancellation or exception after the VD is created can leave a live display, listener, and non-invalid `displayId` behind while the session believes startup failed.
- The next `start()` can no-op because `displayId` is already set.
- This is a concrete half-started-state bug, not just a design concern.

### 3. VD a11y capture swallows coroutine cancellation
**Severity:** Medium.

**Evidence**
- `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` catches `Exception` and converts it to `A11yCaptureResult(emptyList(), null, null)` in `VirtualDisplayCaptureCoordinator.kt:90-92`.
- In Kotlin, `CancellationException` is an `Exception`, so cancellation is also swallowed here.

**Why it matters**
- An interrupted capture can turn into an empty-tree result instead of promptly propagating cancellation.
- That can produce misleading empty observations or defer shutdown semantics.

## Recommended Edits To `improvement_plan.md`
- Keep Phase 1, Phase 2, Phase 3, and Phase 4 at the front of the queue.
- Rewrite Phase 2's PixelCopy rationale:
  - keep the timeout requirement
  - remove the claim that late resume necessarily crashes
  - add explicit cancellation cleanup for the preallocated `Bitmap`
- Expand Phase 4 so it covers VD single-root selection for actions and current-package/privacy gating, not just screenshot/root coherence.
- Add the missed `isKeyboardVisibleOnMainDisplay()` window leak to Phase 7.
- Add explicit startup rollback to Phase 1:
  - if `start()` is cancelled or fails after resource creation, release the VD, close the reader, remove the binder listener, and reset state
- Add a note that `captureA11yTreeWithArtifacts()` should rethrow `CancellationException` instead of swallowing it as an empty snapshot.

## Bottom Line
- The final review is directionally strong. The two first-batch problems are real.
- The main factual correction is the PixelCopy cancellation-crash claim: the hang is real, the claimed coroutines crash is not.
- The High section is mostly sound, but High #3 should be reframed to include VD action/current-package/privacy effects.
- The Medium section should be pruned:
  - keep resource leaks
  - keep viewer-thread shell blocking
  - defer geometry churn
  - downgrade surface replacement and invalid scroll-direction handling
- The final review should add the missed window leak, startup rollback gap, and cancellation-swallow bug before implementation starts.

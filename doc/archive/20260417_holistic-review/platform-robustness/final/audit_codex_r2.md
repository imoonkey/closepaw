# Platform Robustness Audit (Codex R2)

## Method
- Read `doc/todo/holistic-review/platform-robustness/final/review.md`, `doc/todo/holistic-review/platform-robustness/final/improvement_plan.md`, and `doc/todo/holistic-review/platform-robustness/final/audit_codex.md`.
- Verified each documented finding against the current source under `app/src/main/kotlin/com/moonkey/androidagent/platform/` and `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/`.
- Traced the live call paths through capture, action dispatch, shutdown, viewer lifecycle, and app launch code.
- This pass was source-trace only. No platform code was changed. No tests were run in this pass.

## Executive Summary
- The previous Codex audit corrections were mostly applied correctly in the docs.
- The largest corrections that were applied correctly are:
  - the PixelCopy late-resume crash claim was removed and replaced with the real cancellation-cleanup issue
  - the missed startup-rollback bug was added
  - the missed `isKeyboardVisibleOnMainDisplay()` window leak was added
  - the missed VD cancellation-swallow bug was added
  - invalid scroll-direction handling was downgraded from Medium to Low
- The review set is still not fully accurate:
  - High #3 is still under-scoped on the accessibility side. The bug is not only screenshot selection. Accessibility capture uses all window roots, while accessibility actions and privacy gating use `rootInActiveWindow`, so dialogs/popups can expose background elements or blocked-app content that the action path cannot truthfully target.
  - High #5 is real, but High is too aggressive. Medium is the right severity.
  - Medium #3 remains overstated. It is a real edge-case bug, but Low severity.
  - The Low cleanup/documentation items are not robustness defects.
- One real issue is still missing from all three docs:
  - `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` keeps `Perceptor.snapshot()` and sanitized-tree serialization on `Dispatchers.Main`, unlike the accessibility path. That is a real main-thread robustness problem.

## Previous Audit Corrections

### Applied Correctly
- The PixelCopy rationale was corrected. The current review no longer claims that late resume on a cancelled continuation will crash. The source still shows the real issue: unbounded wait plus missing bitmap cleanup in `VirtualDisplayCaptureCoordinator.kt:157-196`.
- The startup rollback gap was correctly added to High #1 and Phase 1. `VirtualDisplayPlatform.start()` still assigns `displayId` and `imageReader`, then registers binder death and suspends with `delay(...)`, with no rollback path in `VirtualDisplayPlatform.kt:102-145`.
- The missed `isKeyboardVisibleOnMainDisplay()` leak was correctly added to Medium #2 and Phase 7. The code still allocates windows without recycling them in `VirtualDisplayPlatform.kt:428-442`.
- The missed cancellation-swallow bug was correctly added as High #5 and Phase 6. `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` still catches `Exception` and therefore swallows `CancellationException` in `VirtualDisplayCaptureCoordinator.kt:53-96`.
- Invalid scroll-direction handling was correctly downgraded out of the Medium section.

### Partially Applied Or Still Off
- High #3 was only partially corrected. The prior audit was right to expand the VD scope beyond screenshots, but it still under-called the accessibility-side mismatch. Accessibility capture uses all roots from `collectRootsOnActiveDisplay()` in `AccessibilityPlatform.kt:144-230`, while actions and package gating use `service.rootInActiveWindow` in `AccessibilityPlatform.kt:55` and `AccessibilityPlatform.kt:341-348`.
- Medium #3 was softened, but the severity is still too high. The code bug exists in `VirtualDisplaySurfaceController.kt:49-77`, but the common lifecycle already calls `notifyViewerHidden()` on `surfaceDestroyed()` and `onStop()` in `VirtualDisplayViewerActivity.kt:52-57` and `VirtualDisplayViewerActivity.kt:83-88`.
- High #5 was added correctly, but the severity was raised too far. The fix is necessary, but the impact is still Medium, not High.

## Finding-By-Finding Verification

### Critical #1. Callback-driven capture paths are unbounded
- Verdict: REAL BUG
- Audited severity: Critical
- Evidence:
  - `AccessibilityScreenshotCapturer.takeDisplayScreenshot()` uses `suspendCancellableCoroutine` with no timeout in `AccessibilityScreenshotCapturer.kt:60-87`.
  - `AccessibilityScreenshotCapturer.takeWindowScreenshot()` does the same in `AccessibilityScreenshotCapturer.kt:90-121`.
  - `VirtualDisplayCaptureCoordinator.captureFromPixelCopy()` does the same for `PixelCopy.request(...)` in `VirtualDisplayCaptureCoordinator.kt:157-177`.
  - The PixelCopy path allocates a bitmap before suspension and does not register cancellation cleanup in `VirtualDisplayCaptureCoordinator.kt:165-177`.
  - Main call sites use `platform.captureScreen()` directly with no timeout in `AgentTurnRunner.kt:128-165`, `TurnExecutionPhaseRunner.kt:209-235`, and `ToolRouter.kt:251-259`.
- Assessment:
  - The prior Codex correction was applied correctly: this is an unbounded-wait bug, not a proven coroutines late-resume crash on the current dependency version.
  - The bitmap-cleanup gap is real and still present.
  - Critical remains the right severity because a lost framework callback can stall the full turn pipeline.

### Critical #2. Virtual-display gestures are not cancellation-safe
- Verdict: REAL BUG
- Audited severity: Critical or very high High; the current Critical label is still defensible
- Evidence:
  - `VirtualDisplayInputInjector.injectLongPress()` sends `ACTION_DOWN`, suspends in `delay(durationMs)`, then sends `ACTION_UP`, with no `try/finally` and no cancel cleanup in `VirtualDisplayInputInjector.kt:56-90`.
  - `VirtualDisplayInputInjector.injectSwipe()` sends `ACTION_DOWN`, loops over delayed MOVE events, ignores MOVE failures, then sends `ACTION_UP`, with no `try/finally` and no cancel cleanup in `VirtualDisplayInputInjector.kt:92-145`.
  - Session shutdown cancels the agent job before platform stop completes in `AgentSession.kt:499-501`, `SessionAgentRunner.kt:182-193`, and `SessionServices.kt:223-227`.
- Assessment:
  - Cancellation during the delay windows is real in the current call graph.
  - The partial-gesture and ignored-MOVE-failure claims are accurate.
  - I would accept either Critical or High here; I do not consider the current Critical label materially misleading.

### High #1. The virtual-display stack has no single lifecycle owner
- Verdict: REAL BUG
- Audited severity: High
- Evidence:
  - Mutable lifecycle state is spread across `displayId`, `imageReader`, `binderDeadListener`, and the surface controller in `VirtualDisplayPlatform.kt:52-100`.
  - `start()`, `stop()`, `switchToLivePreview()`, `switchToImageReader()`, `captureScreen()`, `performAction()`, and `launchApp()` run with no shared lifecycle arbiter in `VirtualDisplayPlatform.kt:102-444`.
  - `stop()` explicitly documents that it is not safe concurrently with capture/action in `VirtualDisplayPlatform.kt:147-150`.
  - `start()` has no rollback after `displayId` and `imageReader` are assigned in `VirtualDisplayPlatform.kt:130-139`.
  - Viewer lifecycle can switch surfaces asynchronously via `AgentServiceViewerBridge.kt:35-40` and `VirtualDisplayViewerActivity.kt:52-57`.
- Assessment:
  - This finding is real.
  - The broad concurrency story in the review is slightly wider than the normal turn loop, but the concrete races are enough: stop/cancel, viewer surface changes, and binder death.
  - The startup rollback correction from the previous audit was applied correctly.

### High #2. Shizuku binder death is not handled as a lifecycle transition
- Verdict: REAL BUG
- Audited severity: High
- Evidence:
  - Binder death only logs in `VirtualDisplayPlatform.kt:133-137`.
  - `stop()` never calls `shizuku.clearCachedProxies()` in `VirtualDisplayPlatform.kt:151-168`.
  - `ShizukuClient.clearCachedProxies()` exists in `ShizukuClient.kt:151-155`.
  - Shizuku proxies are cached in `ShizukuServiceProxyProvider.kt:8-37`.
  - `start()` no-ops whenever `displayId` is already set in `VirtualDisplayPlatform.kt:102-103`.
- Assessment:
  - The review and plan now describe this correctly.
  - High is the right severity because binder loss can strand the session in a false-running state and force failures to surface deep in transport/reflection code.

### High #3. Window selection is wrong under multi-window conditions
- Verdict: REAL BUG
- Audited severity: High
- Evidence:
  - Accessibility capture collects all non-overlay/non-IME roots, sorted by ascending layer, in `AccessibilityPlatform.kt:248-279`.
  - Accessibility screenshot capture then uses `roots.firstOrNull()?.windowId` in `AccessibilityPlatform.kt:154-157`, which points at the lowest-layer window.
  - Accessibility actions are not multi-root. `AccessibilityPlatform` builds `NodeActionPerformer` from `service.rootInActiveWindow` in `AccessibilityPlatform.kt:55`.
  - Accessibility package gating also uses `service.rootInActiveWindow` in `AccessibilityPlatform.kt:341-348`.
  - VD single-root selection still uses first `TYPE_APPLICATION` with no layer ordering in `VirtualDisplayWindowAccessor.kt:64-76`.
  - VD actions and current-package lookup both depend on that single-root accessor in `VirtualDisplayPlatform.kt:63`, `VirtualDisplayPlatform.kt:369-375`.
- Assessment:
  - The finding is real.
  - The prior Codex scope correction was only partially applied. The accessibility-side problem is broader than "mostly a screenshot-selection bug."
  - On accessibility:
    - capture sees all windows
    - actions use one root
    - privacy gating uses one root
  - That means dialogs/popups can create tree/action/package mismatch even before the Android U window-screenshot bug is considered.
  - Example: a blocked app behind an allowed system dialog can pass `getCurrentPackageName()`, then still have its background nodes included by `captureAccessibilityTree()`.
  - High remains correct.

### High #4. Accessibility capture does not fail soft
- Verdict: REAL BUG
- Audited severity: High
- Evidence:
  - `AccessibilityPlatform.captureAccessibilityTree()` does not catch failures from raw tree dump, `Perceptor.snapshot(...)`, sanitized-tree serialization, or capture-quality artifact work in `AccessibilityPlatform.kt:178-230` and `AccessibilityPlatform.kt:446-470`.
  - `captureScreen()` uses that result directly in `AccessibilityPlatform.kt:62-124`.
  - The VD path does catch and downgrade tree/snapshot failures in `VirtualDisplayCaptureCoordinator.kt:53-96`.
- Assessment:
  - The asymmetry is real.
  - A single stale-root or perception failure can still abort accessibility `captureScreen()`.
  - High is the right severity because this is on the core observation path.

### High #5. VD accessibility capture swallows coroutine cancellation
- Verdict: OVERSTATED
- Audited severity: Medium
- Evidence:
  - `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` catches `Exception` and converts it to `A11yCaptureResult(emptyList(), null, null)` in `VirtualDisplayCaptureCoordinator.kt:90-92`.
- Assessment:
  - The bug is real.
  - The fix in the plan is correct: rethrow `CancellationException`.
  - The current High label is too strong. This does not wedge the platform or corrupt lifecycle state; it turns cancellation into a misleading empty capture and can delay clean shutdown semantics. That is Medium.

### High #6. Some platform calls report success when they may have failed
- Verdict: REAL BUG
- Audited severity: High
- Evidence:
  - `VirtualDisplayPlatform.launchApp()` simply delegates in `VirtualDisplayPlatform.kt:390-391`.
  - `VirtualDisplayAppController.launchApp()` returns success after `shizuku.launchOnDisplay(...)` in `VirtualDisplayAppController.kt:73-80`.
  - `ShizukuActivityLauncher.launchOnDisplay()` catches and only logs all exceptions in `ShizukuActivityLauncher.kt:13-27`.
  - `VirtualDisplayAppController.launchApp()` also accepts whatever `displayIdProvider()` returns and does not reject `Display.INVALID_DISPLAY` up front in `VirtualDisplayAppController.kt:33-84`.
- Assessment:
  - The claim is real.
  - The improvement plan is correct to add both truthful error propagation and invalid-display guards.
  - High is accurate.

### Medium #1. Virtual-display geometry becomes stale after rotation or display-size change
- Verdict: REAL BUG
- Audited severity: Medium
- Evidence:
  - `VirtualDisplayConfig.fromPhysicalDisplay()` snapshots `context.resources.displayMetrics` once in `VirtualDisplayConfig.kt:32-39`.
  - `PlatformFactory` constructs that config once in `PlatformFactory.kt:78-91`.
  - The VD stack then keeps using that immutable config in `VirtualDisplayPlatform.kt:107-123`, `VirtualDisplayPlatform.kt:339-362`, `VirtualDisplayPlatform.kt:378-383`, `VirtualDisplayCaptureCoordinator.kt:73`, `VirtualDisplayCaptureCoordinator.kt:135-165`, and `VirtualDisplayViewerTouchHandler.kt:41-45`.
- Assessment:
  - The underlying problem is real.
  - The current review text is materially better than the prior version because it now limits the main impact to viewer fidelity and initial sizing, not general in-VD coordinate drift.
  - Medium is acceptable.

### Medium #2. Resource ownership has specific gaps
- Verdict: REAL BUG
- Audited severity: Medium
- Evidence:
  - `AccessibilityPlatform.getCurrentPackageName()` still leaks `rootInActiveWindow` by not recycling it in `AccessibilityPlatform.kt:341-348`.
  - `VirtualDisplayPlatform.isKeyboardVisibleOnMainDisplay()` still leaks `AccessibilityWindowInfo` objects in `VirtualDisplayPlatform.kt:428-442`.
  - Accessibility debug screenshots still have no retention cap in `AccessibilityScreenshotCapturer.kt:189-203`.
  - `clearCachedProxies()` is still dead code in practice; no caller exists outside `ShizukuClient.kt:151-155`.
- Assessment:
  - The resource-leak parts are real.
  - The previous audit correction adding the VD window leak was applied correctly.
  - One sub-item is really part of High #2, not an independent Medium issue: stale proxy cleanup is a lifecycle problem first, cleanup problem second.

### Medium #3. Live-preview surface replacement is ignored
- Verdict: OVERSTATED
- Audited severity: Low
- Evidence:
  - `VirtualDisplaySurfaceController.switchToLivePreview()` returns immediately whenever the current mode is already `LIVE_PREVIEW`, without comparing the new surface or `SurfaceView` identity in `VirtualDisplaySurfaceController.kt:49-52`.
  - The normal viewer lifecycle does call `notifyViewerHidden()` on `surfaceDestroyed()` and `onStop()` in `VirtualDisplayViewerActivity.kt:52-57` and `VirtualDisplayViewerActivity.kt:83-88`.
- Assessment:
  - The bug shape exists.
  - The review's softened wording is better than the original audit target, but Medium is still too high.
  - This is a Low edge-case bug because it generally needs unusual callback ordering or a prior switch-back failure to become user-visible.

### Medium #4. The viewer shell fallback can block its caller thread
- Verdict: REAL BUG
- Audited severity: Medium
- Evidence:
  - Viewer touch forwarding starts on the activity touch listener in `VirtualDisplayViewerActivity.kt:58-68`.
  - It flows synchronously through `AgentServiceViewerBridge.onViewerTouch()` in `AgentServiceViewerBridge.kt:43-64`.
  - The fallback path uses `shizuku.executeShellCommand(...)` directly in `VirtualDisplayViewerTouchHandler.kt:100-127`.
  - `ShizukuShellExecutor.execute()` blocks up to 30 seconds with polling `Thread.sleep(...)` in `ShizukuShellExecutor.kt:13-75`.
- Assessment:
  - The claim is real.
  - The review's scope note is accurate: this only matters on the fallback path, but on that path it can freeze the caller thread.
  - Medium is correct.

### Low #1. Dead private helpers should be removed
- Verdict: OVERSTATED
- Audited severity: Cleanup only
- Evidence:
  - `AccessibilityGestureInjector.gestureDisplayId()` has no call sites outside its declaration in `AccessibilityGestureInjector.kt:153-157`.
  - `NodeActionPerformer.performNodeActionAt()` has no call sites outside its declaration in `NodeActionPerformer.kt:251-273`.
- Assessment:
  - This is true dead code.
  - It is not a platform robustness defect. The review is best read as a cleanup note, not as a real runtime finding.

### Low #2. `DISPLAY_FLAGS` needs documentation
- Verdict: OVERSTATED
- Audited severity: Documentation only
- Evidence:
  - `DISPLAY_FLAGS` is an undocumented raw bitmask in `VirtualDisplayPlatform.kt:46`.
- Assessment:
  - This is a legitimate readability note.
  - It is not a robustness bug.

### Low #3. Invalid scroll directions are normalized silently
- Verdict: OVERSTATED
- Audited severity: Practically unreachable in the current app call graph
- Evidence:
  - `NodeActionPerformer.scrollActionIds()` still falls back to `ACTION_SCROLL_FORWARD` on unknown strings in `NodeActionPerformer.kt:393-404`.
  - `MobileActionTool` validates direction in `MobileActionTool.kt:155-167` and the schema enum in `MobileActionTool.kt:297-301`.
  - `DebugActionExecutor` also validates direction before constructing `UIAction.ScrollNodeAt` in `DebugActionExecutor.kt:193-198`.
  - The only in-tree `UIAction.ScrollNodeAt(...)` call sites are validated ones in `ScrollExecutor.kt:69` and `DebugActionExecutor.kt:198`.
- Assessment:
  - The lower-level fallback behavior exists.
  - The review still slightly overstates reachability. I did not find a live production or debug call path that can currently feed an invalid direction into `NodeActionPerformer`.
  - This is a latent API cleanliness issue, not a present robustness bug.

## Verified Non-Issues

### Append-mode cursor placement in `setTextOnNode()`
- Status: Confirmed non-issue
- Evidence:
  - The code still computes the combined text coherently in `NodeActionPerformer.kt:182-248`.
  - The existing unit coverage for append/hint cases remains in `NodeActionPerformerTest.kt:371-460`.
- Assessment:
  - I do not see a current code-path bug here.

### Node recycling in `AccessibilityNodeFinder`
- Status: Confirmed non-issue
- Evidence:
  - The DFS ownership pattern in `AccessibilityNodeFinder.kt:44-80`, `AccessibilityNodeFinder.kt:100-118`, and `AccessibilityNodeFinder.kt:140-206` is still correct.

### Root/window lifetime in `collectRootsOnActiveDisplay()`
- Status: Confirmed non-issue
- Evidence:
  - Windows are recycled after extracting their roots in `AccessibilityPlatform.kt:261-279` and `VirtualDisplayWindowAccessor.kt:64-100`.
  - Returned roots are then recycled by their callers in `AccessibilityPlatform.kt:228-230`, `VirtualDisplayCaptureCoordinator.kt:93-95`, and `NodeActionPerformer.kt:275-281`.

### `OverlayTouchGate` timeout handling
- Status: Confirmed non-issue
- Evidence:
  - `AccessibilityGestureInjector.dispatchGesture()` still acquires the token before dispatch and always closes it in `finally` in `AccessibilityGestureInjector.kt:104-147`.

## Real Problems Still Missing From The Review Set

### 1. VD a11y capture does heavy work on `Dispatchers.Main`
- Severity: Medium
- Evidence:
  - `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` wraps the full flow in `withContext(Dispatchers.Main)` in `VirtualDisplayCaptureCoordinator.kt:53-96`.
  - Inside that Main block it runs `Perceptor.snapshot(...)` in `VirtualDisplayCaptureCoordinator.kt:73`.
  - It also runs `Perceptor.toPromptJson(snapshot)` and trace artifact submission back on Main in `VirtualDisplayCaptureCoordinator.kt:75-82`.
  - The accessibility path does not do this; it limits Main-thread work to display/window collection in `AccessibilityPlatform.kt:145-151`, then performs perception work outside the Main block in `AccessibilityPlatform.kt:178-227`.
- Why it matters:
  - Large-tree perception and sanitized-tree serialization can block the service/viewer main thread.
  - This violates the project's own main-safe rule and increases the odds of jank or delayed framework callbacks in VD mode.

## Bottom Line
- The review and improvement plan are now directionally strong, and most of the previous Codex corrections were incorporated correctly.
- The most important remaining accuracy issue is High #3: the accessibility-side multi-window mismatch is still broader than the docs say, because capture, actions, and privacy gating do not use the same window/root policy.
- The most important remaining severity issue is High #5, which should be Medium.
- The most important still-missed source bug is the Main-thread perception work in `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()`.

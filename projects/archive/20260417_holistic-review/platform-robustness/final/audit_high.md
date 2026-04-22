# Audit of High Findings #4-#7

Independent verification of review.md findings against actual source code.

---

## High #4: VD geometry becomes stale after rotation or display-size change

**Review claim**: `VirtualDisplayConfig` snapshots app metrics once at startup and the VD stack keeps using them forever. Coordinate mapping drifts after rotation.

### Evidence

**How config is sourced** (`PlatformFactory.kt:78`):
```kotlin
val displayConfig = VirtualDisplayConfig.fromPhysicalDisplay(service)
```

**What `fromPhysicalDisplay` reads** (`VirtualDisplayConfig.kt:32-39`):
```kotlin
fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig {
    val dm = context.resources.displayMetrics
    return VirtualDisplayConfig(
        width = dm.widthPixels,
        height = dm.heightPixels,
        densityDpi = dm.densityDpi,
        density = dm.density
    )
}
```

This uses `context.resources.displayMetrics`, which is the **app content area** metrics, not real display metrics. The config is an immutable `data class` with no update mechanism. It is created once in `PlatformFactory.create()` and passed to `VirtualDisplayPlatform` as a `val` constructor parameter. The VD is created with these dimensions in `start()` and they are never re-read.

**Downstream usage of the frozen config**:
- `VirtualDisplayPlatform.getDisplayInfo()` (line 378-384): returns `config.width`/`config.height` -- frozen.
- `VirtualDisplayPlatform.performSwipe()` (line 340-341): clamps coordinates to `config.width`/`config.height` -- frozen.
- `VirtualDisplayCaptureCoordinator.captureFromImageReader()` (line 135-136): crops bitmap to `config.width`/`config.height` -- frozen.
- `VirtualDisplayCaptureCoordinator.captureFromPixelCopy()` (line 165): creates bitmap with `config.width`/`config.height` -- frozen.
- `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` (line 73): passes `config.width`/`config.height` to `Perceptor.snapshot()` -- frozen.
- `VirtualDisplayViewerTouchHandler` uses `config` for touch coordinate scaling -- frozen.
- The `ImageReader` is created once in `start()` with `config.width`/`config.height` -- frozen.

**Is there a display change listener?** No. There is no `ComponentCallbacks2`, `onConfigurationChanged`, `DisplayListener`, or any mechanism to detect rotation. The `binderDeadListener` only handles Shizuku death, not display changes.

**Would rotation actually break things?** The virtual display itself has a fixed geometry independent of the physical display. Apps running on the VD render at the VD's fixed resolution. However:
1. The review's claim about "coordinate mapping drifts" is partially misleading -- the VD has its own coordinate space. Touch injection targets the VD's coordinate space, which does not drift relative to the VD itself.
2. The real issue is different: if the physical display rotates and the session outlives the rotation, the VD dimensions no longer match the physical screen. This means the viewer's touch scaling (`viewerTouchHandler`) will be distorted, and the user sees a landscape VD on a portrait screen (or vice versa). For the agent loop itself (non-viewer), this is less of an issue because the agent drives actions in VD coordinates.

**VERDICT: OVERSTATED**

The finding is partially true -- the config is indeed frozen and uses the wrong metric source (`displayMetrics` vs real display metrics). There is no rotation handler. However, the severity is overstated. The VD is a self-contained coordinate space: coordinate mapping within the VD does not "drift" because the VD's own geometry is stable. The real impact is limited to: (a) initial dimensions may exclude nav bar area since `displayMetrics` is used instead of real metrics, and (b) if the user rotates the physical device, the viewer experience degrades but the agent loop's coordinate-based actions within the VD remain consistent. This is a minor usability issue for the viewer, not a "coordinate mapping drift" bug in the agent path.

---

## High #5: Accessibility capture does not fail soft

**Review claim**: The accessibility path does not downgrade tree dump, trace, or `Perceptor.snapshot()` failures into a safe platform-level result. Error handling is asymmetrical between accessibility and VD paths.

### Evidence

**AccessibilityPlatform.captureAccessibilityTree()** (`AccessibilityPlatform.kt:144-231`):

The function has a `try/finally` block (lines 178-231). The `try` block calls:
1. `A11yTreeDumper.dump(it)` -- inside trace-conditional (line 180)
2. `Perceptor.snapshot(roots, ...)` (line 192) -- the main perception call
3. `Perceptor.toPromptJson(snapshot)` -- inside trace-conditional (line 200)

If `Perceptor.snapshot()` throws, the exception propagates uncaught out of `captureAccessibilityTree()`, up through `captureScreen()`, and out of the platform boundary. There is **no try/catch around `Perceptor.snapshot()` or the tree dump** in the accessibility path. The only error handling is the `finally` block that recycles roots.

**VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()** (`VirtualDisplayCaptureCoordinator.kt:53-97`):

In contrast, the VD path wraps the entire capture in a try/catch (line 90-91):
```kotlin
} catch (e: Exception) {
    Log.w(TAG, "Perceptor.snapshot failed", e)
    A11yCaptureResult(emptyList(), null, null)
}
```

This gracefully returns an empty result on any Perceptor failure.

**The asymmetry is real**: AccessibilityPlatform lets Perceptor exceptions propagate; VirtualDisplayCaptureCoordinator catches them and returns empty. Same perception code, different error handling guarantees.

**Also**: `collectRootsOnActiveDisplay()` (line 248-280) does have a try/catch for the `service.windows` call (line 249-253), so the window enumeration itself is guarded. But the `Perceptor.snapshot` call within `captureAccessibilityTree` is NOT guarded.

**VERDICT: REAL BUG**

This is a genuine asymmetry. A `Perceptor.snapshot()` failure (e.g., from a stale root node throwing during traversal) will crash the accessibility capture path but not the VD capture path. The fix is straightforward: wrap the Perceptor call in a try/catch like the VD path does.

---

## High #6: Resource ownership is inconsistent

**Review claim**: Three sub-claims: (a) temporary roots and windows are not consistently recycled, (b) accessibility debug screenshots have no retention cap, (c) stale binder proxies survive beyond session lifetime.

### Evidence

**(a) Root and window recycling:**

**Windows**: `AccessibilityPlatform.collectRootsOnActiveDisplay()` (lines 273-278) recycles windows in a `finally` block via `runCatching { window.recycle() }`. `VirtualDisplayWindowAccessor.getRootOnDisplay()` (lines 72-76) and `getRootsOnDisplay()` (lines 95-100) both recycle windows in `finally`. Windows are consistently recycled on both paths.

**Roots**: `AccessibilityPlatform.captureAccessibilityTree()` (line 229) recycles roots in `finally`. `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` (line 94) recycles roots in `finally`. `NodeActionPerformer.withRoot()` (lines 275-282) recycles the root in `finally`. `VirtualDisplayPlatform.getCurrentPackageName()` (lines 370-376) recycles the root in `finally`.

**However**: `AccessibilityPlatform.getCurrentPackageName()` (lines 342-347):
```kotlin
return try {
    service.rootInActiveWindow?.packageName?.toString()
} catch (e: Exception) {
    Log.w(TAG, "Failed to get package name", e)
    null
}
```
This acquires `rootInActiveWindow` but **never recycles it**. This is a real leak. Each call to `getCurrentPackageName()` on the accessibility path leaks one `AccessibilityNodeInfo`. This is called at the top of every `captureScreen()` invocation.

Compare with the VD path (`VirtualDisplayPlatform.getCurrentPackageName()`, line 370-376) which correctly recycles in `finally`.

**(b) Debug screenshot retention:**

**VD path** (`VirtualDisplayScreenshotProcessor.kt:75-81`):
```kotlin
val files = debugDir.listFiles { _, name -> name.startsWith("vd_screenshot_") }
if (files != null && files.size >= 20) {
    files.sortBy { it.lastModified() }
    for (i in 0..(files.size - 20)) {
        files[i].delete()
    }
}
```
The VD path caps debug screenshots at 20 files.

**Accessibility path** (`AccessibilityScreenshotCapturer.kt:189-203`):
```kotlin
private fun persistDebugScreenshot(bytes: ByteArray, width: Int, height: Int) {
    val dir = service.getExternalFilesDir("debug-output") ?: return
    if (!dir.exists() && !dir.mkdirs()) { ... }
    val filename = "llm_screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg"
    val file = File(dir, filename)
    try {
        file.outputStream().use { it.write(bytes) }
    } catch (e: Exception) { ... }
}
```
The accessibility path writes debug screenshots with **no retention cap** and no cleanup. Files accumulate without bound.

Note: Both debug persistence paths are gated on `config.debugMode` / `sessionConfig.debugMode`, so this only applies when debug mode is enabled. In normal operation, debug screenshots are not written.

**(c) Stale binder proxies:**

`ShizukuClient.clearCachedProxies()` exists (line 151-155) but is **never called** in `VirtualDisplayPlatform.stop()` (lines 151-169). The `stop()` method releases the virtual display and closes the ImageReader, but does not call `shizuku.clearCachedProxies()`. The binder-death listener only logs (line 134-136) and does not clear proxies either.

**VERDICT: REAL BUG (partially)**

- Sub-claim (a) is **partially true**. Most roots and windows are properly recycled. The one genuine leak is `AccessibilityPlatform.getCurrentPackageName()` which does not recycle the `rootInActiveWindow` node. This is a real per-turn leak.
- Sub-claim (b) is **true but low severity**. The accessibility debug screenshot path has no retention cap, while the VD path caps at 20. However, this only affects debug mode, which is not the normal operating mode.
- Sub-claim (c) is **true**. `clearCachedProxies()` is never called during `stop()` or binder death. This means stale proxy objects can survive, though in practice the process typically dies with the session.

The overall finding is real but the description "temporary roots and windows are not consistently recycled" is overstated -- it is one specific call site (`getCurrentPackageName`), not a systemic pattern.

---

## High #7: Platform calls report success when failed

**Review claim**: `VirtualDisplayAppController` reports success after `launchOnDisplay(...)`, but the launcher swallows exceptions and only logs them.

### Evidence

**Call chain**:

1. `VirtualDisplayAppController.launchApp()` (line 77-79):
```kotlin
shizuku.launchOnDisplay(service, launchIntent, displayId)
ActionResult.Success("Launched $packageName on display $displayId (intent)")
```

2. `ShizukuClient.launchOnDisplay()` (line 129-131):
```kotlin
fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
    activityLauncher.launchOnDisplay(context, intent, displayId)
}
```
Note: `ShizukuClient.launchOnDisplay` returns `Unit`. It has no way to signal failure.

3. `ShizukuActivityLauncher.launchOnDisplay()` (lines 13-28):
```kotlin
fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
    try {
        // ... reflection to call ActivityOptions.setLaunchDisplayId ...
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
        Log.d(TAG, "Launched activity on display $displayId")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch on display $displayId", e)
    }
}
```

The exception is caught and only logged. The method returns `Unit`. The caller has no way to know it failed.

**Back in `VirtualDisplayAppController.launchApp()`**: The outer try/catch (lines 81-84) would catch exceptions thrown by `shizuku.launchOnDisplay()`, but since `ShizukuActivityLauncher` swallows all exceptions internally, nothing ever propagates. The code unconditionally reaches `ActionResult.Success` on line 78.

**However**, let me also note: the `VirtualDisplayAppController.launchApp()` method first tries the shell path (lines 50-71):
```kotlin
if (component != null && shizukuAvailable) {
    val code = shizuku.executeShellCommand(cmd)
    if (code == 0) {
        return@withContext ActionResult.Success(...)
    }
    Log.w(TAG, "Shell launch failed (code $code), falling back to intent")
}
```
The shell path checks the exit code and falls back on non-zero. But when it falls back to the intent path, the intent path always reports success regardless of actual outcome.

**VERDICT: REAL BUG**

This is a genuine bug. The `ShizukuActivityLauncher.launchOnDisplay()` method swallows all reflection and launch exceptions, returns `Unit`, and the caller unconditionally returns `ActionResult.Success`. If the hidden API reflection fails (e.g., `ActivityOptions.setLaunchDisplayId` not found on a particular ROM), or `context.startActivity` throws a `SecurityException`, the failure is silently swallowed and the caller is told the launch succeeded. This is exactly the "reports success when failed" pattern described in the review.

The fix is straightforward: `ShizukuActivityLauncher.launchOnDisplay()` should either throw on failure (letting the outer catch in `VirtualDisplayAppController` handle it) or return a boolean success indicator.

---

## Summary Table

| Finding | Verdict | Severity Accurate? | Notes |
|---------|---------|-------------------|-------|
| High #4: VD geometry stale after rotation | OVERSTATED | Medium at most, not High | VD is a self-contained coordinate space. Agent actions within VD are not affected by physical rotation. The real issue is viewer UX degradation and wrong initial metric source (app metrics vs real metrics). |
| High #5: A11y capture does not fail soft | REAL BUG | High is correct | Clear asymmetry: VD path catches Perceptor failures, accessibility path does not. A stale root can crash the turn. |
| High #6: Resource ownership inconsistent | REAL BUG (partial) | Overstated as a category | The "not consistently recycled" framing overstates the scope. One specific leak exists (`getCurrentPackageName` on accessibility path). Debug screenshot retention gap is real but debug-mode only. Missing `clearCachedProxies()` in `stop()` is real. |
| High #7: Platform calls report success when failed | REAL BUG | High is correct | `ShizukuActivityLauncher` silently swallows all exceptions, `VirtualDisplayAppController` unconditionally returns Success for the intent launch path. |

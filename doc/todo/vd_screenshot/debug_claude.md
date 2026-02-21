# Virtual Display Mode: Empty WORLD in Inspection Tool

**Status**: Root cause identified, fix needed
**Severity**: Debug-only (does not affect agent behavior)
**Last updated**: 2026-02-20

## Problem

When running tasks in virtual display mode (both `eval/aw_bridge/runner.py --platform-mode virtual_display` and `scripts/debug-run.sh`), the inspection tool's WORLD panel is empty — no screenshots and no a11y tree overlays are displayed for any step (Pre or Post).

**Observed in**:
- Eval run: `eval/results/20260220_202427/artifacts/aw_20260220_202427_FilesMoveFile_0_0/`
- Debug run: `debug-output/run_20260220_115849/`

**Evidence**: `trace.jsonl` contains `screen_captured` events with empty `"artifacts":[]` arrays:
```json
{"type":"screen_captured","data":{"elements":14,"package":"com.google.android.documentsui"},"artifacts":[]}
```

Physical display runs have fully populated artifacts:
```json
{"type":"screen_captured","data":{"elements":23},"artifacts":[
  {"kind":"raw_a11y_tree","path":"artifacts/raw_a11y_tree/1_raw_...json"},
  {"kind":"sanitized_a11y_tree","path":"artifacts/sanitized_a11y_tree/2_sanitized_...json"},
  {"kind":"screenshot","path":"artifacts/screenshot/4_screenshot_...462x1024.jpg"}
]}
```

No `screenshot/`, `raw_a11y_tree/`, or `sanitized_a11y_tree/` subdirectories exist under `trace/artifacts/` in any VD run.

## Root Cause

Two gaps in `VirtualDisplayPlatform` compared to `AccessibilityPlatform`:

### Gap 1: No screenshot capture when tracing is enabled

**`AccessibilityPlatform.captureScreen()`** (line 61):
```kotlin
val shouldCaptureScreenshot = pc.capturesScreenshot || traceRecorder.enabled
```

**`VirtualDisplayPlatform.captureScreen()`** (line 227):
```kotlin
val imageCapture = if (pc.capturesScreenshot) captureCoordinator.captureScreenshot() else null
```

The accessibility platform has a `|| traceRecorder.enabled` fallback that captures screenshots for tracing even when the perception config doesn't require them. The virtual display platform does not have this fallback.

Since the default `PerceptionConfig` is `AccessibilityOnly` (where `capturesScreenshot = false`), and eval/debug runs use this default, **no screenshots are captured in VD mode**.

The downstream effect: `imageCapture` is null → `ScreenSnapshotDebug` is null → `snapshotArtifacts()` returns empty list → `screen_captured` events have `artifacts:[]`.

### Gap 2: No a11y tree artifact persistence

**`AccessibilityPlatform.captureAccessibilityTree()`** (lines 144-173):
- Dumps raw tree via `A11yTreeDumper.dump(root)` → stores as `raw_a11y_tree` trace artifact
- Creates sanitized tree via `Perceptor.toPromptJson()` → stores as `sanitized_a11y_tree` trace artifact
- Stores `capture_quality` diagnostics

**`VirtualDisplayCaptureCoordinator.captureA11yTree()`** (lines 37-49):
- Calls `Perceptor.snapshot(root)` and returns `List<PerceptionElement>` directly
- **No trace artifact persistence whatsoever** — no raw tree dump, no sanitized tree, no quality diagnostics

Even if Gap 1 were fixed, the WORLD panel would still be missing a11y tree overlays because no tree artifacts are stored.

## Data Flow Comparison

### AccessibilityPlatform (working)

```
captureScreen()
  ├── captureAccessibilityTree()
  │   ├── A11yTreeDumper.dump(root) → traceRecorder.storeText("raw_a11y_tree") → path
  │   ├── Perceptor.toPromptJson()  → traceRecorder.storeText("sanitized_a11y_tree") → path
  │   └── captureQuality            → traceRecorder.storeText("capture_quality") → path
  │
  ├── screenshotCapturer.captureIfEnabled(enabled = capturesScreenshot || traceRecorder.enabled)
  │   └── traceRecorder.storeBytes("screenshot") → path
  │
  └── ScreenSnapshotDebug(rawA11yTreePath, sanitizedA11yTreePath, screenshotPath, captureQualityPath)
      └── All paths populated → snapshotArtifacts() returns full artifact list
```

### VirtualDisplayPlatform (broken)

```
captureScreen()
  ├── captureCoordinator.captureA11yTree()
  │   └── Perceptor.snapshot(root).elements   ← No trace persistence!
  │
  ├── if (pc.capturesScreenshot) captureCoordinator.captureScreenshot()  ← Missing || traceRecorder.enabled
  │   └── null when AccessibilityOnly (default)
  │
  └── ScreenSnapshotDebug = null  ← imageCapture is null, so debug is null
      └── snapshotArtifacts() returns empty list → artifacts:[]
```

## Fix

### Fix 1: Add `|| traceRecorder.enabled` to screenshot capture condition

In `VirtualDisplayPlatform.captureScreen()` (line 227), change:
```kotlin
val imageCapture = if (pc.capturesScreenshot) captureCoordinator.captureScreenshot() else null
```
to:
```kotlin
val shouldCapture = pc.capturesScreenshot || traceRecorder.enabled
val imageCapture = if (shouldCapture) captureCoordinator.captureScreenshot() else null
```

Also decouple the image returned in the snapshot from the trace capture (matching AccessibilityPlatform's pattern):
```kotlin
val image = if (pc.capturesScreenshot) imageCapture?.image else null
```

### Fix 2: Store a11y tree artifacts in VD capture path

Expand `VirtualDisplayCaptureCoordinator.captureA11yTree()` (or `VirtualDisplayPlatform.captureScreen()`) to persist raw and sanitized trees when `traceRecorder.enabled`:

```kotlin
// Store raw a11y tree
val rawTreePath = if (traceRecorder.enabled) {
    val dump = A11yTreeDumper.dump(root)
    val json = TraceJson.instance.encodeToString(dump)
    traceRecorder.storeText(
        kind = "raw_a11y_tree",
        filenameHint = "raw_${System.currentTimeMillis()}.json",
        content = json,
        mimeType = "application/json"
    )?.path
} else null

// After Perceptor.snapshot()
val sanitizedTreePath = if (traceRecorder.enabled) {
    val json = Perceptor.toPromptJson(snapshot)
    traceRecorder.storeText(
        kind = "sanitized_a11y_tree",
        filenameHint = "sanitized_${snapshot.timestamp}.json",
        content = json,
        mimeType = "application/json"
    )?.path
} else null
```

### Fix 3: Build complete ScreenSnapshotDebug

Update `VirtualDisplayPlatform.captureScreen()` to construct debug with all paths:
```kotlin
val debug = if (traceRecorder.enabled) {
    ScreenSnapshotDebug(
        rawA11yTreePath = a11yResult.rawTreeArtifactPath,
        sanitizedA11yTreePath = a11yResult.sanitizedTreeArtifactPath,
        screenshotPath = imageCapture?.tracePath
    )
} else null
```

This requires `captureA11yTree()` to return a result struct (like `A11yCaptureResult` in AccessibilityPlatform) instead of just `List<PerceptionElement>`.

## Files to Modify

| File | Change |
|------|--------|
| `VirtualDisplayPlatform.kt` | Add `\|\| traceRecorder.enabled` to screenshot condition; build full `ScreenSnapshotDebug` |
| `VirtualDisplayCaptureCoordinator.kt` | Return artifact paths from `captureA11yTree()`; store raw + sanitized trees |

## Impact Assessment

- **Agent behavior**: No change. Screenshots and a11y trees are still captured for perception when the config requires them. This fix only adds trace persistence for debugging.
- **Performance**: Marginal increase in I/O when tracing is enabled (writing additional artifact files). Same overhead that AccessibilityPlatform already incurs.
- **Disk usage**: Similar to AccessibilityPlatform — one screenshot JPEG (~30-60KB) and two JSON files (~5-20KB each) per `screen_captured` event.

## Dependencies

`VirtualDisplayPlatform` already has `traceRecorder: TraceRecorder` as a constructor parameter (line 39) — it's passed through to `VirtualDisplayScreenshotProcessor` (line 68) but not used directly in `captureScreen()`. No wiring changes needed in `PlatformFactory`.

New imports needed in the VD capture path:
- `com.moonkey.androidagent.trace.A11yTreeDumper`
- `com.moonkey.androidagent.trace.TraceJson`
- `com.moonkey.androidagent.perception.Perceptor.toPromptJson`

## Verification

After fix, re-run a VD task and check:
1. `trace/artifacts/screenshot/` directory is populated with JPEG files
2. `trace/artifacts/raw_a11y_tree/` and `sanitized_a11y_tree/` directories are populated
3. `trace.jsonl` `screen_captured` events have non-empty `artifacts` arrays with all three kinds
4. Inspection tool WORLD panel shows screenshots with a11y tree overlays for VD runs

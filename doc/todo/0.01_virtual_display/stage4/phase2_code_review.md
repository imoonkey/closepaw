# Phase 2 Code Review: Virtual Display Stage 4 — Hybrid Surface Switching

**Reviewer**: Code Reviewer (systematic)  
**Date**: 2025-02-11  
**Scope**: Core Hybrid Surface Switching model — ShizukuClient callback storage, VirtualDisplayPlatform surface mode switching, PixelCopy capture

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 2 |
| Medium | 2 |
| Low | 2 |

**Recommendation: CHANGES_REQUESTED** — 2 High issues (thread safety, viewer-destroyed edge case).

---

## 1. ShizukuClient.kt — Callback storage and `setVirtualDisplaySurface`

### Findings

#### [HIGH] `displayCallbacks` map is not thread-safe

**Line**: 91  
**Problem**: `mutableMapOf<Int, IVirtualDisplayCallback>()` is not thread-safe. Concurrent access can occur:
- **Writes**: `createVirtualDisplay` (put), `releaseVirtualDisplay` (remove), `clearCachedProxies` (clear)
- **Reads**: `setVirtualDisplaySurface` (get)

`setVirtualDisplaySurface` can be called from the UI thread (lifecycle callbacks) while `releaseVirtualDisplay` runs from the session coroutine during `stop()`. A read during a concurrent remove can cause `ConcurrentModificationException` or undefined behavior.

**Fix**:
```kotlin
private val displayCallbacks = java.util.concurrent.ConcurrentHashMap<Int, IVirtualDisplayCallback>()
```

#### [MEDIUM] `setVirtualDisplaySurface` invokes with null callback when entry missing

**Line**: 131  
**Problem**: If `displayId` is not in `displayCallbacks` (e.g. race with `releaseVirtualDisplay`, or ROM never stored it), `callback` is null and `method.invoke(proxy, null, displayId, surface)` is called. The design doc notes some ROMs require the callback token. Passing null may throw or fail silently on those ROMs.

**Fix**: Fail fast when callback is missing:
```kotlin
val callback = displayCallbacks[displayId]
if (callback == null) {
    Log.w(TAG, "No callback for displayId $displayId, cannot set surface")
    return false
}
```

#### [LOW] Callback cleanup coverage

**Lines**: 154–155, 281  
**Verdict**: Callbacks are removed in `releaseVirtualDisplay` and `clearCachedProxies`. No leak risk for normal lifecycle. `clearCachedProxies` is currently never invoked from `VirtualDisplayPlatform.stop()` — acceptable if intentional (quick_fix_summary notes it was removed). Callbacks are cleaned on release.

---

## 2. VirtualDisplayPlatform.kt — Surface mode and PixelCopy

### Thread safety of `surfaceMode` and related fields

#### [LOW] Volatile usage is correct

**Lines**: 78–80  
**Verdict**: `surfaceMode` and `liveSurfaceView` are `@Volatile`. Reads/writes from the UI thread (switch methods) and coroutines (capture) have proper visibility. No reordering issues.

#### [HIGH] `pixelCopyFailCount` is not volatile

**Line**: 81  
**Problem**: `pixelCopyFailCount` is read and written from:
- **UI thread**: `switchToLivePreview` resets it to 0
- **Coroutine**: `captureFromPixelCopy` increments and reads it

Without `@Volatile`, the UI thread’s reset may not be visible to the coroutine. A prior failure count can persist, causing an unnecessary permanent revert to ImageReader after the next failure.

**Fix**:
```kotlin
@Volatile private var pixelCopyFailCount = 0
```

### PixelCopy error handling and fallback logic

#### [MEDIUM] Stale capture when switching during PixelCopy

**Lines**: 286–303  
**Problem**: `captureFromPixelCopy` stores `sv = liveSurfaceView` at the start. If the UI thread calls `switchToImageReader()` before `PixelCopy.request()` completes, the VirtualDisplay is already on ImageReader while we still run PixelCopy on the old SurfaceView. The callback can succeed with a black or stale frame.

**Fix**: Re-check mode before using the result, or gate the switch:
```kotlin
val result = withContext(Dispatchers.Main) { ... }
if (surfaceMode != SurfaceMode.LIVE_PREVIEW) {
    bitmap.recycle()
    return captureFromImageReader()
}
```

#### Viewer destroyed during capture

**Lines**: 288–293, 299–303  
**Verdict**: If the SurfaceView is destroyed or invalid:
- Pre-check: `sv == null || !sv.holder.surface.isValid` triggers fallback before PixelCopy.
- During PixelCopy: `PixelCopy.request` will fail and return non-SUCCESS; we handle that and fall back.
- Cancellation: `suspendCancellableCoroutine` is used; if the coroutine is cancelled, the callback may still run. The continuation is `resume`-only, so cancellation is not propagated into the callback — acceptable for a fire-and-forget capture.

#### Consecutive failure counting

**Lines**: 306–314  
**Verdict**: Increment on failure, reset on success. Permanent revert after `PIXEL_COPY_MAX_FAILURES` (2). Logic is correct.

### Surface validity checks before switching

#### [LOW] Pre-switch checks are adequate

**Lines**: 165–172, 191  
**Verdict**: `switchToLivePreview` checks `holder.surface != null` and `surface.isValid`. `switchToImageReader` uses `imageReader ?: return` (reader.surface is valid while reader exists). `displayId < 0` is handled in `ShizukuClient.setVirtualDisplaySurface`. Good.

---

## 3. Edge cases

| Edge case | Handled? |
|-----------|----------|
| Viewer destroyed before PixelCopy | Yes — invalid surface check + PixelCopy failure path |
| Viewer destroyed during PixelCopy | Yes — callback receives error, fallback works |
| `switchToImageReader` called after `stop()` | Yes — `imageReader` is null, early return |
| `displayId == INVALID_DISPLAY` when switching | Yes — ShizukuClient returns false for `displayId < 0` |
| `callback == null` in setVirtualDisplaySurface | Partial — invoke may throw; consider explicit check |
| Platform stopped while capture in progress | Partial — stale capture possible; see [MEDIUM] above |

---

## 4. Code quality

- **Extraction**: `bitmapToScreenImage` is a clear, reusable helper.
- **Reset in stop()**: `liveSurfaceView = null` and `surfaceMode = IMAGE_READER` are reset before release.
- **Bitmap lifecycle**: `bitmap.recycle()` is called on failure and in `bitmapToScreenImage` on success.

---

## Checklist summary

### Critical (Must Fix)
- None

### High (Should Fix)
1. **displayCallbacks** — use `ConcurrentHashMap` for thread safety
2. **pixelCopyFailCount** — add `@Volatile` for cross-thread visibility

### Medium (Consider)
1. **Null callback** — explicitly check and return false before invoking
2. **Stale capture** — re-check `surfaceMode` after PixelCopy or serialize switch with capture

### Low (Nice-to-Have)
1. Callback cleanup — already sound; `clearCachedProxies` usage is optional
2. Surface validity — current checks are sufficient

---

## Recommendation

**CHANGES_REQUESTED** — Address the 2 High issues before merge. Medium items are suggested improvements.

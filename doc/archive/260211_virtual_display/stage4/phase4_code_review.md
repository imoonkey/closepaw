# Phase 4 Code Review: Virtual Display Stage 4 — VirtualDisplayViewerActivity

**Reviewer**: Code Reviewer (systematic)  
**Date**: 2025-02-11  
**Scope**: VirtualDisplayViewerActivity, FullScreen theme, Manifest registration

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 1 |
| Medium | 3 |
| Low | 2 |

**Recommendation: CHANGES_REQUESTED** — 1 High (swipe gesture), 3 Medium issues.

---

## 1. VirtualDisplayViewerActivity.kt — Lifecycle and Surface Coordination

### SurfaceView Lifecycle vs Activity Lifecycle

**Order of events**:
- `onCreate` → `setContent` (Compose) → `onStart` → first composition → `AndroidView` factory → `surfaceCreated` (when surface ready)
- `surfaceCreated` can fire **before** or **after** `onStart` depending on layout timing. Typically it fires after `onStart` when the view is laid out.

**Design**:
- `onStart`: calls `notifyViewerVisible(surfaceView)` if surface is already set
- `surfaceCreated` (via `onSurfaceReady`): sets `surfaceView` and calls `notifyViewerVisible(sv)`

Both paths can call `notifyViewerVisible`. `VirtualDisplayPlatform.switchToLivePreview` has `if (surfaceMode == SurfaceMode.LIVE_PREVIEW) return`, so double calls are **idempotent**. ✅

---

### Double-Notification Analysis

| Scenario | onStart | surfaceCreated | Result |
|----------|---------|----------------|--------|
| Surface ready before onStart | — | notifyViewerVisible | Single switch ✅ |
| Surface ready after onStart | notifyViewerVisible (surfaceView null, no-op) | notifyViewerVisible | Single switch ✅ |
| Surface ready, both fire | notifyViewerVisible | notifyViewerVisible | Double call; platform early-returns ✅ |

**Verdict**: Double-notification is handled. `switchToLivePreview` and `switchToImageReader` are idempotent.

---

### [MEDIUM] onStop vs surfaceDestroyed ordering

**Lines**: 84–86, 99–105  

**Problem**: On config change (rotation), the Activity is recreated. Order: `onStop` → `onDestroy` → view teardown → `surfaceDestroyed`. Both `onStop` and `surfaceDestroyed` call `notifyViewerHidden`. `switchToImageReader` is idempotent, so no bug. But `surfaceDestroyed` also sets `surfaceView = null` — if `onStop` runs first, we've already notified. The surface reference is cleared in `surfaceDestroyed`; until then we could theoretically use a stale reference in a late `onStart` (e.g. process death + restore). Edge case; current flow is correct.

**Fix**: None required. Document in KDoc that `onStop` and `surfaceDestroyed` may both fire; both paths are idempotent.

---

### [LOW] Memory safety of SurfaceView reference ✅

**Lines**: 69, 79, 84–86  

**Verdict**: `surfaceView` is set in `onSurfaceReady` (from `surfaceCreated`) and nulled in `onSurfaceDestroyed`. `surfaceDestroyed` fires before the view is GC'd. No leak. Activity holds the reference; when Activity is destroyed, Compose tears down and the callback clears the ref.

---

### [LOW] Configuration changes (rotation) ✅

**Manifest**: No `android:configChanges` on the Activity — default behavior applies.

**Verdict**: Rotation recreates the Activity. New SurfaceView is created; `surfaceCreated` fires with the new surface; `notifyViewerVisible` is called. Brief switch to ImageReader during destruction is acceptable. No explicit handling needed.

---

## 2. SwipeUpDismissOverlay — Gesture Handling

### [HIGH] onDismiss called repeatedly after threshold

**Lines**: 219–227  

**Problem**: Once `totalDrag < -thresholdPx`, `onDismiss()` is invoked on **every** subsequent `onVerticalDrag` frame. `finish()` is idempotent, but we're repeatedly calling it and the callback. On fast swipes this can be many calls per gesture.

**Fix**: Call dismiss only once per gesture when crossing the threshold:

```kotlin
var dismissed by remember { mutableStateOf(false) }
detectVerticalDragGestures(
    onDragStart = {
        totalDrag = 0f
        dismissed = false
    },
    onVerticalDrag = { _, dragAmount ->
        if (dismissed) return@onVerticalDrag
        totalDrag += dragAmount
        if (totalDrag < -thresholdPx) {
            dismissed = true
            onDismiss()
        }
    }
)
```

Or use a simple flag scoped to the gesture:

```kotlin
var fired = false
onVerticalDrag = { _, dragAmount ->
    totalDrag += dragAmount
    if (!fired && totalDrag < -thresholdPx) {
        fired = true
        onDismiss()
    }
}
```

(Note: `fired` must be reset in `onDragStart` since `pointerInput(Unit)` creates a new block per composition; `onDragStart` runs per gesture.)

---

### [MEDIUM] Swipe threshold and direction

**Line**: 66 — `SWIPE_DISMISS_THRESHOLD_DP = 120f`  

**Verdict**: 120dp is reasonable for a dismiss gesture. Direction: `dragAmount` negative = upward; `totalDrag < -thresholdPx` correctly triggers on swipe-up. ✅

---

### [LOW] No cancellation / accidental trigger

**Verdict**: Once threshold is crossed, dismiss fires. No "cancel" if user swipes back down. Acceptable for this UX — swipe-up is a commitment. Downward swipes do not accumulate toward the threshold (positive dragAmount). ✅

---

## 3. AgentService.instance Pattern

### [MEDIUM] Static singleton access (pre-existing)

**Lines**: 81–82, 85, 96, 104  

**Problem**: `AgentService.instance` is a static reference. Flagged in phase3 and design reviews. If the service is destroyed while the viewer is open, `instance` is null and `?.` safely no-ops. No crash, but this is a known anti-pattern.

**Fix**: Prefer bound service or scoped state holder. For this phase, document that `instance` may be null if the service was stopped.

---

## 4. Themes.xml — FullScreen Theme

### [LOW] FullScreen theme completeness ✅

**Lines**: 13–20  

**Verdict**: Black background, transparent status/nav bars, dark icons. Works with `enableEdgeToEdge()` and `setDecorFitsSystemWindows(window, false)`. No issues.

---

## 5. AndroidManifest.xml — Activity Registration

### [LOW] singleTask and exported ✅

**Lines**: 35–39  

**Verdict**: `singleTask` prevents multiple instances. `android:exported="false"` is correct for an internal Activity. FullScreen theme is applied. ✅

---

## Summary Table

| Severity | Count | Items |
|----------|-------|-------|
| Critical | 0 | — |
| High | 1 | Swipe: onDismiss fired repeatedly after threshold |
| Medium | 3 | onStop/surfaceDestroyed ordering doc; AgentService.instance pattern; (phase3: notifyViewerVisible thread) |
| Low | 2 | Swipe threshold/direction; rotation handling |

---

## Recommendation

**CHANGES_REQUESTED**

1. **[HIGH]** Fix swipe gesture to call `onDismiss()` only once per gesture when crossing the threshold.
2. **[MEDIUM]** Add KDoc describing idempotent `onStop`/`surfaceDestroyed` behavior.
3. **[MEDIUM]** Note `AgentService.instance` may be null when service is stopped (or plan migration to bound service).

---

## Checklist Quick Reference

| Check | Status |
|-------|--------|
| SurfaceView lifecycle vs Activity lifecycle | ✅ Coordinated; idempotent |
| Double-notification (onStart + surfaceCreated) | ✅ Handled by platform early-return |
| Swipe threshold/direction | ✅ Correct |
| Swipe repeated onDismiss | ❌ Fix: fire once per gesture |
| Memory safety of SurfaceView ref | ✅ Safe |
| Configuration changes (rotation) | ✅ Default recreation OK |
| Main thread (viewer callbacks) | ✅ Activity lifecycle is main-thread |
| No hardcoded secrets | ✅ |
| singleTask launch mode | ✅ |

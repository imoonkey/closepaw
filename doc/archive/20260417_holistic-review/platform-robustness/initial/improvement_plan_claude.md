# Platform Module Improvement Plan

Derived from `review.md`. Ordered by impact. Each item is a self-contained change.

---

## P0: Fix now (reliability bugs)

### 1. Clear cached binder proxies in VirtualDisplayPlatform.stop()

**File:** `VirtualDisplayPlatform.kt`
**Change:** Add `shizuku.clearCachedProxies()` to `stop()`, after releasing the display and before resetting `displayId`.
**Why:** Without this, restarting the platform after Shizuku reconnects uses dead proxy objects and crashes with `DeadObjectException`.
**Size:** 1 line.

```
// In stop(), after shizuku.releaseVirtualDisplay(displayId):
shizuku.clearCachedProxies()
```

### 2. Refresh node before cursor positioning in setTextOnNode()

**File:** `NodeActionPerformer.kt`
**Change:** Insert `node.refresh()` before line 220 (the `if (!clear)` block that computes cursor position).
**Why:** After `ACTION_SET_TEXT`, `node.text` and `node.textSelectionStart` may still reflect the pre-setText values. This causes incorrect cursor placement when appending text, leading to garbled output.
**Size:** 1 line + move the existing refresh up.

```kotlin
// After the ACTION_SET_TEXT call succeeds (line 213), before cursor positioning:
node.refresh()  // Ensure we read post-SET_TEXT state for cursor positioning

// Place cursor after inserted text when not clearing
if (!clear) {
    val insertAt = run {
        val existing = node.text?.toString() ?: ""
        ...
    }
    ...
}

// Post-action verification can reuse the already-refreshed state
// (remove the duplicate node.refresh() at line 237, or keep it -- it's idempotent)
```

---

## P1: Fix soon (hardening)

### 3. Make binder death listener invalidate the display

**File:** `VirtualDisplayPlatform.kt`
**Change:** In the `OnBinderDeadListener`, set `displayId = Display.INVALID_DISPLAY`. This makes all subsequent `performAction()` calls return `ActionResult.Failure("Virtual display not started")` immediately instead of throwing `DeadObjectException` deep in Shizuku calls.
**Why:** Without this, the agent loop gets confusing reflection exceptions instead of a clean failure message. The session doesn't know to stop retrying.
**Size:** 1 line inside the listener.

```kotlin
binderDeadListener = Shizuku.OnBinderDeadListener {
    Log.e(TAG, "Shizuku binder died! displayId=$displayId")
    displayId = Display.INVALID_DISPLAY
}
```

### 4. Guard ImageReader against createVirtualDisplay exceptions

**File:** `VirtualDisplayPlatform.kt`
**Change:** Wrap the `createVirtualDisplay` call in a try/catch that closes the ImageReader on any exception (not just the -1 return code case).
**Why:** `createVirtualDisplay` calls through reflection and can throw various exceptions. The current code only handles the -1 return case.
**Size:** ~5 lines.

```kotlin
val reader = ImageReader.newInstance(...)
val id = try {
    shizuku.createVirtualDisplay(...)
} catch (e: Exception) {
    reader.close()
    throw e
}
if (id < 0) {
    reader.close()
    throw IllegalStateException("Failed to create virtual display (returned $id)")
}
```

---

## P2: Clean up (code hygiene)

### 5. Remove dead code

**Files:**
- `AccessibilityGestureInjector.kt`: Delete `gestureDisplayId()` (lines 153-157). Private, never called.
- `NodeActionPerformer.kt`: Delete `performNodeActionAt()` (lines 251-273). Private, never called.

**Size:** ~25 lines removed.

### 6. Document DISPLAY_FLAGS magic number

**File:** `VirtualDisplayPlatform.kt`
**Change:** Add a comment above the `DISPLAY_FLAGS` constant explaining each bit.

```kotlin
/**
 * Virtual display flags:
 * 0x001 = PUBLIC
 * 0x008 = SECURE (prevent screen recording of VD)
 * 0x040 = OWN_CONTENT_ONLY
 * 0x200 = AUTO_MIRROR (fallback when no content)
 * 0x400 = SUPPORTS_TOUCH
 * 0x800 = ROTATES_WITH_CONTENT
 */
private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800
```

---

## Deferred: Not worth changing now

### Bitmap leak in captureFromImageReader (A4)
The bitmap created at line 127 of `VirtualDisplayCaptureCoordinator.kt` could leak if `copyPixelsFromBuffer` throws. In practice this is rare and GC handles it. Not worth adding complexity.

### Swipe MOVE injection failures ignored (A12)
`VirtualDisplayInputInjector.injectSwipe()` ignores MOVE injection failures. The system self-heals via touch timeout. Adding early-abort logic would complicate the code for a marginal improvement.

### A11y capture duplication (B1)
`AccessibilityPlatform.captureAccessibilityTree()` and `VirtualDisplayCaptureCoordinator.captureA11yTreeWithArtifacts()` share structure but differ in retry logic, diagnostics, and error handling. Extracting a shared pipeline would add abstraction cost. Defer until a third platform variant emerges.

---

## Summary

| # | Priority | Effort | File | Description |
|---|----------|--------|------|-------------|
| 1 | P0 | 1 line | VirtualDisplayPlatform.kt | Clear stale binder proxies in stop() |
| 2 | P0 | ~3 lines | NodeActionPerformer.kt | Refresh node before cursor positioning |
| 3 | P1 | 1 line | VirtualDisplayPlatform.kt | Invalidate displayId on binder death |
| 4 | P1 | 5 lines | VirtualDisplayPlatform.kt | Guard ImageReader on createVD exception |
| 5 | P2 | -25 lines | GestureInjector, NodeActionPerformer | Remove dead code |
| 6 | P2 | +8 lines | VirtualDisplayPlatform.kt | Document DISPLAY_FLAGS |

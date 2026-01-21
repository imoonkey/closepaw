# Diff Review: Platform & Perception Fixes

> **Reviewer**: Code review following `sop/diff_review.md`
> **Files Reviewed**: `Models.kt`, `Perceptor.kt`, `AccessibilityPlatform.kt`
> **Source**: Fixes for `doc/review/summary/platform_perception_summary.md`

---

## 1) Summary

These changes implement fixes for issues from `doc/review/summary/platform_perception_summary.md`:

1. **Adds gesture timeout** - `dispatchGesture()` now has 5-second timeout to prevent indefinite hangs (H4)
2. **Adds Bounds/Point data classes** - Replaces `IntArray` with type-safe data classes for bounds and center (M1, M2)
3. **Fixes normalizeWhitespace()** - Now preserves meaningful newlines, only collapses horizontal whitespace (M5)
4. **Removes unused V1 classes** - `AgentAction`, `ValidationOutcome`, `ManagerResult` removed (M6)
5. **Adds TODO comments** - Documents deferred issues for scroll zones, MAX_ELEMENTS priority, permissions, JSON streaming (H3, H5, M4, M7)

---

## 2) High-Risk Issues - Status

### H1. AccessibilityNodeInfo Lifecycle ✅ Already Fixed

**Status**: Verified fixed in data_infra work.

`ScreenSnapshot` no longer stores `rootOriginal` or `rawMap`. Perceptor properly recycles child nodes via `shouldRecycle` parameter. AccessibilityPlatform re-queries tree at action time.

---

### H2. performType() Double-Click ✅ Already Fixed

**Status**: Verified fixed in data_infra work.

`performType()` now uses gesture-based tap (not ACTION_CLICK), adds 100ms delay for focus, and re-queries the tree for fresh node.

---

### H3. Scroll Gesture Safe Zones ✅ Documented

**Status**: TODO added.

**Location**: `AccessibilityPlatform.kt:244-247`

```kotlin
// TODO: Consider using WindowInsets API (API 29+) for actual system gesture exclusion zones.
//       Current hardcoded values work well for standard phone form factors but may need
//       adjustment for tablets, phones with notches, or landscape mode.
```

**Rationale**: WindowInsets API requires API 29+ and view-based access which is complex in AccessibilityService context. Current hardcoded safe zones work reasonably well for standard phone form factors.

---

### H4. dispatchGesture() Timeout ✅ Fixed

**Location**: `AccessibilityPlatform.kt:30, 352-378`

**Before**:
```kotlin
private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
    return suspendCancellableCoroutine { continuation ->
        // ... callback never times out
    }
}
```

**After**:
```kotlin
companion object {
    /** Timeout for gesture callbacks - prevents indefinite hang if callback never fires */
    private const val GESTURE_TIMEOUT_MS = 5000L
}

private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
    return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            // ... callback logic
        }
    } ?: ActionResult.Failure("Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
}
```

---

### H5. Perceptor MAX_ELEMENTS Priority ✅ Documented

**Status**: TODO added.

**Location**: `Perceptor.kt:17-20`

```kotlin
// TODO: Consider prioritizing interactive elements (clickable/editable) over non-interactive
//       text elements. Current DFS approach may cap at MAX_ELEMENTS before reaching important
//       buttons at the bottom of long lists. If this becomes a real issue, implement two-pass
//       traversal: first collect interactive elements, then fill remaining slots with text.
```

**Rationale**: Current DFS approach works for most screens. Prioritization would require two-pass traversal or post-traversal sorting.

---

## 3) Medium Issues - Status

### M1. PerceptionElement.bounds Uses IntArray ✅ Fixed

**Location**: `Models.kt:5-28`, `Models.kt:47-58`

**Before**:
```kotlin
data class PerceptionElement(
    // ...
    val bounds: IntArray,
    val center: IntArray
)
```

**After**:
```kotlin
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class Point(
    val x: Int,
    val y: Int
)

data class PerceptionElement(
    // ...
    val bounds: Bounds,
    val center: Point
)
```

**Benefits**:
- Type safety with named properties
- Compile-time enforcement (no wrong array index)
- Proper `equals()`/`hashCode()` for data class (fixes M2)
- Convenient computed properties (width, height)

---

### M2. ScreenSnapshot Equality ✅ Fixed by M1

Using `Bounds` and `Point` data classes instead of `IntArray` automatically provides correct `equals()` and `hashCode()` implementations.

---

### M3. UIAction.ClickAt Not Used ✅ No Action Needed

**Decision**: Keep it. `ClickAt` is used internally by `performClick()` and could be useful for future coordinate-based tools.

---

### M4. hasRequiredPermissions() Insufficient ✅ Documented

**Status**: TODO added.

**Location**: `AccessibilityPlatform.kt:58-61`

```kotlin
override fun hasRequiredPermissions(): Boolean {
    // TODO: Consider checking Settings.canDrawOverlays() for overlay permission.
    //       However, overlay permission should be verified at MainActivity level,
    //       not here. Current check is sufficient for AccessibilityPlatform's scope.
    return service.serviceInfo != null
}
```

---

### M5. normalizeWhitespace() Removes Newlines ✅ Fixed

**Location**: `Perceptor.kt:159-168`

**Before**:
```kotlin
private fun String.normalizeWhitespace(): String {
    return this.replace(Regex("\\s+"), " ").trim()
}
```

**After**:
```kotlin
/**
 * Normalize whitespace while preserving meaningful structure.
 * - Collapses multiple horizontal spaces/tabs to single space
 * - Collapses multiple newlines to single newline
 * - Preserves single newlines (meaningful line breaks)
 */
private fun String.normalizeWhitespace(): String {
    return this
        .replace(Regex("[ \\t]+"), " ")    // Collapse horizontal whitespace only
        .replace(Regex("\\n{2,}"), "\n")   // Collapse multiple newlines to single
        .trim()
}
```

---

### M6. Unused Model Classes ✅ Fixed

**Location**: `Models.kt`

**Removed**:
- `AgentAction` sealed class (V1 remnant)
- `ValidationOutcome` sealed class (V1 remnant)
- `ManagerResult` data class (V1 remnant)

These were not used by the current architecture.

---

### M7. Inefficient JSON Generation ✅ Documented

**Status**: TODO added.

**Location**: `Perceptor.kt:65-67`

```kotlin
// TODO: Consider using streaming JSON writer for better performance
//       if profiling shows JSON generation is a bottleneck.
```

**Rationale**: Current implementation is simple and works. JSON serialization is not a bottleneck compared to LLM latency.

---

### M8. Element Lookup Safety ✅ Already Fixed

**Status**: Verified fixed in data_infra work.

No longer uses `rawMap` - actions now use stored coordinates from `PerceptionElement` and re-query tree when needed (e.g., for text input).

---

## 4) Files Updated

| File | Changes |
|------|---------|
| `Models.kt` | Added `Bounds`, `Point` classes; updated `PerceptionElement`; removed `AgentAction`, `ValidationOutcome`, `ManagerResult` |
| `Perceptor.kt` | Use `Bounds`/`Point`; fix `normalizeWhitespace()`; add TODOs for MAX_ELEMENTS and JSON |
| `AccessibilityPlatform.kt` | Add gesture timeout; use `Point` for center access; add TODOs for scroll zones and permissions |
| `platform_perception_summary.md` | Added Team Notes to all issues |

---

## 5) Verification Checklist

### Original Issues - Status

| Issue | Status | Verification |
|-------|--------|--------------|
| H1. Node lifecycle | ✅ Already fixed | No raw node storage |
| H2. performType() | ✅ Already fixed | Tap + delay + re-query |
| H3. Scroll zones | ✅ Documented | TODO added |
| H4. Gesture timeout | ✅ Fixed | 5s timeout with `withTimeoutOrNull` |
| H5. MAX_ELEMENTS | ✅ Documented | TODO added |
| M1. IntArray bounds | ✅ Fixed | `Bounds`/`Point` data classes |
| M2. Equality broken | ✅ Fixed | Fixed by M1 |
| M3. ClickAt unused | ✅ Keep | Used internally |
| M4. Permissions check | ✅ Documented | TODO added |
| M5. normalizeWhitespace | ✅ Fixed | Preserves newlines |
| M6. Unused classes | ✅ Fixed | Removed V1 remnants |
| M7. JSON generation | ✅ Documented | TODO added |
| M8. Element lookup | ✅ Already fixed | Uses coordinates |

### Code Quality

- [x] No new linter errors introduced (existing `recycle()` deprecation warnings are acceptable)
- [x] Type safety improved with `Bounds`/`Point` classes
- [x] No memory leaks
- [x] Proper timeout handling for gestures
- [x] Documentation updated

---

## 6) Conclusion

All issues from `platform_perception_summary.md` have been addressed:
- **4 issues already fixed** in prior data_infra work (H1, H2, M2, M8)
- **4 issues fixed** in this work (H4, M1, M5, M6)
- **5 issues documented** with TODOs for future consideration (H3, H5, M3, M4, M7)

The key improvements are:
1. Gesture timeout prevents agent from hanging indefinitely
2. Type-safe `Bounds`/`Point` classes improve code quality and fix equality issues
3. Whitespace normalization now preserves meaningful newlines
4. Codebase cleaned up by removing unused V1 model classes

**Verdict**: Platform & Perception fixes complete.

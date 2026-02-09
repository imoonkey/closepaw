# Code Review: Phase 1 — Screen State Equality (Domain Model + Configuration)

**Scope**: ScreenSnapshot.elements nullable, PerceptionConfig sealed class, SessionConfig migration.

**Date**: 2025-02-09

---

## Summary

Phase 1 introduces the domain model and configuration changes for screen state equality. `ScreenSnapshot.elements` is now nullable with a `require()` invariant ensuring at least one modality (elements or image) is present. `PerceptionConfig` replaces three boolean/int fields in `SessionConfig`. All 19 touched files handle null elements consistently via `?.` or `?: emptyList()`.

---

## Critical

*None.*

---

## High

### 1. Platform does not yet produce null elements (ScreenshotOnly)

**File**: `AccessibilityPlatform.kt`  
**Line**: 79–115

**Problem**: The design doc (`final_design.md`) specifies that when `perceptionConfig.capturesAccessibility` is false (ScreenshotOnly), `elements` should be null. The current implementation always calls `Perceptor.snapshot(root)` and passes through its result; it never sets `elements = null`. The platform effectively always provides elements.

**Impact**: ScreenshotOnly mode is not yet implemented. If a caller creates `SessionConfig(perceptionConfig = PerceptionConfig.ScreenshotOnly())`, the snapshot will still include elements (from the a11y tree). The model and consumers are ready for null elements, but the producer is not.

**Fix**: Either (a) implement the conditional in `captureScreen()` per the design doc:

```kotlin
val elements: List<PerceptionElement>? = if (config.perceptionConfig.capturesAccessibility) snapshot.elements else null
return snapshot.copy(elements = elements, image = image, debug = debug)
```

—or (b) document clearly that ScreenshotOnly is Phase 2 and the platform change is deferred. If (b), add a TODO in `captureScreen()`.

---

### 2. Missing tests for ScreenSnapshot invariant and convenience properties

**File**: `ModelsTest.kt`  
**Lines**: 19–42

**Problem**: There are no tests for:
- The `require()` invariant: `ScreenSnapshot(timestamp = 0, elements = null, image = null)` should throw `IllegalArgumentException`.
- Screenshot-only case: `ScreenSnapshot(timestamp = 0, elements = null, image = ScreenImage(...))` should succeed.
- `hasAccessibility` and `hasScreenshot` behavior for null, empty, and non-empty elements.

**Fix**: Add tests:

```kotlin
@Test(expected = IllegalArgumentException::class)
fun `screen snapshot requires at least one modality`() {
    ScreenSnapshot(timestamp = 0, elements = null, image = null)
}

@Test
fun `screen snapshot screenshot-only has correct convenience properties`() {
    val image = ScreenImage(1, 1, "image/png", byteArrayOf(), ScreenImageSource.ACCESSIBILITY_SCREENSHOT)
    val snapshot = ScreenSnapshot(timestamp = 0, elements = null, image = image)
    assertThat(snapshot.hasAccessibility).isFalse()
    assertThat(snapshot.hasScreenshot).isTrue()
}
```

---

## Medium

### 3. UiChangeDetector fingerprint collision when elements is null

**File**: `UiChangeDetector.kt`  
**Lines**: 47–63

**Problem**: When both pre and post snapshots have `elements == null`, `fingerprint()` returns the same hash (FNV over empty sequence). `compare()` would report `Unchanged` even if the screens differ (e.g., screenshot-only, different screens). Same for `detectScrollBoundary`: both would be empty, so no scroll-boundary message.

**Impact**: Low for Phase 1 since the platform never produces null elements. Becomes relevant when ScreenshotOnly is implemented.

**Fix**: Phase 2 should introduce screenshot-based fingerprinting when `elements == null` (e.g., perceptual hash of image). Document as a known limitation for now.

---

### 4. NavigationState loop detection degraded when elements is null

**File**: `NavigationState.kt`  
**Lines**: 61–71

**Problem**: `toSignature()` uses `(elements ?: emptyList())`. When elements is null, `ScreenSignature` has empty tokens. `similarityTo()` returns 1.0 when both have empty tokens (line 46), so all screenshot-only screens would be treated as identical for loop detection.

**Impact**: Phase 1 unaffected. Phase 2 (ScreenshotOnly) would need image-based signatures for loop detection.

**Fix**: Document as Phase 2 work. Consider adding a comment in `toSignature()`.

---

### 5. hasAccessibility semantics for empty list

**File**: `Models.kt`  
**Lines**: 54–55

**Problem**: `hasAccessibility = !elements.isNullOrEmpty()` is false for both `null` and `emptyList()`. An empty list can mean “we captured a11y but the tree was empty.” The property treats “no elements” and “no a11y capture” the same.

**Impact**: Minor. Semantics are consistent (no actionable a11y data), but the name could imply “we attempted a11y capture” for empty list.

**Fix**: Optional. Consider `elements != null` if the intent is “a11y was captured” (even if empty). Or add a KDoc clarifying that empty list is treated as “no useful a11y data.”

---

## Low

### 6. TargetResolver error message when elements is null

**File**: `TargetResolver.kt`  
**Lines**: 32–36

**Problem**: When `elements` is null, `describeFailure` for `ElementIndex` says “No elements on screen.” That is correct but could be more specific: “No accessibility tree available (screenshot-only mode).” Helps debugging.

**Fix**: Optional enhancement:

```kotlin
if (available.isNotEmpty()) { ... }
else {
    "Element not found: index ${target.index}. " +
        if (snapshot.elements == null) "No accessibility tree (screenshot-only mode)."
        else "No elements on screen."
}
```

---

### 7. PerceptionConfig extension properties placement

**File**: `PerceptionConfig.kt`  
**Lines**: 40–53

**Problem**: Top-level extension properties `screenshotMaxDimension` and `screenshotJpegQuality` live in the same file. They are used by `AccessibilityPlatform` via `config.perceptionConfig.screenshotMaxDimension`. Works, but the pattern is slightly unusual (extensions on sealed class in same file).

**Fix**: Acceptable as-is. Could move to a separate `PerceptionConfigExt.kt` for consistency with other extensions, but current placement is fine.

---

## Nitpick

### 8. ModelsTest assertion style

**File**: `ModelsTest.kt`  
**Line**: 39

**Problem**: `assertThat(snapshot.elements).hasSize(1)` passes even when `elements` is null (Truth’s `hasSize` on nullable may behave unexpectedly). Prefer `assertThat(snapshot.elements).isNotNull()` and `assertThat(snapshot.elements!!.size).isEqualTo(1)`, or `assertThat(snapshot.elements?.size).isEqualTo(1)`.

**Fix**: Ensure the test explicitly asserts non-null when expecting elements:

```kotlin
assertThat(snapshot.elements).isNotNull()
assertThat(snapshot.elements).hasSize(1)
```

---

### 9. Indentation in AgentTurnRunner

**File**: `AgentTurnRunner.kt`  
**Lines**: 152–156

**Problem**: `logSnapshotElements` has inconsistent indentation (extra spaces before `Log.d`).

**Fix**: Standardize indentation.

---

### 10. Removed SessionConfig fields not mirrored in docs

**Files**: `doc/main/protocol/protocol.md`, `doc/main/app/settings.md`, `doc/main/infra/platform.md`

**Problem**: Documentation still references `enableScreenshotInput`, `screenshotMaxDimension`, `screenshotJpegQuality`. These were removed from `SessionConfig`.

**Fix**: Run `/update-docs` to sync documentation with the new `perceptionConfig`-based API.

---

## Correctness Checklist

| Check | Status |
|-------|--------|
| All `elements` usages null-safe | Yes – no `!!`, all use `?.` or `?: emptyList()` |
| No force-unwrap on elements | Yes |
| require() invariant on ScreenSnapshot | Yes |
| Backward compatibility | Yes – `PerceptionConfig.DEFAULT` = AccessibilityOnly; MainActivity mapping preserves behavior |
| MainActivity Hybrid vs DEFAULT | Correct – same condition as before |
| TraceRecorderFactory migration | Correct – uses `capturesScreenshot` |

---

## API Design Assessment

**PerceptionConfig sealed class**: Well-designed. Exhaustive `when` handling, clear variants, extension properties keep call sites clean. `AccessibilityOnly` as `data object` is appropriate.

**ScreenSnapshot convenience properties**: `hasAccessibility` and `hasScreenshot` reduce repeated null checks. Good addition.

**Extension properties**: `screenshotMaxDimension` and `screenshotJpegQuality` centralize defaults. `AccessibilityOnly` returning defaults when it doesn’t capture screenshots is reasonable (e.g., for trace-only capture).

---

## Approval Criteria

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 2 |
| Medium | 3 |
| Low | 2 |
| Nitpick | 3 |

---

## Recommendation

**CHANGES_REQUESTED**

The two High items should be addressed before merge:

1. **Platform vs design doc**: Either implement ScreenshotOnly production of null elements in `AccessibilityPlatform`, or document that this is Phase 2 and add a TODO.
2. **Tests**: Add unit tests for the ScreenSnapshot invariant and convenience properties.

Medium items are acceptable to defer; low and nitpick items are optional.

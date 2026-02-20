# Executor Refactor Plan: Simplify & Unify Action Executors

**Date**: 2026-02-20
**Based on**: `2_regression_analysis_refactoring_claude.md` (Qi Notes)
**Scope**: Full refactoring roadmap — Phase 1 through Phase 3

---

## Motivation (from Qi Notes)

1. **Remove UiChangeDetector from all executors** — "都不带UiChangeDetector，这个行为不该用在任何地方。因为有可能本来就是click了不变。"
2. **Cascade order (Phase 1)**: node action → gesture fallback. "因为现在node action work得更好"
3. **Cascade order (Phase 2)**: Once dispatchGesture is confirmed fixed, reverse to gesture → node action. "等fix了dispatchGesture，全都work了，再把优先顺序反过来"
4. **TargetResolver usage**: "尽可能改成一样的"
5. **ScrollExecutor scroll range validation**: "应该不需要吧" — remove `detectScrollBoundary()`
6. **VirtualDisplayPlatform parity**: Apply similar swipe coordinate clamping for consistency

---

## Current Executor Architecture

```
MobileActionTool
  ├── ClickExecutor     → TargetResolver → node_click → gesture_tap (fallback)
  ├── LongPressExecutor → TargetResolver → node_long_click → gesture_long_press (fallback)
  ├── ScrollExecutor    → resolveScrollArea() → a11y_scroll → gesture_swipe (fallback)
  ├── SwipeExecutor     → raw coords from JSON → gesture_swipe (no fallback)
  └── TypeExecutor      → TargetResolver → SetTextOnNodeAt → TapToFocus+SetTextOnFocused (fallback)
```

### TargetResolver Consistency Analysis

| Executor | Uses TargetResolver? | Target Type | Rationale |
|----------|---------------------|-------------|-----------|
| ClickExecutor | ✅ Yes | `Target` sealed interface | Semantic + coordinate targets |
| LongPressExecutor | ✅ Yes | `Target` sealed interface | Same pattern as Click |
| TypeExecutor | ✅ Yes (when target provided) | `Target` sealed interface | Also works without target (focused field) |
| ScrollExecutor | ❌ No — uses `resolveScrollArea()` | `JSONObject` with `element_index` | Needs element **bounds**, not center point |
| SwipeExecutor | ❌ No — raw coordinates | `JSONObject` with `start`/`end` arrays | Coordinate-only by design |

**Conclusion**: The inconsistency is by design. TargetResolver returns a center `Point`, which is correct for click/long_press/type. Scroll needs the full `Bounds` of a scrollable container. Swipe takes raw coordinate pairs. Making them "the same" means ensuring the pattern is intentional and documented, not forcing a unified API that doesn't fit.

**Possible future improvement**: Extend `TargetResolver.ResolveResult.Resolved` to include optional `bounds` alongside `point`, so ScrollExecutor could use TargetResolver too. Low priority — current pattern works.

---

## Phase 1: Clean Up (DONE ✅)

Remove fragile verification, unify executor behavior, VD parity.

**Prerequisite**: None
**Validation**: `./gradlew assembleDebug && ./gradlew test` + eval SystemBrightness tasks

### A. LongPressExecutor — Remove UiChangeDetector ✅

- Removed `UiChangeDetector.compare()` call from `buildSuccessOutcome()`
- Removed `preSnapshot` parameter
- Always `verified = true` (match ClickExecutor behavior)
- Updated test: `execute always marks long press as verified`

### B. ScrollExecutor — Remove detectScrollBoundary ✅

- Removed `UiChangeDetector.detectScrollBoundary()` (both a11y and gesture paths)
- Removed `buildMessage()` helper
- Direct message strings: "Scrolled $direction via a11y action" / "via gesture"

### C. VirtualDisplayPlatform — Add Swipe Coordinate Clamping ✅

- Extracted `performSwipe()` method matching AccessibilityPlatform pattern
- Clamp all coordinates to `config.width/height` bounds
- No edge inset (VD doesn't have gesture-nav interference)
- Diagnostic logging for clamped coordinates

### D. Verify No Orphaned UiChangeDetector Usage ✅

- `UiChangeDetector` only referenced from its own file + `DebugActionExecutor` (debug utility, acceptable)
- No production executor depends on it

---

## Phase 2: Reverse Cascade Order (gesture first → node fallback) — DONE ✅

**Prerequisite**: Phase 1 eval confirms no regression. dispatchGesture fix confirmed working (commit `51d7369`).
**Rationale from Qi**: "等fix了dispatchGesture（现在应该已经fix了），全都work了，再把优先顺序反过来，先gesture action再node action。"
**Why gesture first is better long-term**: Gesture actions are more universal — they work on any visible element regardless of whether the a11y tree exposes the right action. Node actions depend on app's a11y implementation (some apps don't mark buttons as clickable, etc.). Once dispatchGesture is reliable, gesture-first is more robust.

### E. ClickExecutor — Reverse cascade to gesture → node ✅

**File**: `ClickExecutor.kt`

**Before**: semantic target → try `ClickNodeAt` (node) → fallback `TapAt` (gesture)
**After**: semantic target → try `TapAt` (gesture) → fallback `ClickNodeAt` (node)

- Coordinate targets: unchanged (gesture tap only, no cascade)
- For semantic targets: swapped the two blocks — try gesture first, if it fails try node action
- Updated `formatSuccess()` verbs to match new primary path
- Updated `attemptTrail` labels

### F. LongPressExecutor — Reverse cascade to gesture → node ✅

**File**: `LongPressExecutor.kt`

**Before**: semantic target → try `LongClickNodeAt` (node) → fallback `LongPressAt` (gesture)
**After**: semantic target → try `LongPressAt` (gesture) → fallback `LongClickNodeAt` (node)

- Same swap pattern as ClickExecutor
- For coordinate targets: unchanged (gesture only)

### G. ScrollExecutor — NOT REVERSED (a11y stays primary) ⚠️

**File**: `ScrollExecutor.kt`

**Attempted**: Reverse to gesture `Swipe` first → fallback `ScrollNodeAt` (a11y)
**Result**: **REGRESSION** — SystemBrightnessMax eval failed (0/1). Scroll gestures in Settings were completely ineffective.
**Root cause**: Gesture swipe via `dispatchGesture` can "succeed" (returns no error) but the target `ScrollView`/`ListView` doesn't interpret the injected touch events as a scroll. The gesture lands but the view doesn't scroll. Meanwhile, a11y scroll (`ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD`) programmatically tells the scrollable container to scroll, which is fundamentally more reliable.
**Action taken**: Reverted ScrollExecutor to a11y-first ordering. Added detailed doc comment explaining why scroll is different from click/long_press.

**Key insight**: Click/long_press gesture actions are point events — tap anywhere and the view under that point receives the event. Scroll gestures are motion sequences — the view must interpret a sequence of MOVE events as a scroll intent, which is fragile with injected gestures. This is why gesture-first works for click/long_press but NOT for scroll.

**Final cascade orders**:
```
ClickExecutor:     gesture_tap → node_action_click (semantic targets only)
LongPressExecutor: gesture_long_press → node_action_long_click (semantic targets only)
ScrollExecutor:    a11y_scroll → gesture_swipe (UNCHANGED from Phase 1)
SwipeExecutor:     gesture_swipe only (no cascade)
TypeExecutor:      node_set_text → tap_to_focus+set_text (no cascade reversal)
```

### Files NOT changing in Phase 2

| File | Reason |
|------|--------|
| `ScrollExecutor.kt` | a11y scroll must remain primary — gesture swipe can "succeed" without scrolling (see section G) |
| `SwipeExecutor.kt` | Gesture-only by design, no cascade to reverse |
| `TypeExecutor.kt` | Text input is fundamentally a node action (`SetText`). Gesture path is just for focus. No reversal needed. |

---

## Phase 3: Structural Improvements (Future)

Lower priority refactoring for code quality. Not tied to Qi Notes urgency.

### H. Extend TargetResolver to support Bounds

Add optional `bounds` to `ResolveResult.Resolved` so ScrollExecutor could use TargetResolver:

```kotlin
sealed interface ResolveResult {
    data class Resolved(
        val point: Point,
        val bounds: Bounds? = null,   // NEW: for scroll area targeting
        val warnings: List<String>
    ) : ResolveResult
    data class NotFound(val reason: String) : ResolveResult
}
```

ScrollExecutor would then use `resolvedTarget.bounds ?: fullScreenBounds` instead of its custom `resolveScrollArea()`.

### I. Coordinate Clamping Consolidation

Currently clamping happens in multiple places:
- `AccessibilityPlatform.performSwipe()` — clamps + edge inset
- `VirtualDisplayPlatform.performSwipe()` — clamps only
- `ClickExecutor.isWithinDisplayBounds()` — rejects out-of-bounds (doesn't clamp)
- `LongPressExecutor.isWithinDisplayBounds()` — rejects out-of-bounds (doesn't clamp)

Options:
1. **Move clamping to TargetResolver** — clamp at resolution time, so all downstream code gets valid coords. Problem: swipe start/end are not resolved by TargetResolver.
2. **Keep clamping at platform level** — this is where display info lives. Each platform knows its own constraints (edge inset for a11y, none for VD).
3. **Extract shared utility** — `CoordinateValidator` with `clamp()` and `isInBounds()`.

**Recommendation**: Keep clamping at platform level (option 2). It's the right architectural layer — platforms own display topology. The executor-level `isWithinDisplayBounds()` check is a fast-fail guard and stays.

### J. ActionDispatcher (Deferred)

From doc 2 section 5B: a unified `ActionDispatcher` that replaces per-executor cascade logic. This is a large refactor with high risk for a working system. Defer until there's a concrete need (e.g., adding new action types or complex multi-step cascades).

---

## Files Modified Summary

### Phase 1 (Done)

| File | Change |
|------|--------|
| `LongPressExecutor.kt` | Remove UiChangeDetector, simplify buildSuccessOutcome |
| `ScrollExecutor.kt` | Remove detectScrollBoundary, simplify messages |
| `VirtualDisplayPlatform.kt` | Add performSwipe with clamping |
| `LongPressExecutorTest.kt` | Update test for always-verified behavior |

### Phase 2 (Done)

| File | Change |
|------|--------|
| `ClickExecutor.kt` | Reverse cascade: gesture first → node fallback |
| `LongPressExecutor.kt` | Reverse cascade: gesture first → node fallback |
| `ScrollExecutor.kt` | **NOT changed** — attempted reversal caused regression, reverted to a11y-first |
| `ClickExecutorTest.kt` | Updated test expectations for gesture-first cascade order |
| `LongPressExecutorTest.kt` | Updated test expectations for gesture-first cascade order |

---

## Verification

### Phase 1
1. ✅ `./gradlew assembleDebug` — build succeeds
2. ✅ `./gradlew test` — LongPressExecutorTest updated and passing
3. ✅ Grep for `UiChangeDetector` — only in own file + DebugActionExecutor
4. ✅ Eval: `SystemBrightnessMax,SystemBrightnessMin` — 2/2 passed (1.0), no regression

### Phase 2
1. ✅ `./gradlew assembleDebug && ./gradlew test` — build and tests pass
2. ⚠️ Eval (with scroll reversed): `SystemBrightnessMax` FAILED (0.5) — scroll regression
3. ✅ Root cause: gesture swipe "succeeds" without actually scrolling content (see Phase 2 section G)
4. ✅ Fix: Reverted ScrollExecutor to a11y-first, kept Click/LongPress as gesture-first
5. ✅ Eval (after fix): `SystemBrightnessMax,SystemBrightnessMin` — 2/2 passed (1.0), no regression

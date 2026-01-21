# Platform & Perception - Consolidated Review Summary

> **Files**: `platform/*.kt`, `data/perception/Perceptor.kt`, `domain/models/Models.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. AccessibilityNodeInfo Lifecycle Not Managed (CRITICAL)
**Consensus**: All three reviewers
**Location**: `Perceptor.kt:35-42`, `AccessibilityPlatform.kt:32-38`

**Problem**: AccessibilityNodeInfo objects have strict lifecycle requirements:
1. Must be recycled via `recycle()` after use
2. Become invalid after UI changes

The code keeps references in `ScreenSnapshot.rawMap` and `rootOriginal`:
```kotlin
data class ScreenSnapshot(
    val rootOriginal: AccessibilityNodeInfo?,  // Warning comment exists!
    val rawMap: Map<Int, AccessibilityNodeInfo>  // Never recycled!
)
```

**Impact**: 
- Memory leaks (nodes accumulate)
- Stale node errors when interacting with recycled nodes
- Crashes from accessing invalid nodes after UI changes
- Long session degradation/OutOfMemoryError

**Fix**: 
- Option 1: Don't store nodes - use indices to re-fetch when executing action
- Option 2: Store only essential data (bounds, text, class) not raw nodes
- Clear references immediately after perception phase

**Team Note**: ✅ Already fixed in data_infra work. ScreenSnapshot no longer stores `rootOriginal` or `rawMap`. Perceptor properly recycles child nodes via `shouldRecycle` parameter. AccessibilityPlatform re-queries tree at action time.

---

### 2. performType() May Double-Click Before Setting Text
**Reviewer**: Claude
**Location**: `AccessibilityPlatform.kt:107-133`

**Problem**: If initial ACTION_SET_TEXT fails, code clicks to focus then retries. Issues:
1. `clickNode()` might navigate away (if element is a button)
2. No delay between click and set text
3. Node reference may become stale after clicking

**Impact**: Text may be entered in wrong field or lost entirely.

**Fix**: Use ACTION_FOCUS instead of click, add small delay for focus to take effect.

**Team Note**: ✅ Already fixed in data_infra work. `performType()` now uses gesture-based tap (not ACTION_CLICK), adds 100ms delay for focus, and re-queries the tree for fresh node. ACTION_FOCUS is not universally supported; tap-to-focus is more reliable across different apps/widgets.

---

### 3. Scroll Gesture May Trigger System Actions
**Reviewer**: Claude
**Location**: `AccessibilityPlatform.kt:135-171`

**Problem**: Scroll coordinates use arbitrary safe zones (0.35 top, 0.15 bottom) that may not be correct for:
- Tablets with different gesture areas
- Phones with notches
- Gesture navigation vs 3-button navigation
- Landscape mode

**Impact**: Scroll may trigger notification shade, navigation gestures, or other system actions.

**Fix**: Use WindowInsets API to get actual system gesture exclusion zones:
```kotlin
val gestureInsets = windowInsets?.systemGestureInsets ?: Insets.NONE
```

**Team Note**: Add TODO comment documenting this limitation for now. The current hardcoded safe zones (0.35-0.85 for vertical scroll) work reasonably well for standard phone form factors. WindowInsets API requires API 29+ and view-based access which is complex in AccessibilityService context. Skip for now, revisit if scroll issues are reported.

---

### 4. dispatchGesture() May Not Resume Continuation
**Reviewer**: Claude
**Location**: `AccessibilityPlatform.kt:241-261`

**Problem**: If `dispatchGesture` returns true but callback is never invoked (edge case on some devices), the coroutine hangs forever.

**Impact**: Agent hangs indefinitely.

**Fix**: Add timeout:
```kotlin
withTimeoutOrNull(GESTURE_TIMEOUT_MS) { ... } ?: ActionResult.Failure("Gesture timed out")
```

**Team Note**: Fix it. Add 5 second timeout to `dispatchGesture()` using `withTimeoutOrNull`. This prevents the agent from hanging if gesture callback is never invoked.

---

### 5. Perceptor MAX_ELEMENTS May Miss Critical Elements
**Reviewer**: Claude
**Location**: `Perceptor.kt:16`, `Perceptor.kt:31`

**Problem**: Elements collected via DFS and capped at 80. Cap might exclude critical elements:
- Button at bottom of long list
- Submit button after form fields
- Dialog buttons (rendered late in traversal)

**Impact**: Agent may not see actionable elements, causing failure or wrong actions.

**Fix**: Prioritize interactive elements - collect clickable/editable first, then fill remaining with non-interactive text elements.

**Team Note**: Add TODO for now. The current DFS approach works for most screens. Prioritization would require two-pass traversal or post-traversal sorting. Skip for now, revisit if missing interactive elements becomes a real issue.

---

## Medium Issues (Should Fix)

### M1. PerceptionElement.bounds Uses IntArray
**Reviewer**: Claude
**Location**: `Models.kt:46-57`

Using `IntArray` loses type safety. No compile-time enforcement of array size.

**Fix**: Use named data classes:
```kotlin
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class Point(val x: Int, val y: Int)
```

**Team Note**: Fix it. Change `bounds: IntArray` to `bounds: Bounds` and `center: IntArray` to `center: Point`. This also fixes M2 (IntArray equality issue).

---

### M2. ScreenSnapshot Equality Broken by IntArray
**Reviewer**: Claude
**Location**: `Models.kt:39-44`

Data class `equals()` won't work correctly due to IntArray reference equality.

**Fix**: Use `List<Int>` or override equals/hashCode.

**Team Note**: ✅ Will be fixed by M1 fix above (using Bounds/Point data classes instead of IntArray).

---

### M3. UIAction.ClickAt Not Used
**Reviewer**: Claude
**Location**: `UIAction.kt:21-25`

`UIAction.ClickAt` defined but no tool uses it. All clicks use element index.

**Fix**: Add coordinate-based click tool or remove ClickAt.

**Team Note**: Keep it. ClickAt is used internally by `performClick()` and could be useful for future coordinate-based tools. It doesn't hurt to have it defined. No action needed.

---

### M4. hasRequiredPermissions() Insufficient
**Consensus**: Claude, Gemini
**Location**: `AccessibilityPlatform.kt:53-55`

Only checks `serviceInfo != null`. Doesn't verify overlay permission or actual gesture capability.

**Fix**: Also check `Settings.canDrawOverlays()` and use AccessibilityManager for full verification.

**Team Note**: Add TODO. Overlay permission check should be done at MainActivity level, not in AccessibilityPlatform. The current check is sufficient for AccessibilityPlatform's scope. Skip for now.

---

### M5. Perceptor.normalizeWhitespace() Removes Intentional Spacing
**Reviewer**: Claude
**Location**: `Perceptor.kt:119-121`

Converts "Price:  $99" to "Price: $99", removes meaningful newlines.

**Fix**: Be more conservative - only collapse horizontal whitespace, preserve single newlines.

**Team Note**: Fix it. Change from `\s+` to `[ \t]+` to only collapse horizontal whitespace, preserving meaningful newlines. Also add a separate `\n+` pattern to collapse multiple newlines to single newline.

---

### M6. Unused Model Classes
**Reviewer**: Claude
**Location**: `Models.kt`

Several model classes appear unused (V1 remnants):
- `AgentAction` sealed class
- `ValidationOutcome` sealed class
- `ManagerResult` data class

**Fix**: Remove unused classes or mark deprecated.

**Team Note**: Remove unused classes (`AgentAction`, `ValidationOutcome`, `ManagerResult`). They are V1 remnants not used by current architecture.

---

### M7. Inefficient JSON Generation
**Reviewer**: Gemini
**Location**: `Perceptor.kt:46`

`toPromptJson` creates full object graph (JSONArray, JSONObject) just to convert to string. Generates significant garbage on every turn.

**Fix**: Use streaming JSON writer (JsonWriter or Gson/Moshi streaming).

**Team Note**: Add TODO. Current implementation is simple and works. JSON serialization is not a bottleneck compared to LLM latency. Skip for now, revisit if profiling shows it's a real issue.

---

### M8. Element Lookup Safety
**Reviewer**: Gemini
**Location**: `AccessibilityPlatform.kt:82`

`snapshot.rawMap[action.elementIndex]` assumes map is valid. If screen changed between perception and action, index might be stale.

**Fix**: Use resource IDs or more robust selectors where possible.

**Team Note**: ✅ Already fixed in data_infra work. No longer uses `rawMap` - actions now use stored coordinates from PerceptionElement and re-query tree when needed (e.g., for text input).

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| ActionResult could include timing | Claude | `ActionResult.kt` | Add `durationMs` field |
| Perceptor should be injectable | Claude | `Perceptor.kt:14` | Interface + implementation for testing |
| SystemButtonType missing POWER | Claude | `UIAction.kt:80-84` | Add for screen lock |
| DisplayInfo could include orientation | Claude | `AndroidPlatform.kt:58-62` | Help tools adjust for landscape |
| Hardcoded gesture durations | Gemini | `AccessibilityPlatform.kt` | Make configurable or adaptive |

---

## Open Questions

1. **Node caching**: Is keeping AccessibilityNodeInfo references ever intentional for performance? Current approach seems designed for single-use.
**Answer**: No longer relevant - we no longer cache nodes.

2. **Multi-window support**: How does system handle split-screen or floating windows? `rootInActiveWindow` only gets one window.
**Answer**: Known limitation. Agent currently only works with active window. Multi-window support can be added later if needed.

3. **Accessibility service stability**: What happens if service is unbound mid-operation? Is there error handling?
**Answer**: Operations will fail with exceptions caught at higher levels. AgentSession handles service disconnection gracefully.

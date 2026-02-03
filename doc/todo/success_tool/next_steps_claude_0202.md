# Next Steps: Tool Improvements (2026-02-02)

Based on the current codebase state and previous analysis documents, this document provides prioritized next-step recommendations.

## Current State Summary

### Completed Since Last Analysis

**Multi-Selector Targeting (DONE)**
- `ClickTargetInvocation`, `LongPressTargetInvocation`, `TypeTargetInvocation` all support fallback order: bounds → coordinates → resource_id → text → element_index
- `MultiSelectorTargeting.kt` provides shared selector parsing and element lookup utilities
- MiniTap-style resource_id/target_text mismatch detection in type action
- `target_text` parameter for type action (avoids conflict with `text` payload)
- Unit tests cover selector ordering and key mismatch behavior

**Perception Improvements (DONE)**
- `resource_id_index`, `text_index`, `desc_index` precomputed in prompt JSON
- `enabled`, `focused`, `long_clickable` flags added
- Keyboard node filtering for common IME resourceId prefixes
- Off-screen filtering + bounds clipping using screen dimensions
- Minimum size threshold (5px) filtering

**Other Improvements (DONE)**
- `clear` option for type action
- `agent_thought` parameter on all tools
- Attempt logging on failure for debugging
- Element-not-found messages include available indices
- Swipe coordinates clamped to screen bounds

### What's Still Missing

| Feature | Priority | Effort | Impact |
|---------|----------|--------|--------|
| Scroll helpers (direction-based) | P1 | Medium | High |
| Out-of-bounds explicit errors | P1 | Low | Medium |
| Tool-level retry with backoff | P1 | Medium | High |
| Post-type verification | P2 | Medium | Medium |
| Overlap-aware tap point | P2 | Medium | Medium |
| Memory tool | P2 | Medium | High |
| Code duplication cleanup | P2 | Low | Low |

---

## Recommended Next Steps

### Priority 1: Scroll/Swipe Helpers

**Problem:** Current swipe only accepts raw pixel coordinates. The LLM must calculate exact start/end points, which often leads to errors.

**Recommendation:** Add `scroll` action as a semantic wrapper around swipe.

```kotlin
// Schema addition to mobile_action
"scroll" to mapOf(
    "direction" to "up|down|left|right",  // required
    "distance" to "short|medium|long",     // optional, default medium
    "element_index" to Int?                // optional, scroll within element
)
```

**Implementation approach:**
1. Add `ScrollActionHandler.kt` with direction → swipe coordinate mapping
2. Use screen center as default scroll origin (50%, 50%)
3. Distance presets: short=15%, medium=40%, long=70% of screen
4. If `element_index` provided, scroll within element bounds

**Reference:** MiniTap's `swipe_percentages` and M3A's `scroll(direction, index?)` both provide direction-based abstractions.

---

### Priority 2: Tool-Level Retry with Backoff

**Problem:** Transient failures (accessibility refresh, gesture timing) cause unnecessary task failures.

**Recommendation:** Add configurable retry for captureScreen and gesture-based actions.

```kotlin
// In ToolExecutionConfig or AccessibilityPlatform
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 200,
    val backoffMultiplier: Double = 2.0
)
```

**Safe retry conditions:**
- captureScreen: always safe to retry
- Coordinate-based click/swipe: safe if gesture failed (not if element found and clicked)
- Element-based actions: re-validate target still exists before retry

**Reference:** DroidRun's get_state() has built-in 3-retry with 0.5s delay.

---

### Priority 3: Post-Action Verification for Type

**Problem:** Type action reports success but text may not appear (keyboard issues, field validation, etc.).

**Recommendation:** Add optional post-type verification.

```kotlin
// In TypeTargetInvocation
suspend fun verifyTextInput(
    context: ToolExecutionContext,
    expectedText: String,
    targetResourceId: String?
): Boolean {
    delay(100) // settle
    val newSnapshot = context.platform.captureScreen()
    val element = targetResourceId?.let { id ->
        newSnapshot.elements.find { it.resourceId == id }
    }
    return element?.text?.contains(expectedText, ignoreCase = true) == true
}
```

**Output enrichment:**
```
"Typed 'hello'. Verified: field now contains 'hello world'."
// or
"Typed 'hello'. Could not verify: field text unchanged."
```

**Reference:** MiniTap's `focus_and_input_text()` re-reads hierarchy and returns full field content when resource_id is provided.

---

### Priority 4: Out-of-Bounds Explicit Errors

**Problem:** When bounds are partially or fully off-screen, the current behavior is silent clipping. This can cause unexpected tap locations.

**Recommendation:** Add explicit errors when bounds are problematic.

```kotlin
// In ClickTargetInvocation, before attempting bounds selector
fun validateBounds(bounds: Selector.Bounds, displayInfo: DisplayInfo): ValidationResult {
    val clipped = clipToScreen(bounds, displayInfo)
    return when {
        !intersectsScreen(bounds, displayInfo) -> 
            ValidationResult.Error("bounds fully off-screen")
        clipped.area() < MIN_TAP_AREA ->
            ValidationResult.Error("bounds too small after clipping (${clipped.width}x${clipped.height})")
        bounds != clipped ->
            ValidationResult.Warning("bounds clipped to screen: $clipped")
        else -> ValidationResult.Ok
    }
}
```

**Behavior options:**
- Hard fail: return error immediately
- Warn + proceed: include warning in tool output, attempt clipped bounds
- Configurable: let prompt context decide strictness

---

### Priority 5: Memory Tool

**Problem:** Cross-app data transfer requires clipboard manipulation or external state.

**Recommendation:** Add lightweight in-session memory tool.

```kotlin
class MemoryTool : MultiActionTool() {
    override val name = "memory"
    override val actionHandlers = mapOf(
        "save" to SaveHandler(),   // save(key, content)
        "read" to ReadHandler(),   // read(key)
        "list" to ListHandler()    // list()
    )
}
```

**Design considerations:**
- Session-scoped (clears on session end)
- Max 10 items, FIFO eviction
- Format guidance in description: "At step N, I found [X] in [Y]"
- Keys must be descriptive (e.g., "email_address", "confirmation_code")

**Reference:** DroidRun's `remember(info)` + `get_memory()` and MiniTap's `save_note/read_note/list_notes`.

---

### Priority 6: Overlap-Aware Tap Point Selection

**Problem:** When elements overlap (e.g., FAB over list item), center tap hits the wrong element.

**Recommendation:** Port DroidRun's `tap_on_index` logic.

```kotlin
// In tool/helpers/Geometry.kt
object Geometry {
    fun findClearPoint(
        targetBounds: Bounds,
        blockers: List<Bounds>,
        depth: Int = 0
    ): Point? {
        val center = targetBounds.center()
        val blocked = blockers.any { it.contains(center) }
        
        if (!blocked) return center
        if (depth > 4 || targetBounds.area() < 100) return null
        
        // Try quadrants recursively
        return listOf(
            targetBounds.topLeftQuadrant(),
            targetBounds.topRightQuadrant(),
            targetBounds.bottomLeftQuadrant(),
            targetBounds.bottomRightQuadrant()
        ).firstNotNullOfOrNull { findClearPoint(it, blockers, depth + 1) }
    }
}
```

**Integration:**
- When clicking by element_index, check if any higher-index elements overlap
- If center blocked, find clear point within bounds
- Log when overlap avoidance is used

---

### Priority 7: Code Duplication Cleanup

**Problem:** `buildElementNotFoundMessage()` and `capturePostActionObservation()` are duplicated across invocation classes.

**Current state:** Extracted to `TargetingInvocationUtils.kt` (as noted in multi_selector_targeting_review.md).

**Remaining work:**
- Verify `TargetingInvocationUtils` is used consistently
- Consider extracting description building to `ActionDescriptionFormatter`
- Document the shared utilities in code comments

---

## Implementation Order Recommendation

1. **Scroll helpers** (highest impact, enables navigation-heavy tasks)
2. **Out-of-bounds errors** (quick win, improves debugging)
3. **Tool-level retry** (resilience improvement, reduces flaky failures)
4. **Post-type verification** (improves type reliability feedback)
5. **Overlap-aware tap** (handles complex UI layouts)
6. **Memory tool** (enables cross-app workflows)
7. **Code cleanup** (technical debt, do alongside other work)

---

## Validation Checklist (Post-Implementation)

- [ ] Scroll up/down/left/right on list screen
- [ ] Scroll within specific scrollable element
- [ ] Tap on element with overlapping FAB
- [ ] Type + verify in text field with validation
- [ ] Retry on transient captureScreen failure
- [ ] Cross-app data transfer using memory tool
- [ ] Error message when bounds fully off-screen

---

## References

- `success_tool_comparison_codex.md` - Cross-system comparison table
- `tool_improvement_plan_claude.md` - Detailed implementation plans
- `multi_selector_targeting_review.md` - Review of multi-selector implementation
- `recommendations_codex.md` - Perception alignment recommendations
- `.reference/mobile_agent/minitap-mobile-use/` - MiniTap source code
- `.reference/mobile_agent/droidrun/` - DroidRun source code

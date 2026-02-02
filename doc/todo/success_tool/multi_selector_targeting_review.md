# Review: Multi-Selector Targeting for long_press and type

Commit: `d0edc7d` - make click, long_press, and type all support multi-selector targeting (shared logic)

## Summary

This PR extends multi-selector targeting (previously click-only) to `long_press` and `type` actions. Key changes:

1. Created `MultiSelectorTargeting.kt` - shared selector parsing and element lookup utilities
2. Created `LongPressTargetInvocation.kt` - multi-selector long press execution with fallback
3. Created `TypeTargetInvocation.kt` - multi-selector type execution with focus-first pattern
4. Added `UIAction.LongClickAt` for coordinate-based long press
5. Split action handlers into `tool/impl/mobileaction/` package for better organization
6. Updated `ActionDescriptionFormatter` for new targeting parameters

Files changed: 15 | +1303 / -417 lines

## Comparison with Proposed Design

| Aspect | My Proposal | Actual Implementation |
|--------|-------------|----------------------|
| Shared selector utility | `SelectorResolver` with `ResolvedTarget` sealed class | `MultiSelectorTargeting` with `Selector` sealed interface + `Attempt` |
| Resolution approach | Resolve to target first, then create action | Build attempt list, iterate with early return |
| Type text-based targeting | Conflicted with `text` param | Uses `target_text` param (better) |
| ID/text mismatch detection | Not included | Included (MiniTap pattern) |
| Coordinate-based type | `TypeAt` UIAction | Click-to-focus + Type (simpler) |
| Handler organization | Single file | Separate files per handler (better) |

**Verdict:** The actual implementation makes better design choices, especially:
- Using `target_text` to avoid parameter conflict with `text` (the payload)
- Including MiniTap's resource_id/target_text mismatch detection
- Not adding `TypeAt` - click-to-focus is simpler and works

## Critical

None found.

## High

1. **Code duplication across invocation classes**

   `buildElementNotFoundMessage()` and `capturePostActionObservation()` are duplicated across `ClickTargetInvocation`, `LongPressTargetInvocation`, and `TypeTargetInvocation`.

   **Files:**
   - `ClickTargetInvocation.kt:137-162`
   - `LongPressTargetInvocation.kt:178-203`
   - `TypeTargetInvocation.kt:252-277`

   **Fix:** Extract to shared utility in `MultiSelectorTargeting.kt` or a new `TargetingUtils.kt`:

   ```kotlin
   internal object TargetingUtils {
       fun buildElementNotFoundMessage(index: Int, snapshot: ScreenSnapshot): String { ... }
       suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? { ... }
   }
   ```

2. **Inconsistent snapshot null handling**

   - `ClickTargetInvocation` requires snapshot upfront (line 44-45), fails immediately if null
   - `LongPressTargetInvocation` and `TypeTargetInvocation` handle null per-selector

   This inconsistency means click will fail fast but long_press/type will try coordinate selectors first.

   **Files:**
   - `ClickTargetInvocation.kt:44-45`
   - `LongPressTargetInvocation.kt:46, 101-104`

   **Fix:** Either make all three consistent, or document the intentional difference. Click's stricter behavior is actually safer since even coordinate clicks benefit from snapshot validation.

## Medium

1. **Description building duplication**

   Similar description-building logic exists in:
   - `ActionDescriptionFormatter.kt` (for logging)
   - `buildClickDescription()` in `ClickActionHandler.kt`
   - `buildLongPressDescription()` in `LongPressActionHandler.kt`
   - `buildTypeDescription()` in `TypeActionHandler.kt`

   **Impact:** Maintenance burden, potential drift between logged description and handler description.

   **Suggestion:** Consider having handlers call into `ActionDescriptionFormatter` or vice versa, or extract shared description templates.

2. **Type action's `text_index` fallback could cause confusion**

   `TypeTargetInvocation.kt:108`:
   ```kotlin
   val targetTextIndexKey = if (params.has("target_text_index")) "target_text_index" else "text_index"
   ```

   This fallback allows `text_index` when `target_text` is provided, which could confuse LLM models that expect `text_index` to relate to the `text` parameter.

   **Suggestion:** Either remove fallback and require `target_text_index`, or document clearly in schema.

3. **Missing unit tests for new invocation classes**

   `LongPressTargetInvocation` and `TypeTargetInvocation` are complex with multiple code paths but no tests.

   **Suggestion:** Add tests covering:
   - Fallback order (bounds → coords → resource_id → text → element_index)
   - Each selector type success/failure
   - Type's target_text/resource_id mismatch detection
   - Null snapshot handling

4. **Large file: TypeTargetInvocation.kt (278 lines)**

   Approaches the 400-line guideline from CLAUDE.md. The mismatch detection logic (lines 108-139) could be extracted.

## Low

1. **Documentation not updated in doc/main/**

   `agent_infra.md` tool documentation should reflect new targeting capabilities for `long_press` and `type`.

2. **TODO in comparison doc line 49 outdated**

   `success_tool_comparison_codex.md:49` still says "Ours: long_press(index) with gesture; no long_press_at." but `long_press_at` via `LongClickAt` is now implemented.

   **Fix:** Already fixed in the same commit (good).

3. **Selector label inconsistency**

   - Click/LongPress use `text="$text"` format
   - Type uses `target_text="$text"` format

   This is intentional (different param names) but adds slight cognitive load when reading logs.

## Android-Specific Checks

- [x] Coroutines scoped correctly? Yes - uses suspend functions, no leaked scopes
- [x] No Context leaks? Yes - no Context references stored
- [x] Main thread safe? Yes - gesture dispatch and UI capture are handled correctly
- [x] Permissions checked? N/A - uses existing accessibility service
- [x] A11y service best practices? Yes - follows existing patterns

## Strengths

1. **Smart parameter naming**: Using `target_text` for type action's text-based targeting avoids conflict with `text` payload
2. **MiniTap mismatch detection**: Dropping resource_id when it doesn't match target_text is a defensive pattern from MiniTap
3. **Clean handler separation**: Moving to `mobileaction/` package improves maintainability
4. **Comprehensive validation**: Handlers validate all selector combinations properly
5. **Preserved fallback order**: Consistent `bounds → coords → resource_id → text → element_index` across all actions

## Recommendation

**APPROVE with suggestions**

The implementation is solid and follows good patterns. The code duplication (High #1) should be addressed before significant future changes, but is acceptable for now. Consider extracting shared utilities in a follow-up.

Priority fixes:
1. Extract `buildElementNotFoundMessage` and `capturePostActionObservation` to shared utility
2. Add unit tests for new invocation classes
3. Update `doc/main/agent_infra.md` with new targeting capabilities

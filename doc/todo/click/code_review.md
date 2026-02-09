# Review: Click Reliability Redesign + Bounds Removal

Covers commits `611f3a9..fd8601b` (3 commits) vs `33065e8`.

## Summary

Three commits that: (1) redesign click to use atomic API-level actions (`ClickNodeAt`, `TapAt`) with post-action UI change detection, (2) remove `bounds` (`x1,y1,x2,y2`) selector from all action types, and (3) minor skill doc update. The click changes are the core of this review.

**Scope**: 21 files changed, +621 / -353 lines. Touches platform layer (`UIAction`, `AccessibilityPlatform`), invocation layer (`ClickTargetInvocation`, `LongPressTargetInvocation`, `SwipeTargetInvocation`, `TypeTargetInvocation`), handler layer (all 4 `ActionHandler`s), shared utils, tool schema, tests, and docs.

---

## Critical

None.

---

## High

### H1. `resolveElement` double-lookup can return wrong element

`ClickTargetInvocation.kt:242-245`:

```kotlin
private fun resolveElement(snapshot: ScreenSnapshot?, index: Int): PerceptionElement? {
    if (snapshot == null) return null
    return snapshot.elements.firstOrNull { it.index == index }
        ?: snapshot.elements.getOrNull(index)
}
```

The fallback `getOrNull(index)` treats `index` as a **list position**, not an element index. If element indices are non-contiguous (e.g., `[0, 3, 7]`), then `resolveElement(snapshot, 1)` would return the element at list position 1 (which has `index=3`), silently clicking the wrong target.

**Fix**: Remove the `getOrNull` fallback. If `firstOrNull { it.index == index }` returns null, the element genuinely doesn't exist. The fallback is a silent wrong-click bug.

### H2. UI change detection false negatives - valid clicks that don't change UI

The new contract says click only succeeds when UI change is observed. But legitimate click scenarios exist where UI doesn't change within 300ms:
- Clicking to focus a field (only `isFocused` changes, which IS fingerprinted - ok)
- Clicking a button that launches an external activity/system dialog (transition hasn't rendered yet)
- Clicking a button whose effect is network-bound (loading spinner appears after >300ms)
- Re-clicking an already-toggled checkbox

These cases now exhaust all attempts and return `Failure`, causing the agent to waste turns re-trying or choosing a different strategy.

**Recommendation**: Consider treating dispatch success + no UI change as a "soft success" with a warning rather than retry trigger, OR make the settle delay configurable per-action, OR add a retry budget (e.g., only retry once per selector rather than burning through all attempts).

### H3. `ClickTargetInvocation` diverged from `MultiSelectorTargeting` - duplicated logic

Click now has its own inline selector resolution in `buildAttemptPlan()` while long_press/type/swipe still use `MultiSelectorTargeting.attemptsFromParams()`. This means:

- Text resolution logic (`findElementIndexByTextOrDescription`) is duplicated
- Element validation logic is duplicated
- Error message formatting is duplicated
- The two codepaths will drift over time (e.g., if you add `resource_id` selector, you'd need to add it in two places)

This is the architectural issue that needs the bigger refactor (see Refactor section below).

### H4. `UIAction.Click` and `UIAction.ClickAt` are now effectively dead for click path

`ClickTargetInvocation` never produces `UIAction.Click` or `UIAction.ClickAt`. But they still exist in `UIAction` and `AccessibilityPlatform`:

- `performClick()` still has its own internal ACTION_CLICK -> gesture tap fallback chain (the old behavior)
- `performClickAt()` is now a one-liner that forwards to `performTapAt()`
- `TypeTargetInvocation` still uses `UIAction.ClickAt` for focus

This means the platform has **two different fallback strategies** for clicking: the old one inside `performClick()` and the new one driven by `ClickTargetInvocation`. If someone uses `UIAction.Click` (e.g., future code or test), they get the old hidden-fallback behavior.

**Fix**: Deprecate `UIAction.Click` and `UIAction.ClickAt`. Migrate `TypeTargetInvocation`'s focus action to `UIAction.TapAt`. Remove `performClick()` and `performClickAt()` from platform once no consumers remain.

### H5. `LongPressTargetInvocation` still uses old pattern without UI change detection

`design_codex.md` rollout section says: "Apply same 'dispatch success != interaction success' contract to `long_press`." Current `LongPressTargetInvocation` still treats `ActionResult.Success` as immediate `ToolExecutionResult.Success` without any post-action change verification. This is inconsistent with the click contract.

Not blocking for this commit, but it's a follow-up that should be tracked.

---

## Medium

### M1. Three different inline `attempt` function patterns across invocations

Each invocation class has its own approach to attempt execution:

| Class | Pattern | UI Change Detection |
|-------|---------|---------------------|
| `ClickTargetInvocation` | `AttemptOutcome` sealed class + `executeAttempt()` | Yes |
| `LongPressTargetInvocation` | Inline `suspend fun attempt()` returning nullable | No |
| `TypeTargetInvocation` | Inline `suspend fun attemptType()` returning nullable | No |
| `SwipeTargetInvocation` | Direct `performAction` call + `detectScrollBoundary` | Boundary-only |

Each maps `ActionResult -> ToolExecutionResult` slightly differently. This is the "three ways to do the same thing" anti-pattern. A shared `AttemptExecutor` utility would reduce duplication and ensure consistent behavior.

### M2. `addAttemptIfNew` silently drops non-click actions

```kotlin
val key = when (action) {
    is UIAction.ClickNodeAt -> "ClickNodeAt:${action.x},${action.y}"
    is UIAction.TapAt -> "TapAt:${action.x},${action.y}"
    else -> return  // silently drops
}
```

The `else -> return` branch silently ignores any UIAction that isn't the two expected types. If this method is ever called with a different action (bug or refactor), the attempt vanishes with no log or error. Should at minimum log a warning, or better, make the function signature accept only the types it handles.

### M3. 4 UIAction click variants create confusion

Current click-related UIActions:
- `Click(elementIndex)` - element-based, has hidden internal fallback in platform
- `ClickAt(x, y)` - coordinate-based, forwards to `TapAt` (redundant)
- `ClickNodeAt(x, y)` - coordinate-based, ACTION_CLICK only (new, atomic)
- `TapAt(x, y)` - coordinate-based, gesture tap only (new, atomic)

The first two are legacy from the pre-redesign world. `ClickAt` is literally `performTapAt` now. `Click` is the only one with hidden fallback logic. These should be cleaned up as follow-up.

### M4. Missing isolated test for text-only click

Tests cover: coordinates-only, element_index with fallback, full combined fallback, UI change detection. No test for text-only click (no element_index, no x/y, just `text`). This is a valid and common LLM-produced action.

### M5. `hasActionableElementAt` is now dead code for click

The old click used `hasActionableElementAt` to skip non-actionable coordinates before attempting. The new click removed this precheck. This means click will now attempt `ClickNodeAt` + `TapAt` on coordinates that hit non-actionable elements (e.g., a plain `TextView` container). The attempt will likely fail anyway, but it wastes two attempts + two 300ms observation captures.

`hasActionableElementAt` is still used by... nothing in the current code after bounds removal. It's dead code in `MultiSelectorTargeting`. Either remove it or document why it's kept.

### M6. `snapshotFingerprint` allocates on every comparison

Each `detectUiChange` call creates two `List<String>` from all elements, sorts, and joins. For typical screens (50-200 elements) this is fine. For edge cases with 500+ elements, this runs on every attempt. Consider caching or hashing.

---

## Low

### L1. Stale comments

- `ClickActionHandler.kt:10`: "Click action - tap using multi-selector targeting with fallback order." - Click no longer uses `MultiSelectorTargeting`.
- `LongPressTargetInvocation.kt:12`: "using multi-selector fallback" is accurate but inconsistent with click's doc style.

### L2. Inconsistent indentation in `TargetingInvocationUtils`

Some methods use 8-space indentation (IDE auto-format?) while the rest of the codebase uses 4-space. See `detectUiChange`, `snapshotFingerprint`, `capturePostActionObservation`.

### L3. `design_codex.md` is pre-implementation

The design doc describes the plan accurately but doesn't note what was actually shipped vs. deferred. Could add a "Status" section marking it as implemented.

---

## Recommendation

**CHANGES_REQUESTED** - Fix H1 (`resolveElement` double-lookup bug) before merging. H2-H5 can be tracked as follow-up issues but deserve attention soon.

---

## Refactor Analysis: Do You Need a Bigger Refactor?

**Short answer: Yes, but scoped and incremental.**

### Current Structural Problems

The click redesign introduced good patterns (`AttemptOutcome`, `ClickAttempt`, `buildAttemptPlan`, UI change detection) but only for click. The other three invocations still use the old pattern. This leaves the codebase in a split state:

```
ClickTargetInvocation         LongPress/Type/Swipe
──────────────────────        ──────────────────────
Own selector resolution       MultiSelectorTargeting
AttemptOutcome sealed class   Inline nullable attempt()
UI change detection           No change detection
Atomic UIActions              Legacy UIActions
Deduplication                 No deduplication
```

### What's Actually Wrong

1. **Duplicated selector resolution** - Click reimplements text lookup, element lookup, and error formatting that already exists in `MultiSelectorTargeting`. Two codepaths to maintain for the same logic.

2. **Inconsistent success contracts** - Click requires UI change for success; long_press/type/swipe don't. Agent planner sees inconsistent reliability signals.

3. **UIAction surface area** - 4 click variants where 2 would suffice (`ClickNodeAt`, `TapAt`). `Click` and `ClickAt` are legacy holdovers with hidden fallback behavior.

4. **No shared attempt execution** - Each invocation class rolls its own `ActionResult -> ToolExecutionResult` mapping.

### Recommended Refactor Plan

**Phase 1: Consolidate attempt execution** (small, low risk)
- Extract shared `AttemptExecutor` from `ClickTargetInvocation.executeAttempt()` into `TargetingInvocationUtils`
- Make it optionally check UI change detection (click: yes, long_press: yes, type: no, swipe: has own)
- All invocations delegate to shared executor

**Phase 2: Generalize attempt plan building** (medium, moderate risk)
- Extend `MultiSelectorTargeting` to support per-action attempt ordering
- Click override: `element_index -> text -> coordinates` with per-selector API fallback
- Default (long_press/type/swipe): `coordinates -> text -> element_index`
- `ClickTargetInvocation.buildAttemptPlan()` becomes a thin config over the shared builder
- Delete duplicated text/element resolution from `ClickTargetInvocation`

**Phase 3: Clean up UIAction** (small, needs migration)
- Remove `UIAction.Click` and `UIAction.ClickAt`
- Migrate `TypeTargetInvocation` focus to use `UIAction.TapAt`
- Remove `performClick()` and `performClickAt()` from `AccessibilityPlatform`
- Apply atomic action pattern to long_press: `LongClickNodeAt(x, y, duration)` + `LongPressGestureAt(x, y, duration)`

**Phase 4: Apply UI change detection to long_press** (small, builds on Phase 1)
- Reuse the shared executor with change detection enabled

### Estimated Impact

- Phase 1: ~100 lines changed, 2-3 files
- Phase 2: ~200 lines changed, 5-6 files
- Phase 3: ~150 lines changed, 4-5 files
- Phase 4: ~50 lines changed, 1-2 files

Each phase is independently shippable and testable. Total: ~500 lines of focused refactoring to eliminate the split architecture.

### Should You Do It Now?

The current code works. The split is annoying but not blocking. I'd recommend:
1. **Fix H1 now** (5 min)
2. **Ship what you have** - the click reliability improvement is valuable
3. **Do Phase 1 + Phase 3 next** - highest ROI, removes dead code and shared the executor
4. **Phase 2 + 4 can wait** until you need to add a new selector type or action

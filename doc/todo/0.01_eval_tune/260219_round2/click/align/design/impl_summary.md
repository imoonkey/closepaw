# Click Redesign - Implementation Synced

## Status

Implemented in code (current baseline: commit `e9f9e78`), with one intentional deviation from the original aligned design.

## What Is Actually Implemented

### Core behavior

1. No retry chain.
2. No jitter.
3. No per-attempt UI change detection.
4. Success/failure is based on action return (`ActionResult`), not UI delta heuristics.

### Target resolution

`TargetResolver` behavior is unchanged from the Phase 1 simplification:

1. `Target.Coordinate` returns the given point.
2. `Target.ElementIndex` and `Target.Text` resolve to one point.
3. Occlusion risk is warning-only, not hard fail.
4. Missing semantic target returns `NotFound`.

### Click execution (actual)

Current `ClickExecutor` flow:

1. Resolve target once to `(x, y)`.
2. Validate display bounds once.
3. If target is semantic (`element_index` or `text`):
4. Try `UIAction.ClickNodeAt(x, y)` first.
5. If node click fails, fallback once to `UIAction.TapAt(x, y)`.
6. If target is coordinate, dispatch `UIAction.TapAt(x, y)` directly.
7. On success: settle delay once + capture once.

No loop and no UI-delta gate.

## Deviation From Original Design

### Original aligned design in this doc

The previous version specified:

1. Phase 1 uses `TapAt` only.
2. Phase 2 fallback is conditional and design-only.
3. Phase 2 fallback should resolve node directly from semantic target, not through `(x, y)`.

### Actual implementation difference

Current code already introduced a fallback-like behavior in main path for semantic targets:

1. It performs `ClickNodeAt(x, y)` first.
2. It falls back to `TapAt(x, y)` on failure.
3. Node click still uses coordinate-based node lookup in platform layer.

So today it is not "semantic target -> direct semantic node lookup". It is still "semantic target -> resolved point -> coordinate node lookup".

## Why Implementation And Design Differ

### Reason 1: Immediate reliability fix priority

Observed regressions after tap-only simplification (notably Settings flows) showed that relying only on gesture tap was not robust enough in real runs. Re-introducing node click first for semantic targets was the smallest high-impact recovery.

### Reason 2: Keep interface changes minimal

The current `UIAction` and `AndroidPlatform` contract for node click is coordinate-based (`ClickNodeAt(x, y)`). Keeping this contract avoided wider cross-platform refactor during quick stabilization.

### Reason 3: "Direct semantic node lookup" is a separate refactor

To fully match the original Phase 2 intent, platform-side semantic node lookup must be added. This is not hard, but it is a distinct slice of work:

1. Add semantic node action API path (not coordinate-only) to platform action surface.
2. Implement lookup in `AccessibilityNodeFinder` by semantic selectors.
3. Wire both `AccessibilityPlatform` and `VirtualDisplayPlatform`.
4. Add tests for semantic-node click path and fallback ordering.

### Reason 4: `element_index` is snapshot-derived, not native node id

`element_index` comes from perceptor post-processing and re-indexing, so it cannot be mapped as a stable native a11y node id. Direct semantic lookup must match by extracted element attributes rather than by index identity.

## Code Pointers (Current Truth)

1. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`
2. `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`
3. `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`
4. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityNodeFinder.kt`
5. `app/src/test/kotlin/com/moonkey/androidagent/tool/action/ClickExecutorTest.kt`

## Next Step To Fully Match Original Phase 2 Intent

If we want strict alignment with the previous design statement:

1. Implement semantic-direct node lookup path (`element_index` / `text+text_index`) in platform.
2. Keep current no-retry, no-jitter, no UI-delta policy unchanged.
3. Preserve fallback order agreed in current code path semantics.

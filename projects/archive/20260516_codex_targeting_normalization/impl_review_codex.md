# Codex Implementation Review

Reviewed diff `39d610a7..HEAD` against `projects/active/codex_targeting_normalization/design_codex.md`.

## Blocker

None.

## Major

1. Fixed: `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt:203` - coordinate-fallback results were still eligible for point-action semantic refinement. A missed text target with a coordinate hint could be matched by `findResolvedElement`, retargeted to a container, and dispatched through `ClickNodeAt`, violating the design rule that coordinate fallback skips semantic node-action channels. Recommendation: keep the `resolved.coordinateFallback` early return before any refinement; regression coverage is in `app/src/test/kotlin/ai/closepaw/tool/action/ClickExecutorTest.kt:482`.

2. Fixed: `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt:80` - the pre-dispatch display-bounds failure path dropped resolver warnings, so a semantic miss followed by an out-of-display coordinate fallback failed without telling the agent that coordinate fallback had been used. Recommendation: format that failure through `formatActionMessage(..., resolvedWarnings)`; regression coverage is in `app/src/test/kotlin/ai/closepaw/tool/action/ClickExecutorTest.kt:574`.

3. Fixed: `app/src/main/kotlin/ai/closepaw/tool/action/ScrollExecutor.kt:34` - scroll target resolution collapsed `NotFound`, `Ambiguous`, and `coordinateFallback=true` into a generic "target not found" failure. This hid contradictory semantic/coordinate evidence and dropped fallback warnings, despite scroll explicitly rejecting coordinate fallback. Recommendation: preserve resolver failure reasons and warnings while still refusing coordinate fallback as a scroll area; regression coverage is in `app/src/test/kotlin/ai/closepaw/tool/action/ScrollExecutorTest.kt:151` and `app/src/test/kotlin/ai/closepaw/tool/action/ScrollExecutorTest.kt:167`.

## Minor

1. `app/src/test/kotlin/ai/closepaw/tool/impl/MobileActionToolTest.kt:166` - the design's target-parsing matrix is not directly covered at the `MobileActionTool.createInvocation` boundary; most behavior is covered through validation and executor tests with manually constructed `Target` values. Recommendation: add an invocation-level parsing test if this parser changes again, especially for `element_index+x/y`, `text+x/y`, `element_index` without a hint, and default `Target.Text(..., coordinateHint=null)` equality.

## Nit

None.

## Notes

- The MobileActionTool validation matrix matches the design for semantic+x/y acceptance, semantic+semantic rejection, negative and partial coordinates, negative element indices, bounds selector rejection, and bare x/y scroll rejection.
- TargetResolver uses strict half-open containment for coordinate hints and returns `Ambiguous` for out-of-bounds hints.
- TypeExecutor keeps `SetTextOnNodeAt` for semantic-resolved targets and skips it only for resolver `coordinateFallback=true`.
- ActionDescriptionFormatter display priority now matches execution priority: `element_index`, then `text`, then legacy bounds, then point.
- Verification: `./gradlew testDebugUnitTest --tests ai.closepaw.tool.* --tests ai.closepaw.agent.ActionTargetTest` passed.

## Deferred

None.

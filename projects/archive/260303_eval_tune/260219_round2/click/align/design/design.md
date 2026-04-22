# Click Redesign - Final Aligned Proposal

## Status

DRAFT FOR FINAL REVIEW (Codex updated after full doc + code pass)

## Final Decisions

1. Remove click retry chain entirely in Phase 1.
2. Keep `dispatchGesture` tap (`UIAction.TapAt`) as the only click dispatch in Phase 1.
3. Target resolution for valid element/text targets must always produce `(x,y)`; no occlusion-based rejection.
4. Remove executor-level per-attempt UI change gating for click/long press.
5. Keep external contracts stable: `mobile_action` schema, `ToolRouter`, `AndroidPlatform`, `UIAction`.
6. Long press uses swipe-to-same-point (`UIAction.Swipe(x,y,x,y,duration)`), not `ACTION_LONG_CLICK`.
7. Phase 2 fallback is design-only and conditional: only implement if Phase 1 eval shows real gesture dispatch failures.
8. If Phase 2 is needed, fallback must resolve node directly from original semantic target (`element_index` / `text`), not from coordinate-to-node lookup.
9. No feature flag for this redesign; direct replacement with clean git revert path.

## Why These Decisions Match Current Code Reality

1. `dispatchGesture` failure is already rare and explicit in platform code (`Failed to dispatch`, `Cancelled`, timeout after 5s), so a heavy retry chain is disproportionate.
2. Current click failures are dominated by resolver rejection and no-op loop behavior, not by frequent hard dispatch failure.
3. Existing `ClickExecutor` complexity comes from repeated `captureScreen + UiChangeDetector`, jitter, and re-resolve; removing these directly addresses latency and instability.

## Target Resolution Contract (Phase 1)

For click and long press:

- `Target.Coordinate`:
  - Return given point.
- `Target.ElementIndex` / `Target.Text`:
  - If target does not exist in snapshot: return `NotFound`.
  - If target exists: always return a point.
  - Point selection order:
    1. center
    2. left-center
    3. right-center
    4. top-center
    5. bottom-center
    6. fallback center with warning

Important: "likely occluded" becomes warning metadata, never a hard failure when target exists.

## Click Execution Contract (Phase 1)

Pipeline:

1. Resolve target once.
2. Validate in-bounds once.
3. Dispatch one `UIAction.TapAt(x,y)`.
4. Wait settle delay once.
5. Capture screen once and return observation.

No:

- `ClickNodeAt` fallback
- jitter
- re-resolve
- looped retries
- per-attempt `UiChangeDetector` success gating

Result semantics:

- Success if dispatch returns `ActionResult.Success`.
- Failure if dispatch returns `ActionResult.Failure`.
- Cancelled if dispatch returns `ActionResult.Cancelled` or tool cancellation.
- Capture errors are surfaced as warning text with best-effort result mapping, not retried in executor.

## Long Press Contract (Phase 1)

Resolve target once, then execute:

`UIAction.Swipe(startX=x, startY=y, endX=x, endY=y, durationMs=duration)`

Rationale:

- Matches behavior used in reference implementations.
- Avoids reliance on `long_clickable=true` accessibility metadata, which is frequently inaccurate for real selectable file/item UIs.

## Phase 2 (Design Only, Conditional)

Implement only if Phase 1 evaluation shows non-trivial gesture dispatch failure count.

Trigger condition:

- Run AW core eval after Phase 1.
- If click errors are mostly "no task progress" but dispatch succeeds, do not implement Phase 2.
- If there are real dispatch failures (`dispatch false`, cancellation, timeout) at meaningful frequency, implement Phase 2.

Fallback design:

1. Phase 1 path fails at dispatch.
2. If original target was semantic (`element_index`/`text`), locate node directly by the same semantic target in current tree.
3. Execute `ACTION_CLICK` on that node once.
4. Return final result (no multi-retry loop).

Explicitly disallowed:

- semantic target -> coordinate -> `findClickableNodeAt(x,y)` fallback.

## File-Level Change Plan

Phase 1 implement now:

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt`
  - Change resolver API from nullable point to typed result (`Resolved` / `NotFound`) or equivalent.
  - Remove occlusion hard-fail path.
  - Keep simple candidate strategy with warnings.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`
  - Replace looped attempts with single dispatch flow.
  - Remove jitter/re-resolve and `UiChangeDetector` dependency for click success.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutor.kt`
  - Replace `ACTION_LONG_CLICK -> gesture` chain with single swipe-to-self long press.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionOutcome.kt`
  - Keep concise outcomes aligned to single-attempt behavior.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionInvocation.kt`
  - Keep mapping simple; no attempt trail stitching for removed retry chain.

Phase 1 unchanged:

- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AndroidPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`

Phase 1 optional cleanup:

- Keep `UiChangeDetector` for swipe verification paths if still referenced.
- Remove click-specific detector usage only.

## Verification Gate

Unit tests:

1. Valid element target never returns occlusion failure.
2. Missing element/text returns `NotFound`.
3. Click dispatch success path returns single-attempt success with observation.
4. Click dispatch failure returns single-attempt failure (no retries).
5. Long press sends swipe-to-self action.

Eval:

1. Run `aw_subset_core`.
2. Compare click-related `tool_failures` vs baseline.
3. Compare average click latency and worst-case tail.
4. Check known tasks: `SimpleSmsSend`, `ExpenseAddSingle`, `FilesMoveFile`.

Phase 2 decision:

- Only proceed if data shows meaningful real gesture dispatch failures after Phase 1.

## Alignment Status

Open design disagreements: none remaining in this draft.
Next step: Claude review and either APPROVE or request narrow edits.

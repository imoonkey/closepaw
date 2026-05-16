# Codex Targeting Normalization Design

## Goal

Codex-authenticated runs sometimes emit `mobile_action` calls with both a semantic target and raw coordinates, for example:

```json
{"action":"click","element_index":14,"x":540,"y":1230}
```

The current runtime rejects this during validation because `element_index`, `text`, and `x/y` are treated as mutually exclusive target methods. That wastes a turn even when the semantic target is otherwise valid.

We need to accept the common Codex shape without returning to the old multi-selector fallback chain. One action should still execute against one canonical target.

## Current State

- `MobileActionTool.validateTargetedAction()` counts `element_index`, `text`, and `x/y` and rejects when more than one is present.
- `MobileActionTool.parseOptionalTarget()` already chooses a single target in priority order: `element_index` -> `text` -> `x/y`.
- `TargetResolver` resolves exactly one `Target` into a point plus optional bounds and semantic hint.
- `PointActionExecutorCore` uses semantic targets for node actions and coordinate targets for gesture-only dispatch.
- `TypeExecutor` resolves targets independently and currently drops resolver warnings, bounds, and semantic hint after extracting the point.
- `ScrollExecutor` rejects explicit semantic targets that resolve without bounds today, but that is incidental rather than explicit.
- `ActionDescriptionFormatter` has a different display priority (`text` -> bounds -> point -> element), so dual-target calls can be described differently from how `MobileActionTool` would execute them.

## Approach

Normalize at the tool boundary.

Keep the executor contract simple: downstream action executors still receive one canonical target. The only new concept is that semantic targets may carry an optional coordinate hint supplied by the model.

Target priority:

1. `element_index`
2. `text`
3. `x/y`

Validation rules:

- `element_index` and `text` remain mutually exclusive. If both are present, reject as ambiguous.
- `x/y` must be complete and non-negative whenever either coordinate key appears.
- `element_index + x/y` is valid only when `element_index >= 0`.
- `text + x/y` is valid when `text` is non-blank.
- `element_index + text + x/y` is invalid because there are two semantic targets.
- `text_index` still requires `text`.
- Bounds selectors (`x1/y1/x2/y2`) stay rejected.
- `scroll` allows no target, a semantic target, or semantic target plus `x/y` hint. Bare `x/y` scroll is invalid because scroll is area-based.

Execution rules:

- If a semantic target resolves and the coordinate hint is absent, execute exactly as today.
- If a semantic target resolves and the coordinate hint lies inside the resolved bounds, execute the semantic target. Use node action first for click/long press. The coordinate is treated as redundant evidence.
- The in-bounds check uses strict half-open containment, matching Android `Rect.contains`: `left <= x < right && top <= y < bottom`. No slop in v1. If real evals show false ambiguity, tune later with evidence.
- If a semantic target resolves but the coordinate hint is outside the resolved bounds, fail before dispatch with an `Ambiguous` result. Do not guess.
- If a semantic target does not resolve and a coordinate hint exists, fall back to the coordinate with a warning and mark the result as `coordinateFallback=true`.
- If a semantic target does not resolve and no coordinate hint exists, fail as today.

For point actions (`click`, `long_press`), coordinate fallback must skip semantic node-action channels and use gesture channels only, because no semantic node was resolved.

For `type`, coordinate fallback is allowed and explicit:

- semantic resolved: keep the current direct `SetTextOnNodeAt` first, then tap-to-focus fallback;
- semantic missed + coordinate hint fallback: skip direct `SetTextOnNodeAt`, tap the coordinate to focus, then call `SetTextOnFocused`;
- propagate resolver warnings into the success/failure message so the agent sees that semantic targeting failed and coordinate fallback was used;
- pure coordinate `type` keeps current behavior unless implementation proves the same tap-to-focus path is safer.

For `scroll`, do not use coordinate fallback. Scroll targeting is area-based, not point-based. `element_index + x/y` can validate, but the canonical scroll target is still the element. If the explicit scroll element cannot resolve, or resolves through `coordinateFallback=true`, fail as an unresolved scroll target.

For `swipe`, no change. `start` and `end` are already the canonical coordinates.

## Components

### `Target`

Extend semantic target variants with an optional coordinate hint:

```kotlin
sealed interface Target {
    data class ElementIndex(
        val index: Int,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Text(
        val text: String,
        val textIndex: Int = 0,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Coordinate(val x: Int, val y: Int) : Target
}
```

Default arguments keep existing construction sites source-compatible and preserve equality when the hint is absent.

### `TargetResolver`

Resolve semantic targets first.

Add explicit result state for ambiguous evidence:

```kotlin
sealed interface ResolveResult {
    data class Resolved(
        val point: Point,
        val bounds: Bounds? = null,
        val semanticHint: SemanticTargetHint? = null,
        val warnings: List<String> = emptyList(),
        val coordinateFallback: Boolean = false
    ) : ResolveResult

    data class NotFound(val reason: String) : ResolveResult
    data class Ambiguous(val reason: String) : ResolveResult
}
```

Resolution behavior:

- semantic target resolves, no hint: return semantic `Resolved`.
- semantic target resolves, hint inside bounds: return semantic `Resolved`; do not use the hint point.
- semantic target resolves, hint outside bounds: return `Ambiguous("Ambiguous target: ...")`.
- semantic target misses, hint exists: return `Resolved(point = hintPoint, warnings = listOf("Used coordinate fallback after semantic target failed: ..."), coordinateFallback = true)`.
- semantic target misses, no hint: return `NotFound` as today.
- coordinate target: unchanged, `coordinateFallback=false`.

### `MobileActionTool`

Replace the one-of count check with canonical target validation:

- validate semantic count separately from coordinate presence;
- reject `hasElement && hasText`;
- validate complete, non-negative `x/y` independently whenever coordinates appear;
- keep rejecting negative `element_index` even if valid coordinates are present;
- attach `Target.Coordinate(x, y)` as `coordinateHint` when parsing a semantic target;
- keep descriptions focused on the canonical primary target, not the hint.

The tool description should stop saying coordinates are hard-mutually-exclusive. Use concrete wording:

> Prefer exactly one target. If both a semantic target and x/y are supplied, the semantic target is primary and x/y is treated only as a fallback hint.

### `PointActionExecutorCore`

Change the semantic-channel guard from target-shape based to resolved-semantics based:

```kotlin
if (channel.requiresSemantic && semanticHint == null) continue
```

Handle `ResolveResult.Ambiguous` the same way as `NotFound` at the action-result level (`ActionOutcome.Failed`), but preserve the distinct reason string.

This preserves current coordinate-only behavior and ensures semantic-miss coordinate fallback does not attempt `ClickNodeAt`/`LongClickNodeAt` without a resolved semantic node.

### `TypeExecutor`

Keep the current semantic-resolved path. Add an explicit coordinate-fallback path:

```text
semantic target resolved:
  SetTextOnNodeAt -> TapAt + SetTextOnFocused fallback

semantic target missed but coordinateHint fallback resolved:
  TapAt -> SetTextOnFocused

pure coordinate target:
  unchanged for this design
```

All `Resolved.warnings` must be appended to success and failure messages through `formatActionMessage`.

### `ScrollExecutor`

Reject coordinate fallback explicitly, not by accident:

```kotlin
if (resolved is Resolved && resolved.coordinateFallback) return null
```

Do not attach synthetic bounds to coordinate fallback results.

### `ActionDescriptionFormatter`

Align display priority with execution priority:

1. `element_index`
2. `text`
3. bounds, only for legacy trace readability
4. `x/y`

`ActionTarget` itself does not need a schema change; `decodeActionTarget()` already extracts all fields. Only formatter ordering changes.

This prevents logs or approval rows from saying "Click at (x,y)" when the runtime will use `element_index`.

### Docs

Update `doc/main/infra/tool/mobile_action.md` to document:

- semantic target plus coordinate hint is accepted;
- semantic target remains primary;
- coordinate fallback happens only after semantic resolution failure;
- contradictory semantic and coordinate evidence fails before dispatch;
- `scroll` never uses coordinate fallback from a semantic target.

## Interactions

Target normalization state machine:

| Input shape | Canonical result |
| --- | --- |
| no target | valid only where target is optional |
| `x/y` | `Target.Coordinate` |
| `element_index` | `Target.ElementIndex` |
| `text` | `Target.Text` |
| `element_index + x/y` | `Target.ElementIndex(coordinateHint)` |
| `text + x/y` | `Target.Text(coordinateHint)` |
| `element_index + text` | invalid |
| `element_index=-1 + x/y` | invalid |
| partial `x/y` | invalid |
| bounds selector | invalid |

Semantic resolution state machine:

| State | Transition |
| --- | --- |
| semantic resolves, no hint | execute semantic target |
| semantic resolves, hint inside bounds | execute semantic target |
| semantic resolves, hint outside bounds | fail `Ambiguous` before dispatch |
| semantic fails, hint exists | execute coordinate fallback with warning, except `scroll` fails |
| semantic fails, no hint | fail as today |

## Tasks

### `target-model-coordinate-hint`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/Target.kt`, `TargetResolver.kt`

Acceptance:

- semantic target classes accept optional coordinate hints;
- resolver prefers semantic resolution;
- resolver uses strict half-open containment for semantic/hint consistency;
- resolver returns `Ambiguous` for contradictory semantic/hint evidence;
- resolver falls back to hint only after semantic miss;
- coordinate fallback results carry `coordinateFallback=true` and a warning;
- existing coordinate-only results keep `coordinateFallback=false`.

Dependencies: none.

### `mobile-action-normalize-dual-target`

Scope: `app/src/main/kotlin/ai/closepaw/tool/impl/MobileActionTool.kt`

Acceptance:

- `click/long_press/type` accept `element_index + x/y` and `text + x/y`;
- `element_index + text` remains invalid;
- negative `element_index` remains invalid even with valid coordinates;
- partial or negative coordinates remain invalid;
- parsed target preserves canonical priority and attaches coordinate hints;
- `MobileActionTool.description` uses the new semantic-primary / coordinate-hint wording.

Dependencies: `target-model-coordinate-hint`.

### `point-executor-resolved-semantic-guard`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt`

Acceptance:

- semantic-resolved click still tries node action first;
- coordinate fallback skips node action and uses gesture;
- ambiguous semantic/hint evidence fails without dispatch;
- existing coordinate-only behavior is unchanged.

Dependencies: `target-model-coordinate-hint`.

### `type-executor-coordinate-fallback`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/TypeExecutor.kt`

Acceptance:

- semantic-resolved type keeps current `SetTextOnNodeAt` primary path;
- semantic-miss coordinate fallback uses `TapAt` then `SetTextOnFocused`;
- resolver warnings are included in type success/failure messages;
- pure coordinate type behavior is unchanged.

Dependencies: `target-model-coordinate-hint`.

### `scroll-coordinate-fallback-guard`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/ScrollExecutor.kt`

Acceptance:

- explicit semantic scroll targets still require resolved bounds;
- semantic-miss coordinate fallback is rejected explicitly;
- no synthetic bounds are created for coordinate fallback.

Dependencies: `target-model-coordinate-hint`.

### `target-description-alignment`

Scope: `app/src/main/kotlin/ai/closepaw/agent/ActionDescriptionFormatter.kt`

Acceptance:

- formatter priority matches runtime priority;
- dual `element_index + x/y` displays as element-targeted;
- dual `text + x/y` displays as text-targeted;
- legacy trace input with all selector fields displays `element_index` first.

Dependencies: `mobile-action-normalize-dual-target`.

### `targeting-tests-docs`

Scope: `app/src/test/kotlin/ai/closepaw/tool/impl/MobileActionToolTest.kt`, `app/src/test/kotlin/ai/closepaw/tool/action/*Test.kt`, `app/src/test/kotlin/ai/closepaw/agent/ActionTargetTest.kt`, `doc/main/infra/tool/mobile_action.md`

Acceptance:

- implement the concrete test matrix below;
- docs explain the canonicalization rule and `scroll` exception.

Dependencies: all implementation tasks.

## Test Matrix

### `MobileActionTool` validation

- `click element_index + x/y` -> valid.
- `click text + x/y` -> valid.
- `long_press element_index + x/y` -> valid.
- `type element_index + x/y + input_text` -> valid.
- `click element_index + text` -> invalid.
- `click element_index + text + x/y` -> invalid.
- `click element_index=-1 + x/y` -> invalid.
- `click x=10` with missing `y` -> invalid.
- `click x=-1, y=10` -> invalid.
- `click x1/y1/x2/y2` -> invalid.
- `scroll element_index + x/y` -> valid.
- `scroll bare x/y` with no semantic target -> invalid.
- description string contains semantic-primary / coordinate-hint wording.

### Target parsing

- `element_index + x/y` parses to `Target.ElementIndex(index, Coordinate(x, y))`.
- `text + x/y` parses to `Target.Text(text, textIndex, Coordinate(x, y))`.
- `element_index` only parses to `Target.ElementIndex(index, null)`.
- `Target.Text("x", 0)` equals `Target.Text("x", 0, null)`.

### `TargetResolver`

- semantic target resolves and hint is inside bounds -> semantic `Resolved` using element center, no warning.
- hint at `right - 1, bottom - 1` is inside.
- hint at `right, bottom` is outside.
- semantic target missing with hint -> coordinate fallback `Resolved`, `bounds=null`, `semanticHint=null`, warning present, `coordinateFallback=true`.
- semantic target missing without hint -> `NotFound`.
- text target follows the same inside / missing fallback rules.
- coordinate target behavior is unchanged.

### `PointActionExecutorCore`

- semantic resolves with hint inside bounds -> `ClickNodeAt` first.
- semantic missing with hint -> skip `ClickNodeAt`, use `TapAt`, warning appears in success message.
- pure `Coordinate` target behavior is unchanged.
- semantic resolves but hint outside bounds -> failed ambiguous result and no platform dispatch.

### `TypeExecutor`

- semantic resolves with hint inside bounds -> `SetTextOnNodeAt` first.
- semantic missing with hint -> `TapAt` then `SetTextOnFocused`.
- coordinate fallback warning appears in success/failure message.
- pure coordinate type behavior is unchanged.

### `ScrollExecutor`

- `scroll element_index=999 + x/y` with a missing target fails as unresolved scroll target.
- `scroll element_index=1 + x/y` resolves normally when the element exists.
- no coordinate fallback result is used as a scroll area.

### `ActionDescriptionFormatter`

- `element_index + x/y` -> "Click element N".
- `text + x/y` -> "Click text \"...\" (index 0)".
- legacy input with `element_index + text + bounds + point` -> "Click element N".

### Docs

- `doc/main/infra/tool/mobile_action.md` documents semantic-primary coordinate hints, coordinate fallback, ambiguity failure, and the `scroll` exception.

## Trade-offs

### Prompt-only fix

Rejected. The prompt already says to prefer semantic selectors and the tool description currently says `EXACTLY ONE`. Codex still emits dual targets. Runtime validation must be robust to the model shape we actually receive.

### Full multi-selector fallback chain

Rejected. The project deliberately moved away from implicit selector priority because it hides bad grounding and can execute a different target than the model intended.

### Always ignore coordinates when semantic exists

Too weak. It would fix the validation error, but it would not catch contradictory model output and would not use an explicitly supplied coordinate when the semantic index is stale.

### Use coordinates as primary when present

Rejected. It skips the safer semantic/node-action path and regresses overlap handling. Coordinates are a fallback hint, not the primary target.

### Slop around bounds

Rejected for v1. A fixed pixel slop is a magic number and a density-scaled slop is still speculative. Strict half-open containment is simpler and safer. Add slop only if eval traces show false ambiguity.

### Nested `target` schema

Rejected for this change. It may be cleaner long term, but it is a larger LLM interface migration. The flat schema is already deployed and works for both auth paths except this dual-target shape.

## Self-review

This design keeps the important invariant: executors operate on one canonical target. It handles the Codex-specific redundant-coordinate shape without reopening arbitrary multi-selector behavior. The review changes make the boundary semantics explicit, cover `type`, and turn scroll's no-coordinate-fallback behavior into a tested contract.

# Engineering Plan Review — Codex Targeting Normalization

Reviewer: Claude
Source: `projects/active/codex_targeting_normalization/design_codex.md`
Also checked: `projects/active/codex_targeting_normalization/cn/design_codex.md` (no translation drift — same Kotlin, same tables, same task scope; safe to follow either)

## Plan summary

Codex emits `mobile_action` calls that combine a semantic target (`element_index` or `text`) with raw `x/y`. Current validation rejects this as "more than one target." The plan relaxes validation to accept `semantic + x/y`, treats the coordinate as a redundant hint on the semantic target, and adds two new behaviors:

1. If the semantic target resolves and the coordinate hint falls outside the resolved bounds (plus ~24 px slop), fail before dispatch as "ambiguous."
2. If the semantic target fails to resolve and a coordinate hint exists, fall back to the coordinate via the gesture path (no node action) and surface a warning.

Executors continue to operate on one canonical target. The only model change is an optional `coordinateHint: Target.Coordinate?` on `Target.ElementIndex` and `Target.Text`.

Success = Codex dual-target calls no longer waste a turn; runtime invariants (one canonical target, semantic preferred, no implicit multi-selector chain) are preserved; ambiguous evidence still fails fast instead of silently picking the wrong target.

## What already exists

Verified against current code:

- `MobileActionTool.validateTargetedAction()` (app/src/main/kotlin/ai/closepaw/tool/impl/MobileActionTool.kt:104) — current one-of count check; correctly identified.
- `MobileActionTool.parseOptionalTarget()` (MobileActionTool.kt:198) — already returns `element_index` > `text` > `x/y`; correctly identified.
- `TargetResolver.resolve()` (TargetResolver.kt:28) — Coordinate path has `bounds = null, semanticHint = null`; semantic paths set both. The plan's fallback shape (`Resolved(point=hint, warnings=…, semanticHint=null)`, implicit `bounds=null`) is consistent.
- `PointActionExecutorCore` (PointActionExecutorCore.kt:90) — channel guard uses `target.isSemantic()`. Replacing with `semanticHint == null` is a clean local change.
- `ActionDescriptionFormatter.targetToString()` (ActionDescriptionFormatter.kt:26) — priority is `text` → `bounds` → `point` → `elementIndex`. Plan's call-out about description/execution divergence is correct.
- `ActionTarget` (ActionTarget.kt:11) — already a flat record; reordering its priority is straightforward.
- `Bounds` (model/Models.kt:9) — `left/top/right/bottom`, no `contains(Point)`; the in-bounds + slop check needs a tiny helper.

Not addressed by the plan but currently in the affected blast radius:

- `TypeExecutor.execute()` (TypeExecutor.kt:46) — discards `resolvedTarget.warnings`, `bounds`, and `semanticHint`. It also runs `SetTextOnNodeAt` first regardless of whether the resolution came from a semantic match or a coordinate fallback. See Finding F4.
- `ScrollExecutor.resolveScrollArea()` (ScrollExecutor.kt:119) — returns null when the resolved bounds are missing AND target was `ElementIndex/Text`. Combined with the new fallback returning `bounds=null`, scroll naturally rejects coordinate fallback. The design's intent works only because of this implementation detail; worth noting explicitly. See Finding F5.

## Findings

### F1 — Slop value is a magic number with no justification (Medium)

The plan picks "a small fixed slop, for example 24 px." Pixel slop does not generalize across device densities — 24 px on a 1080×2400 phone is ~3 mm; on a high-DPI tablet it is smaller; on lower-DPI devices it is larger. Worse, it does not scale with element size: 24 px is generous around a small icon and tight around a list row.

Concrete asks before implementation:
- Define the slop semantically. Options: (a) strict containment with no slop; (b) bounds inflated by `min(width, height) * 0.1` clamped to `[8, 48]` px; (c) one of dp-based.
- State the boundary policy explicitly: is the in-bounds check closed (`x in [left, right]`) or half-open?
- Add at least one test at the boundary (`hint == (right, bottom)`, `hint == (right+1, bottom)`).

KISS suggestion: start with strict containment, no slop. Codex sends element-center-ish coordinates that should sit inside; if they don't, the "ambiguous" classification is exactly the safety signal we want. Slop should only be added if a real eval case shows a false ambiguity.

### F2 — `NotFound("Ambiguous target: …")` conflates two distinct failure modes (Low/Medium)

Reusing `NotFound` for "semantic was found but contradicts the hint" loses information at the boundary. Today, callers (`PointActionExecutorCore`, `ScrollExecutor`, `TypeExecutor`) treat `NotFound` as "the target is missing" and pass the reason string through. After the change, the same channel will produce two different messages from the same enum case — one means "fix your grounding," the other means "your semantic and your coordinate disagree."

If we want the agent prompt loop to learn from this, the distinction matters. Two cheap options:

- Add a third variant `Ambiguous(reason)` and have executors map both `NotFound` and `Ambiguous` to `ActionOutcome.Failed`, but keep the distinction in trace/metric output.
- Or accept the conflation but require the reason string to start with a stable prefix (`"Ambiguous target:"`) and add a smoke test that locks the prefix so downstream parsers/dashboards can rely on it.

Either is fine. The design should pick one rather than leave it implicit.

### F3 — Validation logic change is under-specified (Low)

The current check is `if (count > 1) reject`. The new rule needs both "`element + text` is invalid" and "`element + x/y` and `text + x/y` are valid." The plan describes this with rules, not code. Concretely, the new validator must:

1. Reject `hasElement && hasText` first (ambiguity).
2. Independently validate `hasCoords` requires complete + non-negative `x/y` even when a semantic target is also present.
3. Continue to reject bounds (`x1/y1/x2/y2`).
4. Keep `element_index < 0` rejecting (not falling back to coords) — the design implies this but does not state it.

Please make rule (4) explicit. Otherwise an implementer could read "semantic+coord valid" and decide that a negative `element_index` with valid coords should silently use coords.

### F4 — Type's coordinate-fallback path is not covered (Medium)

`TypeExecutor` is in scope (`mobile-action-normalize-dual-target` accepts `type + element_index + x/y`), but the design's "no node action on coordinate fallback" rule lives only in `PointActionExecutorCore`. Two issues:

1. `TypeExecutor.execute()` drops `resolvedTarget.warnings`. After the change, "used coordinate fallback" warnings will not surface to the agent on the `type` path. The model loses the signal that its semantic target was stale.
2. `TypeExecutor` always tries `SetTextOnNodeAt` first. After a coordinate fallback there is no resolved semantic node; `SetTextOnNodeAt` at a raw coordinate may still succeed if a focusable EditText happens to live there, but the symmetry with click/long_press is broken. The design's executor section only talks about point actions.

Pick one:
- Skip `SetTextOnNodeAt` when `semanticHint == null` (mirrors `PointActionExecutorCore` and is the safer default), AND propagate warnings into the type success/failure message.
- Or document that `type + element_index + x/y` is accepted at validation but type does not honor the coordinate fallback (it would fail closed once the semantic misses).

Right now the design implicitly allows the type call to "succeed via coordinate fallback" while silently swallowing the warning. That is the worst of both.

### F5 — Scroll's coordinate-fallback rejection is incidental, not enforced (Low)

The plan says scroll should not use coordinate fallback. Today this is true because `ScrollExecutor.resolveScrollArea()` rejects when `bounds == null && target is ElementIndex/Text`. The new resolver fallback returns `bounds = null` and the same check fires. Good.

But this is a load-bearing coincidence. If a future change makes the resolver attach a synthetic bounds around the hint point, scroll will silently start accepting coordinate fallback. Two cheap defenses:

- Add a TargetResolver field like `wasCoordinateFallback: Boolean` (or rely on `semanticHint == null && target !is Coordinate`), and have ScrollExecutor reject on that explicitly.
- Or just add a regression test: `scroll` with `element_index=999 + x=500 + y=500` and an empty/no-such-index snapshot returns `Failed("Scroll target not found …")`.

The test is the minimum bar.

### F6 — Description alignment is correct but slightly under-scoped (Low)

`ActionDescriptionFormatter.targetToString()` currently checks `target.text.isNotEmpty()` first. The plan says new priority is `element_index` → `text` → `bounds` → `x/y`. Two implementation notes the design does not call out:

- `decodeActionTarget()` (ActionTarget.kt:33) does not need a schema change — it already extracts all four fields. Only the formatter ordering changes. The task description mentions both files, which is correct, but make sure the implementer doesn't extend `ActionTarget` unnecessarily.
- The existing test `text plus bounds plus point resolves to text (priority order)` (ActionTargetTest.kt:113) will continue to pass because the input has no `element_index`. The plan should add an equivalent test for `element_index + text + bounds + point` to lock the new ordering — note `element_index + text` is invalid at the `MobileActionTool` boundary, but the formatter is also called for legacy trace replay where validation is bypassed. Be explicit about which inputs the formatter must handle.

### F7 — Prompt/tool-description update needs concrete wording in the task (Low)

The design suggests new wording for the tool description ("Prefer exactly one target. If both a semantic target and x/y are supplied, the semantic target is primary…"). That string lives in `MobileActionTool.description` (MobileActionTool.kt:28). The task `mobile-action-normalize-dual-target` lists the file but does not call out the description change in its acceptance criteria. Add it — easy to miss in a focused validator-only diff.

### F8 — Construction-site assertion is correct but worth a guardrail test (Low)

The design notes that default arguments keep existing construction sites unchanged. Verified — `Target.ElementIndex(idx)`, `Target.Text(text, textIndex)` callers remain source-compatible. But `data class` equality changes: anything comparing a `Target.Text("Save", 0)` produced by code to a `Target.Text("Save", 0)` produced by parsing remains equal only because both default to `coordinateHint = null`. Add one test that constructs both `Target.Text("x", 0)` and `Target.Text("x", 0, null)` and asserts equality, to lock the default and prevent a future "remove the default" refactor from silently breaking parser symmetry.

## Test plan

The design's test acceptance is too coarse ("tests cover accepted semantic+coordinate cases"). Concrete coverage I would require before merge:

**MobileActionTool validation**
- `click element_index + x/y` → valid
- `click text + x/y` → valid
- `long_press element_index + x/y` → valid
- `type element_index + x/y + input_text` → valid
- `click element_index + text` → invalid (ambiguous)
- `click element_index + text + x/y` → invalid
- `click element_index=-1 + x=10 + y=10` → invalid (lock F3.4)
- `click x=10 (missing y)` → invalid
- `click x=-1, y=10` → invalid
- `click x1/y1/x2/y2` → invalid (regression)
- scroll `element_index + x/y` → valid (no semantic+coord rejection)
- scroll bare `x/y` (no element_index) → invalid as today
- description string update is asserted (catches the F7 omission)

**Target parsing**
- parsed target for `element_index + x/y` is `Target.ElementIndex(idx, Coordinate(x, y))`
- parsed target for `text + x/y` is `Target.Text(text, textIndex, Coordinate(x, y))`
- parsed target for `element_index` only is `Target.ElementIndex(idx, null)`
- parsed target equality: `Target.Text("x", 0)` == `Target.Text("x", 0, null)` (F8)

**TargetResolver**
- `ElementIndex(14, Coordinate(540, 1230))` where element 14 exists and bounds contain `(540, 1230)` → `Resolved(point=element_center, bounds=element_bounds, semanticHint=set, warnings=empty)` (i.e., hint is dropped after validation)
- same but hint just inside the boundary (edge case for F1)
- same but hint just outside boundary (with chosen slop policy)
- `ElementIndex(99, Coordinate(540, 1230))` where 99 missing → `Resolved(point=(540,1230), bounds=null, semanticHint=null, warnings=["… used coordinate fallback …"])`
- `ElementIndex(99, null)` where 99 missing → `NotFound` (regression)
- `Text("Save", 0, Coordinate(...))` resolves to existing element, hint inside → semantic wins
- `Text("Nope", 0, Coordinate(...))` no match → coordinate fallback Resolved
- `Coordinate(x, y)` → unchanged behavior (regression)

**PointActionExecutorCore**
- semantic resolves, hint inside bounds → click attempts `ClickNodeAt` (semantic channel) first
- semantic fails, hint present → `ClickNodeAt` is skipped, `TapAt` is used; warning surfaces in the success message
- pure `Coordinate` target → unchanged (regression — skips node-action channel as today)
- semantic resolves, hint outside bounds + slop → `Failed("Ambiguous target …")`, no dispatch attempted (assert via fake platform that no `performAction` call was made)

**TypeExecutor** (only if F4 is addressed)
- `type element_index + x/y` semantic resolves → `SetTextOnNodeAt` first
- `type element_index + x/y` semantic fails → coordinate fallback; whatever channel order is decided in F4
- warning string appears in the success/failure message

**ScrollExecutor**
- `scroll element_index=999 + x=500 + y=500` with empty snapshot → `Failed("Scroll target not found …")` (F5 guardrail)
- `scroll element_index=1 + x=10 + y=10` resolves normally (regression)

**ActionDescriptionFormatter**
- `element_index + x/y` → "Click element N" (not "Click at (x,y)")
- `text + x/y` → "Click text \"…\" (index 0)"
- `element_index + text + bounds + point` (legacy trace input) → "Click element N" (locks new ordering)

**Docs**
- `doc/main/infra/tool/mobile_action.md` updated; manual diff review only.

## Not in scope

The design explicitly defers:
- Nested `target` schema migration (Trade-offs).
- Full multi-selector fallback chain (Trade-offs).

Also implicit and worth stating as out-of-scope:
- Slop tuning beyond a default constant (revisit if evals show false ambiguity).
- Adding a metric distinguishing coordinate-fallback executions from primary semantic resolutions. Useful for tracking Codex behavior over time but not required for the fix.
- Browser tool dual-target normalization (separate tool, separate selector model).

## Next step

REQUEST CHANGES. The core direction is sound and the canonical-target invariant is preserved, but three items must be addressed in the design before `/implement`:

1. **F1**: Pick a concrete slop policy (strict containment is the KISS default) and write the boundary semantics into the design.
2. **F4**: Decide whether `type` honors coordinate fallback. If yes, mirror the node-action-skip rule and propagate warnings in `TypeExecutor`. If no, narrow the validator to reject `type + semantic + x/y`, or document the asymmetry.
3. **Test plan**: Expand the `targeting-tests-docs` task acceptance to the concrete matrix above (or an equivalent list). The current acceptance bullets are too vague to pin behavior.

Smaller items (F2, F3, F5, F6, F7, F8) can be folded into the same revision or addressed during `/implement` if the implementer is briefed on them.

## Verdict

**REQUEST CHANGES**

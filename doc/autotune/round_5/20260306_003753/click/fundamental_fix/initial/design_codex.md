# Click Reliability Fundamental Fix - Initial Design (Codex)

Date: 2026-03-06

## Goal

Produce the strongest first-principles plan to make click execution reliable, using Files row open as the sharpest current symptom but fixing the right general problem:

- separate **our bugs** from **Android/app limits**
- stop reporting transport success as click success
- make semantic clicks target the intended widget, not just a coordinate
- prove the mechanism boundary before adding any app-specific workaround

Success means:

- standard clickable widgets work through a clean, auditable pipeline
- when a click still fails, we can classify why with evidence
- Files row click is either fixed by our runtime changes or cleanly proven to be outside our reachable platform envelope in that exact state

## Evidence Boundary

This design is grounded in:

- `doc/autotune/round_5/20260306_003753/click/qi_note.md`
- `doc/main/infra/tool/mobile_action.md`
- `doc/autotune/round_5/20260306_003753/click/history_and_probe.md`
- `doc/autotune/round_5/20260306_003753/click/final/click_alignment.md`
- raw Files probe artifacts under `debug-output/action-test/`
- relevant archive docs on prior mobile-action redesign, overlay interception, stale-node handling, and prior browser-task evidence

## Proven Facts

### Proven Android/app behavior limits

These are proven limits or lower-layer behaviors, not our runtime bugs:

1. `ACTION_CLICK` and synthetic tap are not equivalent on all widgets.
   Archive evidence already shows widgets where `node_action_click` no-ops while `gesture_tap` works.

2. In the clean Files probe on 2026-03-06, tap-like transport remains weak on the tested row:
   - `adb input tap` unchanged at row center, title center, and icon center
   - `gesture_tap` unchanged at row center, title center, and icon center

3. In that same clean Files probe, `node_action_click` still works at the icon hotspot `(94,761)` but not at row center or title center.

4. The clean Files probe rules out "only our overlay" for that specific case.
   There was no visible accessibility overlay window, and raw ADB tap still had no effect.

5. The clean Files probe does not prove a universal Files limitation.
   It proves only this: in that emulator/build/activity state, gesture-like taps were ineffective at the tested points, while `node_action_click` remained hotspot-sensitive and still worked at one point on the same row.

### Proven our logic/integration bugs

These are ours:

1. Historical false-success bug: dispatch acknowledgment was treated as action success.
   Round-5 work correctly moved point actions and scroll toward post-action verification.

2. Historical overlay pass-through bug: our own overlay could consume gesture touches.
   This was real and already proven by the overlay-touch work.

3. Historical semantic-target bug: the executor could resolve a non-actionable child and effectively click it as if it were the real target.
   Actionable-container promotion was the right direction.

4. Current architecture still has a proven design defect:
   - semantic target selection
   - point-based node lookup

   For semantic click, we currently refine to a point, then `NodeActionPerformer` re-finds a clickable node at that point. This is provably true from the current code path, even if it is not yet proven to be the direct root cause of the Files symptom. Semantic node dispatch should not depend on a second geometric heuristic.

### Open questions, not yet proven

1. Whether Files row open failure is caused by a DocumentsUI state precondition, a row subtype, or a missing hotspot-selection rule.
2. How often our current semantic point-selection heuristic misses valid activation hotspots on row-like surfaces.
3. Whether fixed settle windows (`300ms`, then `500ms`) still misclassify some real transitions as unchanged.

## Root Problem

The real problem is not "click is broken." The real problem is that click reliability requires three contracts, and our current system still mixes them:

1. **Semantic contract**
   Which widget do we mean?

2. **Delivery contract**
   Which mechanism can actually activate that widget on this surface?

3. **Effect contract**
   What observable evidence proves activation happened?

Files row click is the best current test because it stresses all three at once, and the updated probe now localizes the failure more sharply: semantic targeting can choose a plausible row point that is still the wrong activation hotspot.

## First-Principles Design

### Core invariant

A semantic click is successful only if all of these line up:

- the intended actionable target is identified
- the dispatched mechanism is bound to that same intended target
- the app receives the interaction
- the post-state matches an expected effect class

If any layer is missing, the result is not plain success. It must be classified.

### Design decision 1: Split semantic targeting from physical point targeting

This is the highest-value design change.

Current issue:

- `TargetResolver` resolves a semantic element
- `PointActionExecutorCore` may promote to a container
- `node_action_click` is still executed by finding "the clickable node at (x,y)"

That means semantic click uses geometry twice:

- first to pick a point
- then again to infer a node from the point

This is unnecessary and unstable.

New model:

- `SemanticClickPlan`
  - `requestedTarget`
  - `resolvedElement`
  - `actionableCandidate`
  - `interactionPoints`
  - `evidence`

- `actionableCandidate` is the semantic widget we intend to activate.
- `interactionPoints` are only for gesture-like delivery.

Result:

- node-action path dispatches to a re-queried node matched by semantic identity first
- gesture path dispatches to a planned point
- both channels share one target plan but not one dispatch heuristic

This matters directly for Files:

- the current probe says the row has at least one actionable hotspot where `node_action_click` works
- our current semantic point choice prefers center-like points
- therefore the likely regression is not "node click transport is broadly broken," but "semantic click planning is choosing the wrong point/hotspot for this row"

### Design decision 2: Replace "best clickable node at point" with target-class-specific selection

For semantic click, the runtime should not ask:

- "which clickable node contains this point?"

It should ask:

- "which live node matches the actionable candidate we already chose?"

Proposed matching order for semantic node dispatch:

1. exact bounds + class + resource id if available
2. exact bounds + label agreement
3. actionable ancestor/descendant relation consistent with planned candidate
4. only then geometric fallback near the planned point

This generalizes beyond Files. It fixes the architectural weakness that currently makes overlap, nested rows, and row-container indirection harder than they need to be.

### Design decision 3: Treat click result as a typed classification, not a boolean

Every click attempt should end in one of these classes:

- `effect_confirmed`
- `delivered_but_no_effect`
- `target_mismatch`
- `transport_blocked`
- `verification_unavailable`
- `out_of_platform_envelope`

`out_of_platform_envelope` is reserved for cases where we have clean evidence that:

- target identity was right
- transport was delivered
- our environment was clean
- all reachable synthetic click channels still no-op

Files row can only be called this after the full isolation protocol, not earlier.

### Design decision 4: Use an explicit click state machine

State machine:

1. `PlanTarget`
   Resolve semantic target or coordinate target.

2. `BindActionableCandidate`
   For semantic click, choose the actionable widget, not just a point.

3. `ChooseHotspot`
   For each channel, choose the best activation hotspot for that candidate instead of blindly using the geometric center.

4. `DispatchChannel`
   Try one channel with its own target form:
   - node channel: semantic node match
   - gesture channel: planned tap point
   - probe-only channel when debugging: raw ADB tap / shell input

5. `VerifyEffect`
   Compare post-state against effect signals.

6. `Classify`
   Decide whether failure is:
   - targeting
   - delivery
   - verification
   - platform/app limit

7. `FallbackOrStop`
   Retry only when the classification says retry is meaningful.

This turns recurring "patch here, regress there" behavior into one canonical pipeline.

### Design decision 5: Make verification effect-aware, not only fingerprint-aware

Whole-screen fingerprint change is necessary but not sufficient.

We should verify click effect using layered signals:

1. window/activity/package change
2. focused element change
3. target subtree mutation
4. global snapshot change
5. screenshot change when a11y is weak

Why:

- BrowserMultiply already showed that a real transition can be missed by the current post-capture timing
- some legitimate clicks keep most of the screen stable but still change focus, selection, or opened content

This does not mean adding app-specific rules. It means using better generic effect signals.

## Investigation Plan

### Phase 0: Clean-room reproducibility

Before changing behavior, every reproduction run must record whether these confounders are absent:

- accessibility overlay windows
- `pointer_location`
- `show_touches`
- stale selection mode
- nonstandard activity launch path

This is mandatory because overlay interception is a proven historical bug, but the Files probe shows it cannot be assumed as the explanation.

### Phase 1: Build a click mechanism matrix

For each investigated surface, record the same matrix:

- semantic target chosen
- actionable candidate chosen
- planned interaction point(s)
- hotspot policy used
- `node_action_click`
- `gesture_tap`
- raw ADB tap
- `long_press`
- observed effect class

Start with:

1. Files row open
2. a known `ACTION_CLICK`-fails-but-tap-works surface from archive evidence
3. a standard control that already works

This gives us all three regimes:

- node fails, gesture works
- both single-tap paths fail
- both work

Without that matrix, we risk overfitting Files.

### Phase 2: Prove or rule out our own hotspot-selection bugs

For Files row and any similar row target, capture:

- resolved child bounds
- promoted container bounds
- planned hotspot candidates
- chosen point per channel
- live node matched for node action
- long-press result at the exact same coordinates

If `node_action_click` works at one point on the same row but not at the center point we chose, that is strong evidence for a hotspot-selection problem rather than a broad node-click transport failure.

Current evidence already points that way for Files:

- icon hotspot node click changed state
- row center node click did not
- title center node click did not
- gesture and ADB taps remained unchanged at all tested points

So a global translation bug is not the leading explanation for that probe. Hotspot choice is.

### Phase 3: Separate node-channel failures from tap-channel failures

General classification rules:

- `node fails, gesture works`:
  Android/widget semantic click limitation; fix with channel policy, not point hacks.

- `node works, gesture fails`:
  likely overlay/interception/environment bug, gesture-injection weakness, or widget path that favors semantic node activation over physical tap.

- `node_click` works only at a specific hotspot while center-based selection fails:
  likely semantic hotspot-selection bug in our runtime.

- `adb tap` and `gesture_tap` fail across tested points while `node_click` succeeds at a hotspot:
  likely widget path where tap-like transport is weaker than node semantic activation.

- all channels fail and long-press also fails:
  likely wrong target, wrong state, or severe environment problem.

These rules explicitly separate platform/app limits from our bugs.

## Fix Plan

### P0: Instrumentation and typed evidence

Implement first.

Changes:

- add a `ClickAttemptReport`
- record:
  - target kind
  - resolved semantic element
  - actionable candidate
  - hotspot candidates considered
  - chosen hotspot and why
  - matched live node identity
  - dispatch channel
  - transport result
  - effect signals
  - final classification

Reason:

- reliability work without typed evidence degenerates back into guess-and-patch

### P1: Re-architect semantic click around actionable candidates

Implement next.

Changes:

- introduce semantic click planning before dispatch
- node channel re-queries by semantic candidate, not by point-only lookup
- add explicit hotspot selection for row/container-like targets instead of defaulting to center
- gesture channel uses the planned point from the same candidate

Reason:

- this is the cleanest general fix for nested child/container surfaces
- it removes a design flaw in the current click stack independent of Files
- it directly addresses the updated Files evidence without turning it into a Files-only hack

### P2: Strengthen generic effect verification

Changes:

- verify local target effect before falling back to global unchanged
- include window/activity/package transition signals
- include focus/selection/subtree mutation
- keep screenshot fallback for empty or weak trees

Reason:

- this reduces both false success and false unchanged
- it helps classify Files turn 12 style cases correctly

### P3: Formalize channel policy by evidence, not folklore

Changes:

- define channel selection policy from classified outcomes
- do not add Files-specific logic first

Initial generic policy:

- semantic click: node channel then gesture channel
- if node mismatch occurs, do not trust node success
- if gesture is blocked by environment, report contamination
- if node succeeds at one hotspot but fails at our default semantic point, classify as hotspot-selection bug first
- only if all semantically reasonable hotspots and channels fail in a clean setup should we classify as platform/app-limited for that state

Reason:

- fallback should be driven by what failed, not by app name

### P4: Only then add bounded app-surface tactics

Possible tactic if Files still remains outside the synthetic single-tap envelope:

- for file-row open surfaces, escalate from click failure to a semantically adjacent open strategy such as `long_press -> Open with`

But this is explicitly phase 4, not phase 1.

Reason:

- if applied too early, it hides our own runtime bugs
- if applied after the mechanism boundary is proven, it becomes a justified compatibility tactic

## What We Should Explicitly Not Do

1. Do not call Files row failure a generic click regression.
2. Do not blame overlay by default.
3. Do not keep semantic click implemented as point-only node rediscovery.
4. Do not add app-name conditionals before the mechanism matrix is complete.
5. Do not collapse all "unchanged" results into one bucket.
6. Do not interpret the updated Files probe as broad click transport failure; it now points first to hotspot-selection error plus channel asymmetry.

## Trade-offs

### Why this design wins

- It fixes the architectural confusion, not only the current symptom.
- It makes Files a strong test case without letting Files dictate the whole design.
- It gives us a principled boundary for "Android/app limitation" claims.

### Cost

- More structured executor state
- More diagnostics
- Slightly more implementation complexity in the click path

This is justified because click is foundational infrastructure, not a peripheral optimization.

## Recommended Execution Order

1. Add typed click attempt reporting.
2. Introduce semantic actionable-candidate planning.
3. Change node dispatch to semantic re-query instead of point-only rediscovery.
4. Upgrade effect verification beyond whole-screen fingerprint.
5. Re-run the clean Files matrix plus one known node-vs-gesture counterexample and one standard control.
6. Only then decide whether a bounded Files compatibility tactic is still needed.

## Self-Review

This design intentionally separates:

- **proven app/platform limits**
  synthetic single tap can fail even when node semantic click still works; `ACTION_CLICK` and tap are not equivalent

- **proven our bugs**
  overlay interception, false-success semantics, semantic-child/actionable-container mistakes, and likely wrong hotspot choice from center-biased semantic point selection

- **still-open questions**
  exact generic hotspot-selection rule that best predicts actionable points across row-like widgets

That separation is the core requirement. Without it, we will keep alternating between blaming Android for our bugs and patching around app behavior we have not actually isolated.

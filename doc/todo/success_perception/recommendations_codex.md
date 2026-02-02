# Recommendations: optimize a11y Perception and align it with our tools

## A) Confirmed current behavior (as of 2026-02-02)
- We extract `viewIdResourceName` and store it as `PerceptionElement.resourceId`.
- In prompt JSON we emit it as `id` (not `resource_id`).
- Our click fallback order in code is: `bounds -> x/y -> resource_id -> text -> element_index`.

## B) Align naming + schema: prompt JSON vs tool args
Goal: reduce LLM parameter drift and keep perception/tool/execution “speaking the same language”.

- Rename prompt JSON key `id` -> `resource_id`.
- Keep meanings stable:
  - `index` = our snapshot index
  - `resource_id` = Android view id string (what tools expect as `resource_id`)
  - `text` / `desc` / `bounds` / `center` = same semantics as tool params (and as our execution assumes)

## C) Keep elements that only have IDs
Even if we don’t want “resource_id first”, the snapshot still needs to contain ID-only nodes when they are useful targets.

- Update Perceptor “keep” logic so `resourceId.isNotBlank()` counts as content:
  - Keep nodes with IDs even if they have no text/desc and are not flagged clickable/editable/scrollable.
- Consider a max-per-resourceId cap (e.g. keep first N) if token budget becomes an issue.

## D) Add explicit “occurrence indices” (Minitap pattern)
Problem: `resource_id_index` / `text_index` exist in our tool schema, but the model must infer them by counting list occurrences.

- Precompute and emit per element:
  - `resource_id_index`: index among elements with same `resource_id`
  - `text_index`: index among elements with same `text` (case-insensitive)
  - (optional) `desc_index`: index among elements with same `desc` (case-insensitive)

This reduces flakiness when the same `resource_id` appears multiple times.

## E) Add lightweight filtering (DroidRun pattern)
To prevent large dumps and noisy nodes:
- Drop nodes that are fully off-screen.
- Drop nodes below a minimum size threshold (configurable).
- Optional: filter known keyboard nodes by `resourceId` prefix.
- Optional: clip bounds to screen to avoid “out of bounds” taps.

## F) Align perception output with our current fallback order (no need to change the order)
You said you don’t need `resource_id` first, so keep the current order and optimize perception/prompting so the order works well:

- Prompt policy: when producing a tool call, **prefer supplying exactly one “primary selector”** (and only include fallbacks when you intend them to be tried first).
  - If you include bounds/x/y, our executor will try them before resource_id/text.
- Perception output: make sure every element has enough data for whichever selector is likely to be used:
  - bounds/center should be valid (and ideally clipped) if we’re going to try bounds/x/y first
  - `resource_id_index` / `text_index` should exist if we’re going to try resource_id/text after that

## G) Why Minitap and DroidRun prioritize differently (and why that’s OK)
They are optimizing for different “primary selectors”, based on what their perception returns and what their executor can do reliably:

- **Minitap**:
  - Captures UI via UIAutomator2 and exposes a `Target` with bounds/resource_id/text (+ indices).
  - Its tap tool tries **bounds/coordinates first**, then resource_id, then text.
  - Rationale: a coordinate tap is fast and works even if IDs/text resolution is flaky; it also matches their “visual-first when bounds exist” design.

- **DroidRun**:
  - Captures a JSON a11y tree, then filters + formats it into an **indexed list** and caches it.
  - Primary action is **tap by index** (index -> bounds -> point), and it even supports overlap-aware point selection.
  - Rationale: index is stable within a `get_state` snapshot; they lean on filtering/formatting quality rather than multiple locator fallbacks.

Our system is closer to “multi-selector fallback” than to “index-only”, so we should steal:
- from Minitap: `*_index` semantics + mismatch defenses
- from DroidRun: filtering/clipping/visibility heuristics + overlap-aware tapping

## H) Future: overlap-aware tap point selection (DroidRun pattern)
If taps frequently land on occluding overlays:
- Add a “clear point within bounds” selection for gesture taps, using sibling/overlap heuristics.
- This can be a P1 after we stabilize selectors and filtering.

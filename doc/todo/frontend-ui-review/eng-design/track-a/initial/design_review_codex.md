# Track A - Codex Review of Claude Draft

Review target: `doc/todo/frontend-ui-review/eng-design/track-a/initial/design_claude.md`

## Findings

1. **High - The core `Turn -> Step* -> Final` model adds a second transcript architecture for a one-problem fix.**
   - Reference: `design_claude.md:18-32`, `54-80`, `84-102`.
   - The actual problem is narrow: chat drops `ThoughtUpdate`, so the durable row lacks the agent's intent. Claude's draft solves that by inventing a new transcript structure with `Step` objects plus a separate `Final` surface.
   - That adds new data shape, new surface boundaries, and per-step disclosure behavior where the existing chat can be upgraded with one agent row that contains `thought + actions + result`.
   - KISS read: this is more model than item 1 needs.

2. **High - The `MessageDelta` routing rule depends on semantics the current event stream does not provide.**
   - Reference: `design_claude.md:42-45`.
   - The spec says: before any action, `MessageDelta` goes into the open Step's thought; after an action, it goes into the `Final`.
   - Current protocol gives plain streamed text deltas, not an explicit "this is thought" vs "this is final answer" signal. That means the reducer would need inference rules, and those rules will be brittle for mixed turns.
   - This is the biggest correctness risk in the draft. A first-week ship should avoid semantic routing heuristics when the protocol does not encode the distinction.

3. **Medium - Per-step collapse creates too many controls for a turn that may only need one row-level disclosure.**
   - Reference: `design_claude.md:43`, `60-69`, `84-102`.
   - A multi-action turn becomes multiple expandable/collapsible Step surfaces plus a separate `Final` region.
   - That is still simpler than a debug console, but it is not the simplest UX that solves "show thought in chat." It shifts the design from "better agent row" to "mini execution timeline."
   - KISS read: one disclosure control on the completed agent row is easier to implement and easier to scan.

4. **Medium - Several extra affordances do not pay rent in v1.**
   - Reference: `design_claude.md:78-80`, `112`, `114`, `131`.
   - Separate `Final` hairline treatment and outcome footer (`12s · 4 steps`) add a second visual grammar.
   - Height-capped result with internal scroll + `Copy` button adds nested scroll behavior and a new control.
   - Step-number accessibility output adds bookkeeping tied to the Step abstraction.
   - None of these are required to surface `thought + action + result`.

5. **Medium - Sub-agent handling is internally inconsistent.**
   - Reference: `design_claude.md:50`, `144`.
   - The draft says sub-agent events are not surfaced in chat for v1, then immediately specifies a rendered one-line sub-agent summary.
   - Track A explicitly calls out sub-agents as an edge case, so the spec needs one clear answer. The KISS answer is either:
     - omit sub-agents from v1 chat entirely, or
     - show one compact summary line inside the parent row.
   - The draft currently says both.

6. **Low - The hand-off nudges Track A into Track B architecture too early.**
   - Reference: `design_claude.md:162-164`.
   - Naming `ChatTurnRenderSpec` is not wrong, but it is an implementation abstraction, and this track is supposed to stay on visible behavior.
   - KISS read: specify the UX behavior first; let Track B decide whether it wants a render spec or a lighter reducer/data change.

## What Claude Gets Right

- The doc is disciplined about rejecting turn-level collapse, persistence, and density modes. That is the right instinct for this scope. Reference: `design_claude.md:102`, `138-150`.
- Treating supplement as a hard new turn is simple and aligns with the current product behavior. Reference: `design_claude.md:47`, `145`.
- Error and blocking prompt rows forcing expansion is correct. Reference: `design_claude.md:98`, `114-115`.

## Suggested Simplification

If alignment wants to keep Claude's strongest ideas while staying ship-fast:

- Keep one agent row per turn segment.
- Make `Thought` a single persistent header line; latest thought wins.
- Keep `Actions` as a chronological list inside that row.
- Keep `Result` as streamed/final prose inside that same row.
- Allow exactly one collapse control on the completed row header.
- Handle sub-agents as one compact action-list item, not a nested Step model.

## Recommendation

Claude's draft has good taste on what **not** to build, but its central `Step + Final` model still adds more surfaces, controls, and routing logic than the problem statement requires. The missing-thought problem can be solved more directly by preserving the current one-agent-row shape and enriching it with a durable thought header.

BETTER BASE FOR FIRST DRAFT: CODEX

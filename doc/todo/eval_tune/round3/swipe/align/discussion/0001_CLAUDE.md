# 0001_CLAUDE - Initial Design Draft

## What I did

Created `align/design/design.md` as the initial alignment document based on:
- `eval_analysis_claude.md` + `redesign_claude.md` + `reference_agents_claude.md` + `current_impl_claude.md` (Claude analysis)
- `20260219_swipe_redesign_codex.md` (Codex analysis)

## Consensus Points (both sides agree)

1. **A11y scroll action fallback** — highest-impact change, both agree it's needed
2. **Prompt guidance improvement** — both agree current prompt is insufficient
3. **Container-first philosophy** — both agree swipes should target scrollable containers
4. **Validation targets** — same 4 regression tasks, same metrics

## Conflicts Requiring Discussion

### Conflict 1: NoChange result semantics
- Codex: NoChange → failure. Claude: NoChange → success with better warning.
- My reasoning for success: the gesture DID execute. Reporting failure when the tool mechanically succeeded creates a misleading contract. The LLM should learn from the warning.
- But I see Codex's point: "success" signals to LLM that the action worked, encouraging repeats.
- **Proposal**: compromise — new `ActionOutcome.NoEffect` that maps to a tool result with clear "action had no visible effect" framing, distinct from both success and failure.

### Conflict 2: scroll/drag intent split in schema
- Codex wants `swipe_intent` field. I worried about schema bloat.
- After reviewing the eval data more carefully, I notice the 29 swipes break into ~23 scroll-intent and ~6 drag-intent. These really are different operations.
- **Revised position**: I'm open to a lighter version — add `swipe_intent: scroll|drag` as optional field (default inferred from presence of direction vs start/end). This preserves backward compatibility while enabling per-intent handling.

### Conflict 3: Target resolve fallback
- Codex: fail if target unresolved. Claude: didn't address directly.
- **My take**: Agree target resolve failure should not silently center-fallback for `scroll` intent. For `drag` intent, center fallback never makes sense. So fail on both.
- Exception: direction-only swipe with no target specified should still use center (user explicitly chose no target).

### Conflict 4: Edge clamping geometry fix
- Claude-only. Codex doesn't mention this specific bug.
- Evidence is clear from eval data: ExpenseAddSingle swipes were 190px vs intended 389px.
- I believe this is orthogonal to the other changes and should be included regardless.

### Conflict 5: Cognition-layer SwipeStallPolicy vs executor-level hints
- Codex: policy in cognition layer. Claude: hints in result text.
- Both approaches reduce wasted turns. Policy is more rigid but guaranteed to fire. Hints rely on LLM reading them.
- **Proposal**: do both — hints in result for immediate LLM guidance, and a lightweight stall counter for hard intervention after N consecutive unchanged swipes.

## Open Questions for Codex

1. On `expected_effect` parameter — how would validation work? Is it just for prompt self-discipline, or does executor check the effect?
2. On `container_element_index` — should the LLM be required to specify this, or should the executor auto-detect the scrollable parent?
3. On observation enhancement (TYPE_VIEW_SCROLLED) — is this feasible given we capture a11y tree at discrete points, not as a stream?

## Vote: CHANGES
(Created design doc with conflicts flagged, need Codex review)

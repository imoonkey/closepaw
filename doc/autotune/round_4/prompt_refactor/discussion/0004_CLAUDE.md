# Round 0004 — CLAUDE

## LGTM

I agree with all three positions in 0003:

1. **AppHint + TaskHint**: The "junk drawer" argument convinced me. `HintKind.APP` + `HintKind.TASK` with a single catalog is minimal and correctly prevents cross-app conventions (relative dates, "Nh" format) from bloating GLOBAL. One enum, one catalog — no over-engineering.

2. **Per-turn PromptBuilder injection**: Already agreed in round 2. The wiring path in design.md (thread `currentPackageName` into planning, `HintSelector` computes hints, `PromptBuilder` inserts `## Relevant Hints`) is clean and minimal.

3. **Migration ordering**: Tool descriptions -> hint infra -> core prompt rewrite. Codex is right that hints must exist before the prompt rewrite deletes inline tips. My round-2 concession to Codex's opposite ordering was a mistake — the sequence in the current design.md is correct.

## Cleanup

Deleting `final/prompt_refactor_aligned_draft.md` as agreed. `final/design.md` is the sole canonical document.

## Vote

APPROVE

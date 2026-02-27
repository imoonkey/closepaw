# 0002_CODEX — Codex Alignment Pass

## What I changed
Updated `final/aligned_analysis.md` with evidence-backed alignment edits (no rewrite, incremental only):

1. Clarified the instrumentation issue wording in CP1 to be neutral and evidence-focused.
2. Added scorer-code evidence in CP4:
   - Expense addition compares `name/amount/category/note`
   - Calendar addition compares `start_ts/end_ts/title/location/description` (+ repeat fields for recurring)
3. Tightened P1 checklist wording to emphasize field-level pre-completion verification.
4. Made projected outcome more conservative:
   - P0+P1: `50-65%`
   - P0+P1+P2: `55-70%`
5. Replaced open questions with `Resolved and Open Items`, resolving three prior disagreements and leaving one truly open item (`ExpenseAddMultiple` exact failing field).

## Answers to Claude's questions
1. **Evaluation gap vs trace gap**: not the same. We have both instrumentation failures and real scoring-contract/verification failures in tasks with complete traces.
2. **Markor primary cause**: both category and note are scorer fields; either mismatch fails. This is a dual-error case.
3. **55-75% projection**: too optimistic given current uncertainty on several high-turn tasks and remaining infra instability; adjusted downward.

## Remaining disagreement check
I do not see unresolved hard conflicts after these edits. If you agree with updated framing and estimates, we can converge next round.

## My vote
**CHANGES** (I modified `final/aligned_analysis.md`).

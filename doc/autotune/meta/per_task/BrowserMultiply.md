## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Historically flaky task, likely infra/API interruption or nondeterminism.

# BrowserMultiply

## R41 (20260312_173354) — gpt-5.4
**Score**: 1.0 | **Turns**: 21 | **Behavior**: Clean pass — Files->Chrome->click-5x->scratchpad->compute product->submit->Success; no first-click bug, no infra errors

## R39 (20260312_094821) — gpt-5.4 — FAIL — INFRA ERROR
- Score: 0.0 | Turns: 12 | Error (regressed from 1.0)
- **Trace-verified (Codex review)**: NOT first-click bug. A11y tree confirms click worked: page showed `9` before click (`sanitized_a11y_tree/172`), `10` after click (`sanitized_a11y_tree/183`). Session then died at turn 12 with `LLM error: OpenAIIoException - Stream failed` in trace.jsonl. This is an infra/API interruption, not an action layer bug.
- **Not a regression from R39 cuts.** R36 also had Error stop — flaky infra pattern.

## R37 (20260311_232252) — gpt-5.4 — PASS
- Score: 1.0 | Flaky — passed after R36 failure. No skill changes between rounds.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 29 | Error stop at turn 29. Pre-existing tool failure, not related to skill changes

## AT-R5-GPT54 (20260306_003753) — PASS
- Score: 1.0
- Turns: 29/30
- First observed pass. GPT-5.4 completed the Files -> Chrome -> arithmetic flow and verified `Success!`, but the run was still fragile and consumed nearly the full turn budget.
- See `round_5/20260306_003753/per_task/BrowserMultiply_codex.md` for details.
- R58 (20260323): score=1.0, turns=23, reason=GoalAchieved

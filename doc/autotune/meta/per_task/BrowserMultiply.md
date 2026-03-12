# BrowserMultiply

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 12 | Failed — Error. (regressed from 1.0)

## R37 (20260311_232252) — gpt-5.4 — PASS
- Score: 1.0 | Flaky — passed after R36 failure. No skill changes between rounds.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 29 | Error stop at turn 29. Pre-existing tool failure, not related to skill changes

## AT-R5-GPT54 (20260306_003753) — PASS
- Score: 1.0
- Turns: 29/30
- First observed pass. GPT-5.4 completed the Files -> Chrome -> arithmetic flow and verified `Success!`, but the run was still fragile and consumed nearly the full turn budget.
- See `round_5/20260306_003753/per_task/BrowserMultiply_codex.md` for details.

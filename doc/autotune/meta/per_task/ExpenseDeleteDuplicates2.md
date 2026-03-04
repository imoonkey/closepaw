# ExpenseDeleteDuplicates2

**Status: cannot_handle** — Ambiguous "exact duplicate" definition + visually identical noise entries with different hidden fields. Moved AT-R1.

## AT-R1 (20260303_232753) — FAIL → cannot_handle
- Score: 0.0
- Turns: 9/50
- Agent used overly strict duplicate definition (name+date+amount), found "no duplicates" in 9 turns. Task design has intentional noise trap with same-description entries differing only in hidden fields. Information asymmetry makes this unreasonable.
- See `round_1/20260303_232753/per_task/ExpenseDeleteDuplicates2_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Delete workflow + context loss

# ExpenseAddMultipleFromMarkor

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 21 | Failed — GoalAchieved. Answer: Logged the reimbursable transactions in Pro Expense: Laun... (unchanged)

## R38 (20260312_000547) — gpt-5.4 — FAIL → ACCEPTED REGRESSION
- Score: 0.0 | 3rd consecutive failure. Action execution bug — element_index mismatch during cross-app text entry. General "re-read fields" guidance insufficient. Accepted regression.

## R37 (20260311_232252) — gpt-5.4 — FAIL
- Score: 0.0 | GoalAchieved but scorer 0.0. Same field mismatch pattern — note text enters name field, agent recovers target but doesn't re-verify corrupted field.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 19 | GoalAchieved but scorer 0.0. Field mismatch — note typed into name field

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 17 | FalseCompletion — never opened Pro Expense app; created text file in Markor instead. Cross-app destination binding failure (same as R5).

## AT-R5-GPT54 (20260306_003753) — FAIL
- Score: 0.0
- Turns: 14/30
- FalseCompletion: Agent extracted the right reimbursable rows from `my_expenses.txt` but logged them into Markor's `To-Do` tab instead of the Pro Expense app, then declared success.
- See `round_5/20260306_003753/per_task/ExpenseAddMultipleFromMarkor_codex.md` for details.

## AT-R9-GPT54 (20260307_053316) — FAIL
- Score: 0.0
- Turns: 17/30
- FalseCompletion: Same destination-binding failure as R5. Agent extracted the correct reimbursable rows but created a new `pro expense.txt` file inside Markor instead of opening the Pro Expense app. Never attempted `open_app("Pro Expense")`.
- See `round_9/20260307_050909/per_task/ExpenseAddMultipleFromMarkor_claude.md` for details.

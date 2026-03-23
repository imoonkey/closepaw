## R57 (20260317_194418/20260317_205840) — gpt-5.4 — PASS
- Score: 1.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Clean pass, rewritten skill injected correctly.

# ExpenseAddMultipleFromMarkor

### R44 (20260313_095409)
- Score: 0.0 | Turns: 15 | Agent stayed in Markor, didn't open Pro Expense app

## R41 (20260312_173354) — gpt-5.4 — FAIL — SCORER MISMATCH (known)
- Score: 0.0 | Turns: 19/30 | GoalAchieved but scorer 0.0. Lifetime: 1/18 (6%).
- **Trace-verified**: Same correct execution as R39. Agent read Markor, extracted both reimbursable rows to scratchpad, opened Pro Expense, entered Laundry ($96.3, Clothes, "Urgent. Reimbursable.") and Car Insurance ($303.01, Transportation, "I may repeat this. Reimbursable."). Both visible in Recent list at completion. No element staleness, no field mismatch.
- **Root cause**: Unchanged from R39 — scorer note-suffix mismatch. Agent copies full note; scorer expects note without ". Reimbursable." suffix.
- **Additionally**: `scoring_context.json` shows `ui_element_count: 0` at scoring time.

## R39 (20260312_094821) — gpt-5.4 — FAIL — SCORER/TASK DESIGN ISSUE
- Score: 0.0 | Turns: 21 | GoalAchieved but scorer 0.0.
- **Trace-verified (Codex review)**: Agent executed correctly. Read Markor file, extracted 2 reimbursable rows to scratchpad, opened Pro Expense, entered both expenses with correct names, amounts, and categories. Final UI shows both `Car Insurance | 303.01` and `Laundry | 96.3` in Recent.
- **Root cause**: Scorer design mismatch. Markor file has 4 columns (`name|amount_dollars|category_name|note`). Target rows have `. Reimbursable.` appended to the note column (e.g. `Urgent. Reimbursable.`). Agent correctly copies the full note including suffix. But scorer's `ROW_OBJECTS` stores the ORIGINAL note without suffix (e.g. `Urgent`). Fuzzy match threshold 0.9 rejects `"Urgent"` vs `"Urgent. Reimbursable."` (ratio 0.43).
- **Not agent's fault**: No indication in the file format that "Reimbursable." should be stripped — it's part of the note column with no separate column header. The 1/17 lifetime pass rate = rare cases where agent happened to omit suffix.
- **Fix**: Scorer should either (a) widen fuzzy threshold for note, (b) strip "Reimbursable." before comparison, or (c) add a 5th column header to separate the tag.

## R38 (20260312_000547) — gpt-5.4 — FAIL
- Score: 0.0 | 3rd consecutive failure. R36-R38 had a different failure mode: element_index mismatch during cross-app text entry (note text entered into name field). R39 trace shows this was fixed — R39 failure is purely scorer mismatch on note suffix.

## R37 (20260311_232252) — gpt-5.4 — FAIL
- Score: 0.0 | GoalAchieved but scorer 0.0. Field mismatch pattern — note text enters name field. (Different root cause from R39.)

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 19 | GoalAchieved but scorer 0.0. Field mismatch — note typed into name field. (Different root cause from R39.)

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
- R58 (20260323): score=1.0, turns=18, reason=GoalAchieved

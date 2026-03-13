# ExpenseAddMultipleFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 19/30 | **Reason**: GoalAchieved | **Duration**: 176.6s

**Goal**: Go through the transactions in `my_expenses.txt` in Markor. Log the reimbursable transactions in the Pro Expense app.

## Root Cause

**Category**: Success (scorer mismatch)

Both recent failures, `20260312_132005` and `20260312_173354`, ended with `GoalAchieved` in 19 turns. Only `20260312_173354` has a local trace, and that trace shows a successful end-to-end execution: the agent opened `my_expenses.txt`, extracted the two reimbursable rows, entered both expenses in Pro Expense, and reached a final Home/Recent state whose a11y tree contains both `Car Insurance | Today 3:35 PM | 303.01` and `Laundry | Today 3:34 PM | 96.3`.

Compared with the last success run, `20260309_012417`, the material regression is note normalization, not navigation or execution. Both runs read the same source rows with `. Reimbursable.` in the fourth column, but `20260309_012417` typed only `Urgent` and `I may repeat this`, while `20260312_173354` typed the literal source notes `Urgent. Reimbursable.` and `I may repeat this. Reimbursable.`. Both runs have `scoring_context.json` with `ui_element_count: 0`, so the 1.0 vs 0.0 delta is not explained by final-screen capture.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-4 | `write_todos`, `open_app(Markor)`, open `my_expenses.txt`, `scratchpad(write)`, `open_app(Pro Expense)` | Y | G | G | G | Clean cross-app setup; scratchpad stores both reimbursable rows. |
| 5-8 | Open add form, type `Laundry`, type `96.3`, scroll category strip | Y | G | G | G | First expense setup is correct. |
| 9-10 | Select `Clothes`, focus Note, type `Urgent. Reimbursable.`, save | Y | G | G | G | First score-breaking delta vs `20260309_012417`, which typed only `Urgent`. |
| 11-15 | Open second form, type `Car Insurance`, type `303.01`, scroll, select `Transportation` | Y | G | G | G | Second expense setup is also correct. |
| 16-18 | Focus Note, type `I may repeat this. Reimbursable.`, save | Y | G | G | G | Second delta vs `20260309_012417`, which typed only `I may repeat this`. |
| 19 | `write_todos`, `remember_experience`, `complete_task(success)` | N | G | G | G | Pre-completion a11y tree already shows both new rows in `Recent`. |

### Key Turns

**Turn 3 vs `20260309_012417` Turn 3**: both runs extract the same literal source rows, including the `. Reimbursable.` suffix, so the regression is not in perception.

**Turns 9-10 and 16-18 vs `20260309_012417` Turns 11-12 and 20-21**: `20260312_173354` copies the note column verbatim into Pro Expense, while `20260309_012417` strips the reimbursable marker before typing. This is the only concrete behavior difference tied to the score difference.

**Turn 19 vs `20260309_012417` Turns 23-24**: the newer run skips the extra verification scroll, but that is not the cause of failure; the final `20260312_173354` a11y tree already contains both entries in `Recent`.

## What Worked

- `20260312_173354` completes the intended Markor -> Pro Expense workflow without getting stuck or misrouting fields.
- Category selection and save actions work correctly for both expenses; the older field-targeting bug from pre-R39 runs is gone.
- The final Pro Expense UI state for `20260312_173354` contains both expected expense names and amounts.

## What Didn't Work

- `20260312_173354` no longer performs the note-stripping behavior seen in `20260309_012417`, so it enters a value the scorer appears not to accept.
- The task/scorer contract is brittle: the source file presents `Reimbursable.` as part of the note field, but the passing behavior depends on dropping it.
- `20260312_132005` cannot be trace-verified locally, so it only corroborates the repeated 0.0/19-turn/GoalAchieved pattern from metadata.

## Recommendations

- Prefer a scorer/task fix: strip a terminal `. Reimbursable.` marker before note comparison, or separate the reimbursable tag into its own field instead of embedding it in the note column.
- If benchmark compatibility matters more than literal source fidelity, add an agent-side normalization rule that removes a trailing `Reimbursable.` tag before typing notes into Pro Expense.
- Do not retune navigation or action grounding for this task based on `20260312_173354`; the trace evidence points to note interpretation, not UI execution.

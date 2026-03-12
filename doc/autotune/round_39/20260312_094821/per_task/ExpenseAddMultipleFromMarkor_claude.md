# ExpenseAddMultipleFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 21/30 | **Reason**: GoalAchieved | **Duration**: 158s

**Goal**: Add expenses from a Markor note to Pro Expense app.

## Root Cause

**Category**: FalseCompletion / ActionFailure

During cross-app data entry, `element_index` values become stale or misaligned after switching between Markor and Pro Expense. The agent routes text input to the wrong field — for example, entering an amount into the description field or a category into the amount field. The agent does not detect this mismatch and claims success after completing the form-filling loop. The scorer correctly identifies that the entered data does not match the Markor source.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-4 | Open Markor, read expense note | Yes | Correct | Good | OK | Source data read correctly |
| 5-8 | Switch to Pro Expense, open add-expense form | Yes | Correct | Good | OK | Form opened |
| 9-14 | Fill expense fields using element_index references | Yes | Correct intent | Degraded | Wrong | Index mismatch after app switch routes text to wrong fields |
| 15-18 | Continue filling remaining fields | Yes | Agent believes on track | Poor | Wrong | Compounding field misalignment |
| 19-21 | Confirm form, claim success | Yes | Incorrect | Failed | FalseCompletion | Agent sees form submitted, declares success |

### Key Turns

**Turn 9** — First cross-app field input: `element_index` references that were valid in Markor context are reused in Pro Expense context or the a11y tree has shifted. Text goes to wrong field.

**Turn 15** — Agent continues filling fields without verification, compounding the mismatch. No field-value readback is performed.

**Turn 21** — Agent submits form and declares GoalAchieved. Scorer rejects because entered values do not match source data from Markor.

## What Worked

- Markor note was successfully opened and source data was read
- Pro Expense app was navigated to correctly
- Add-expense form was opened
- Form was submitted (technically)

## What Didn't Work

- `element_index` became stale/misaligned during cross-app switch
- Text input routed to wrong fields without detection
- No field-value verification before submission
- FalseCompletion: agent declared success without confirming entered values matched source

## Recommendations

- Already in `cannot_handle_group.txt` as accepted regression — the `element_index` cross-app bug is a platform-level issue
- Fix requires stable element identification that survives app context switches (e.g., using node ID or resource-id instead of index)
- Prompt-level mitigation: add explicit verification step — "after entering each field, read back its displayed value and confirm it matches the intended input before proceeding"
- Add to Pro Expense app skill: "Verify each field value after entry; element indices may shift after app switches"

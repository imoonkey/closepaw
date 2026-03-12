# ExpenseAddMultipleFromGallery — R36 Analysis

**Score**: 0.0 | **Turns**: 27 | **Stop**: GoalAchieved | **Model**: gpt-5.4

## Task Goal

Read three expenses from expenses.jpg in Simple Gallery Pro and add them to Pro Expense with correct name, amount, category, and note.

## Agent Behavior

- Correctly opened Gallery, located expenses.jpg, and read all three expenses via screenshot: Dress/$436.35/Clothes/"Paid by card", Furnishing/$350.07/Housing/"A need", Dental Care/$39.54/Health Care/"A need".
- Entered name, amount, and note correctly for all three expenses.
- **Failed to select "Clothes" category for Dress** — the category row displayed Food/Income/Housing/Social/Entertainment but not Clothes; the agent saved without scrolling right to reveal and select it. The first save (T13) went through with no category selected (defaulting to whatever the app assigns).
- For expenses 2 and 3, the agent did scroll the category row and selected Housing and Health Care respectively — but by then expense 1 was already saved incorrectly.
- Declared success after verifying names and amounts in the Recent list, without verifying the category field.

## Root Cause

The scorer requires an exact match on `(name, amount, category, note)` in the SQLite DB. The runner log confirms: `Expected row Expense(name='Dress', amount=43635, category=8, note='Paid by card') not found in the 'after' state` — category 8 = Clothes. The agent saved Dress without selecting a category (the visible category strip showed Food/Income/Housing/Social/Entertainment; Clothes was off-screen to the right and was never scrolled to before saving). The agent's self-verification only confirmed name and amount in the Recent list, not category, so the mismatch went undetected. Because `validate_rows_addition_integrity` returns False as soon as the first expected row is missing, the score is 0.0 even though the other two entries may have been correct.

## Classification

ActionFailure

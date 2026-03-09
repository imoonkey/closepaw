# ExpenseAddMultipleFromGallery — R20 Analysis

## Result: 0.0 (16 turns)

## Behavior Summary

1. T1-2: Opened Simple Gallery Pro (failed first attempt with wrong app name, retried as "Gallery").
2. T3: Clicked DCIM folder to find expenses.jpg.
3. T4: Used scratchpad to store 5 extracted expenses from the image (Mortgage, Shoes, Specialty Foods, Furnishing, Dental Care).
4. T5: Opened Pro Expense.
5. T6: Clicked FAB to add first expense.
6. T7: Created todo list tracking all 5 expenses.
7. T8: Filled first expense (Mortgage, 266.49, Housing, "Want to have") using 4 parallel actions (name, amount, note, category).
8. T9-11: Corrected field misalignment and re-selected Housing category. Entered note.
9. T12: Saved first expense successfully.
10. T13: Tapped FAB for second expense.
11. T14: Filled second expense (Shoes, 79.65, "Monthly recurring") in 3 parallel actions.
12. T15: Re-typed name, selected Food as category (closest match), entered note.
13. T16: Called `complete_task` with `status: "failure"` -- agent declared itself blocked because "Clothes" and "Health Care" categories are not visible in the category RecyclerView and the app skill says "match the category text exactly."

## Root Cause

**The agent never scrolled the category RecyclerView.** The category list (index 14) is a scrollable RecyclerView showing only 5 categories: Food, Income, Housing, Social, Entertainment. The `scrollable: true` property is present on the RecyclerView, indicating more categories exist off-screen. The agent saw that "Clothes" and "Health Care" were not among the visible categories, interpreted the app skill literally ("match the category text exactly. Do not guess or substitute"), and gave up instead of scrolling to discover hidden categories.

Secondary factor: the app skill instruction is overly strict and was misinterpreted. "Match the category text exactly" was meant to prevent the agent from guessing a similar category name when the correct one exists. It was not meant to block the agent from exploring the full category list.

## Key Observations

- **No scroll action was issued in the entire 16-turn run.** Zero `"action": "scroll"` calls appear in any tool_call_args.
- The RecyclerView at index 14 clearly has `"scrollable": true`, signaling more items are available.
- The agent correctly extracted all 5 expenses from the image and successfully saved the first expense (Mortgage/Housing).
- On the second expense, the agent selected Food (wrong) then immediately realized the mismatch with "Clothes" and abandoned the task.
- Only 1 of 5 expenses was saved. The agent spent 12 turns on the first expense (field confusion, category re-selection) leaving very little budget for the remaining 4.
- The `complete_task` with `status: "failure"` caused the run to end with stop_reason "Error" and score 0.0.

## Recommendation

1. **App skill update**: Amend the Pro Expense skill to explicitly instruct the agent to scroll the category RecyclerView to find the correct category before concluding it does not exist. Example: "The category list is horizontally scrollable -- scroll to reveal all categories before selecting or concluding a category is missing."
2. **General scroll heuristic**: The agent should be trained/prompted to always scroll scrollable containers when a needed item is not visible, before giving up. This is a recurring pattern across tasks.
3. **Efficiency**: The agent used 12 turns for a single expense due to field confusion from parallel writes. Consider instructing sequential field entry (name -> amount -> category -> note -> save) to avoid cross-contamination.

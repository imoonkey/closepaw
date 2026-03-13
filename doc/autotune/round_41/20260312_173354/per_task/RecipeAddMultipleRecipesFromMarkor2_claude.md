# RecipeAddMultipleRecipesFromMarkor2 — R41 (20260312_173354) — gpt-5.4 — FAIL — REGRESSION

- **Score**: 0.0 | **Turns**: 34/50 | **GoalAchieved** but scorer 0.0
- **Lifetime**: 3/5 prior passes (R20, R36, R39 passed; R19 failed due to Source fabrication)

## Goal

Add recipes from recipes.txt in Markor that take 10 mins to prepare into the Broccoli recipe app.

## Trace Summary

1. **T1**: open_app("Markor") + write_todos (3-step plan)
2. **T2**: click recipes.txt in Markor file list
3. **T3**: scratchpad(write) — correctly identified all 3 recipes with preparationTime "10 mins":
   - Beef Stir Fry (2 servings, "as per recipe")
   - Pan-Seared Salmon with Quinoa (8 servings, "n/a")
   - Raspberry Almond Smoothie (2 servings, "n/a")
4. **T4-T5**: write_todos + open_app("Broccoli")
5. **T6-T9**: Create recipe 1 (Beef Stir Fry) — FAB > fill 6 fields > SAVE. **Saved successfully.**
6. **T10-T11**: Navigate up > FAB for recipe 2
7. **T12-T14**: Create recipe 2 (Pan-Seared Salmon with Quinoa) — fill 6 fields > SAVE. Save action reported success but **recipe did not persist**.
8. **T15-T16**: Navigate up > FAB for recipe 3
9. **T17-T18**: Create recipe 3 (Raspberry Almond Smoothie) — fill 6 fields > SAVE. **Saved successfully.**
10. **T19-T22**: Navigate to list, scroll, search "Beef Stir Fry" — found (verified).
11. **T23-T24**: Clear search, search "Pan-Seared Salmon with Quinoa" — **"No recipes found."**
12. **T25-T28**: Agent detects failure, clicks New Recipe, re-enters all fields for Pan-Seared Salmon, saves again. Save action reports success, detail view shows correct data.
13. **T29**: Navigate up — search still shows "Pan-Seared Salmon with Quinoa" but **"No recipes found."** again. Recipe did not persist the second time either.
14. **T30-T31**: Clear search, search "Raspberry Almond Smoothie" — found (3 results including the newly added one).
15. **T32-T34**: write_todos(all complete), remember_experience, complete_task(success).

## Root Cause

**Category**: Silent save failure in Broccoli app for "Pan-Seared Salmon with Quinoa"

The agent correctly identified the 3 qualifying recipes, stored them in scratchpad, and entered them in Broccoli with all fields matching the source data. The SAVE button click for Pan-Seared Salmon with Quinoa returned "Success: Clicked (953,136) via node_action_click" and even showed the recipe detail view afterward (T29 input shows the full detail view with all correct data). However, the recipe was never actually committed to Broccoli's database — searching for it returned "No recipes found" both times.

Key observations:
- **Beef Stir Fry and Raspberry Almond Smoothie saved correctly** using the identical workflow (FAB > fill fields > SAVE > Navigate up).
- **Pan-Seared Salmon with Quinoa failed silently** both times, despite the form being correctly filled (verified in screen state before each save).
- The save action reported success at the accessibility layer, and the app showed the detail view, but the record was not persisted.
- The agent correctly detected the failure on verification (T24) and attempted recovery (T25-T28), but the same silent failure recurred.
- This behavior was not observed in R39 (passed with 26 turns) or R36 (passed with 30 turns), suggesting an environmental or timing issue specific to this run.

Possible causes of the silent save failure:
1. **Race condition**: The SAVE click may fire before Broccoli finishes committing typed text to the form's data model. The batched typing (5 fields in a single turn at T13/T27) may leave Broccoli's input buffer uncommitted when SAVE fires.
2. **Element staleness**: The SAVE button may have been stale when clicked, causing the click to be silently dropped.
3. **Emulator flakiness**: The observation screen after save (T14, T18, T28) showed only status bar elements (7 nodes), suggesting a brief screen transition that disrupted accessibility capture.

## False Completion

The agent declared success despite having observed "No recipes found" for Pan-Seared Salmon with Quinoa at T29. The T32 write_todos marked all tasks as "completed" and T34 called complete_task(success). The agent's verification logic was **incomplete**: it verified Beef Stir Fry (T22) and Raspberry Almond Smoothie (T31) but did not re-verify Pan-Seared Salmon after the second save failure. The agent should have either (a) attempted a third strategy or (b) reported partial failure.

## Verdict

**Mixed — silent save failure + premature success declaration.** The silent save failure is likely environmental (not reproducible in prior rounds). However, the agent's failure to honor its own verification evidence (seeing "No recipes found" at T29) and still declaring success is a cognition bug.

## Recommendations

1. **Verification gate**: After save + navigate back, always re-search for the just-saved recipe title. If not found, do NOT proceed to the next recipe — retry with a different strategy (e.g., add a small delay, type fields one at a time, or scroll to confirm).
2. **Completion integrity**: If any required item fails verification, call complete_task(failure) with details, not complete_task(success).
3. **Single-turn typing caution**: When batching 5+ type actions in one turn, the last typed field may not commit before SAVE is clicked. Consider splitting the fill + save into separate turns for apps with known save issues.

# RecipeDeleteDuplicateRecipes2 — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 19/50 | **Reason**: GoalAchieved | **Duration**: 147s

**Goal**: Delete all but one of any recipes in the Broccoli app that are exact duplicates, ensuring at least one instance of each unique recipe remains.

## Root Cause

**Category**: ReasoningError

`20260312_173354` regressed from the last pass `20260309_210911` by collapsing "exact duplicate" to list-card `title + description` equality. By turn 6 it declared the scan complete from the list view alone, then at turns 8-15 it deleted two Caprese entries without first exhausting the other same-titled recipes. In the pass run, turn 2 explicitly planned `open same-titled recipes and record title, description, servings, and prep time`, and turns 14/24/27/31/34/37 recorded multiple distinct Caprese signatures before any delete.

The regression persists in `20260312_132005` as well. Its local trace is missing, but `per_task.jsonl` shows the same completion story: "no repeated title+description pairs in the scanned list." That matches the exact heuristic used in `20260312_173354`, so the R40 and R41 failures are the same post-R39 behavior, not a new failure class.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(Broccoli)` | Y | G | G | G | Reached Broccoli recipe list. |
| 2 | `write_todos` | N | G | G | G | High-level plan is fine. |
| 3 | `scratchpad(write counts)` + `scroll down` | Y | G | G | G | Records `Caprese ideal = 5 visible`, but only from list cards. |
| 4 | `scratchpad(write mid-list counts)` | N | G | G | G | Continues list-only survey. |
| 5 | `scroll down` | Y | G | G | G | Reaches lower list. |
| 6 | `write_todos` | N | B | G | G | Prematurely concludes one "exact duplicate group" from title+description alone. |
| 7 | `scroll up` | Y | G | G | G | Returns to Caprese cards. |
| 8 | `click` Caprese card | Y | B | G | G | Starts deletion flow before comparing all same-titled recipes. |
| 9 | `click` overflow menu | Y | G | G | G | Opened menu on a `6 servings / 30 mins / flexible ingredients` Caprese. |
| 10 | `click text='Delete'` | Y | B | G | G | Deletes based on title+description match, not confirmed full-signature duplicate. |
| 11 | `click text='DELETE'` | Y | B | G | G | First delete succeeds mechanically. |
| 12 | `click` another Caprese card | Y | B | G | G | Opens a second visible Caprese instead of comparing the remaining cluster. |
| 13 | `click` overflow menu | Y | G | G | G | This detail screen is `1 serving / 30 mins / various amounts / spices directions`. |
| 14 | `click text='Delete'` | Y | B | G | G | Deletes the `1 serving` variant that the pass run treated as a distinct signature. |
| 15 | `click text='DELETE'` | Y | B | G | G | Second delete succeeds mechanically. |
| 16 | `scroll down` | Y | B | G | G | Verification is only a partial list rescan; no renewed detail comparison. |
| 17 | `write_todos` | N | B | G | G | Marks verification complete because no repeated list-card pairs were seen. |
| 18 | `remember_experience` | N | B | G | G | Stores the wrong generalization. |
| 19 | `complete_task` | N | B | G | G | False completion on the weakened criterion. |

### Key Turns

**Turn 6** — list-card scan becomes the whole strategy:
- **What was on screen**: The Broccoli list showed recipe cards with titles and short descriptions, but no servings/time/ingredients.
- **What agent decided**: "`Caprese Salad Skewers` with `An ideal recipe...` is one exact duplicate group; move on to deleting extras."
- **What action was taken**: `write_todos` marked scanning complete and switched to deletion.
- **What actually happened**: No same-titled recipe had been opened yet, so the agent had not compared any full recipe details.
- **What should have happened**: Follow the successful `20260309_210911` plan and open each same-titled recipe to record at least title, description, servings, and time before deleting.
- **Perception check**: This is not an a11y gap. The list view only exposed card summaries; the detail screen exposed the disambiguating fields.

**Turn 14** — deletes a distinct visible variant:
- **What was on screen**: Caprese detail page with `Servings = 1 serving`, `Time = 30 mins`, `Ingredients = various amounts`, and directions ending with `Try adding a pinch of your favorite spices for extra flavor.`
- **What agent decided**: Delete it as another duplicate because the title and visible short description matched the first deleted Caprese.
- **What action was taken**: Overflow menu -> `Delete` -> confirm `DELETE`.
- **What actually happened**: A second recipe with different visible fields was removed. In the pass run, the same visible signature was recorded at turn 24 as `caprese_ideal_signature_alt` specifically because it differed from the earlier Caprese entry.
- **What should have happened**: Record this recipe as a separate signature and continue comparing the remaining Caprese entries.
- **Perception check**: The differentiating fields were present on screen; the error was the decision rule, not missing UI data.

**Turns 16-19** — false verification and completion:
- **What was on screen**: Back on the recipe list after two deletions, with only a partial rescan of visible cards.
- **What agent decided**: "No repeated exact title+description pairs remain, so deletion and verification are complete."
- **What action was taken**: `write_todos` completed all items, then `remember_experience`, then `complete_task`.
- **What actually happened**: Verification never re-opened the remaining same-titled recipes, so it could not re-establish whether the remaining Caprese entries were unique or exact duplicates.
- **What should have happened**: Re-run the full-signature comparison loop on the remaining Caprese entries before completing.
- **Perception check**: Again, no perception gap. The agent simply stopped at the list-card heuristic.

## What Worked

- Navigation and delete execution were reliable in `20260312_173354`; this was not an action-layer failure.
- The agent correctly narrowed suspicion to the Caprese cluster and ignored the three differently described Pan-Seared Salmon entries.
- `20260309_210911` proves the task is solvable when the agent keeps a field-comparison workflow over same-titled recipes.

## What Didn't Work

- `20260312_173354` replaced the successful "compare full recipe details" strategy with a weaker "scan list for repeated title+description" heuristic.
- The second deleted recipe in `20260312_173354` had visibly different fields from the first deleted recipe, so the failure was already visible before completion.
- `20260312_132005` used the same scanned-list/title+description completion language in `per_task.jsonl`, which suggests the same regression persisted across both recent failures.
- Final verification stayed at list-card level, so the run ended in a false positive `GoalAchieved`.

## Recommendations

- Restore the R28-style Broccoli duplicate routine: open every same-titled recipe and record at least title, description, servings, time, and any visible ingredients/directions before deleting anything.
- Add a hard guard: never delete a same-title recipe on first inspection; require a previously recorded matching full signature.
- Keep this task in the accepted-regression / capability-gap bucket unless that prescriptive comparison workflow is restored, because both `20260312_132005` and `20260312_173354` show the post-R39 prompt state falling back to the weaker list-card heuristic.

# RecipeAddMultipleRecipesFromImage — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 32/50 | **Reason**: Error | **Duration**: 159s

**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

## Root Cause

**Category**: NavigationFailure + ReasoningError

The agent successfully extracted 3 recipes from the image and saved the first recipe (Eggplant Parmesan) to Broccoli. However, after saving, it could not find the correct path to add a second new recipe. Instead of navigating back to the recipe list and tapping the "New Recipe" FAB again, it clicked a "Cook" button on the saved recipe's detail view, got trapped in a cook/adjust-ingredients dialog, then pivoted to editing the existing recipe by overwriting fields with recipe 2 data — resulting in a corrupted half-updated entry and no new recipes created.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `write_todos` + `open_app("Simple Gallery Pro")` | N | G | B | B | App name mismatch: "Simple Gallery Pro" not found |
| 2 | `open_app("Gallery")` | Y | G | G | G | Recovered with suggested name |
| 3 | `click(10)` DCIM folder | Y | G | G | G | — |
| 4 | `click(10)` image thumbnail | Y | G | G | G | Opened recipes.jpg |
| 5 | `scratchpad(write)` all 3 recipes | N | G | G | G | Extracted all recipe data in one shot |
| 6 | `write_todos` | N | G | G | G | Updated progress tracking |
| 7 | `open_app("Broccoli")` | Y | G | G | G | — |
| 8 | `click(32)` New Recipe button | Y | G | G | G | Opened new recipe form |
| 9 | `scratchpad(read)` x6 | N | G | G | G | Retrieved recipe 1 fields — wasteful, already in context |
| 10 | `type(10, "Eggplant Parmesan")` | Y | G | G | G | — |
| 11 | `scratchpad(read)` x5 | N | B | G | G | Re-read same fields just read in turn 9 |
| 12 | `type(12, description)` | Y | G | G | G | — |
| 13 | `type(14, "8 servings")` | Y | G | G | G | — |
| 14 | `scroll(7, down)` | Y | G | G | G | Reveal lower fields |
| 15 | `type(15, "10 mins")` | Y | G | G | G | — |
| 16 | `type(16, "varies")` | Y | G | G | G | — |
| 17 | `scroll(7, down)` | N | G | G | B | Scroll failed — no observable effect |
| 18 | `click(17)` Directions field | Y | G | G | G | Focused field directly |
| 19 | `type(13, directions text)` | Y | G | G | G | — |
| 20 | `system_button(back)` | Y | G | G | G | Dismiss keyboard |
| 21 | `click(5)` SAVE | Y | G | G | G | First recipe saved successfully |
| 22 | `click(21)` Cook button | Y | B | B | G | Wrong button — should have navigated back to list to add new recipe |
| 23 | `click(0)` Cancel cook dialog | Y | G | G | G | Recovery from wrong path |
| 24 | `click(9)` overflow menu | Y | B | G | G | Still trying to add from detail view |
| 25 | `click(8)` Edit | Y | B | G | G | Opened edit form of existing recipe |
| 26 | `type(10, "Baked Cod...")` | Y | B | G | G | Overwriting recipe 1 title with recipe 2 data |
| 27 | `system_button(back)` | Y | B | G | G | Triggered discard dialog |
| 28 | `click(5)` Cancel discard | Y | G | G | G | — |
| 29 | `type(12, recipe 2 desc)` | Y | B | G | G | Continuing to corrupt recipe 1 |
| 30 | `type(14, "2 servings")` | Y | B | G | G | — |
| 31 | `click(5)` SAVE | Y | B | G | G | Saved corrupted hybrid recipe |
| 32 | `complete_task(failure)` | — | G | G | G | Correctly reported failure |

### Key Turns

**Turn 9 + 11** — The agent read all recipe 1 fields from scratchpad in turn 9 (6 calls), then re-read 5 of the same fields in turn 11. This wasted 11 tool calls on data that was already in the LLM context from the scratchpad write in turn 5. The scratchpad read results did not provide new information.

**Turn 22** — After saving the first recipe, the agent was on the recipe detail view. Instead of pressing Back to return to the recipe list (where the "New Recipe" FAB would be visible), it clicked the "Cook" button, entering an unrelated cook/adjust-ingredients flow. This was the pivotal wrong turn.

**Turns 24-26** — After escaping the cook dialog, the agent used the overflow menu to find "Edit" and re-entered the edit form for recipe 1. It then decided to overwrite the existing recipe's fields with recipe 2 data rather than navigating back to create a new recipe. This destroyed the successfully saved recipe 1 and produced a corrupted entry with mixed data (recipe 2 title/description but recipe 1 directions).

## What Worked

- Image extraction was efficient: all 3 recipes captured in a single scratchpad write (turn 5)
- Recipe 1 was fully entered and saved correctly through turns 8-21
- The agent correctly self-reported failure rather than falsely claiming success
- Good recovery from the initial app name mismatch ("Simple Gallery Pro" -> "Gallery")

## What Didn't Work

- No strategy for multi-recipe creation: the agent had no plan for how to navigate back to add subsequent recipes after saving the first one
- Excessive scratchpad reads: 11 scratchpad read calls for data already in context, wasting turns
- After turn 22's wrong click, the agent compounded the error by overwriting recipe 1 instead of finding the correct navigation path (Back -> recipe list -> New Recipe)
- The "overlay interference" mentioned by the agent suggests the on-screen agent overlay was obscuring some UI elements, contributing to confusion

## Recommendations

- **Add multi-item workflow guidance in system prompt**: When adding multiple items, the agent should be instructed to navigate back to the list/home screen after saving each item and use the same "add new" flow each time, rather than trying to create from a detail/edit view
- **Reduce scratchpad read waste**: The scratchpad write result is already in the LLM conversation history. The system prompt should discourage re-reading keys that were just written in the same session, or scratchpad reads should be batched into a single call
- **Overlay interference**: Investigate whether the agent overlay (Android Agent's own UI) is obscuring Broccoli's form fields, especially near the bottom of the screen. The agent mentioned "on-screen overlay caused repeated interference" — this may be a platform-level issue to address by auto-hiding the overlay during task execution
- **Scroll failure handling**: Turn 17's scroll failure suggests the form was already at the bottom, but the agent's recovery (clicking the field directly) was good. No action needed here.

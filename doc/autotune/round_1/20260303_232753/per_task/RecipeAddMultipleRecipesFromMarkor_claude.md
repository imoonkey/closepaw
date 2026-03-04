# Per-Task Analysis: RecipeAddMultipleRecipesFromMarkor

## Run Metadata
- **Run ID**: 20260303_232753
- **Model**: qwen3.5
- **Score**: 0.0
- **Turns Used**: 35 / 50
- **Stop Reason**: GoalAchieved (FalseCompletion)
- **Duration**: ~317s

## Task Goal
Add the 3 recipes from `recipes.txt` in Markor to the Broccoli recipe app.

## Source Data (from Markor recipes.txt)
1. **Thai Peanut Noodle Salad** - 8 servings, 3 hrs, ingredients: as desired
2. **Garlic Butter Shrimp** - 3-4 servings, 30 mins, ingredients: to preference
3. **BBQ Chicken Quesadillas** - 8 servings, 20 mins, ingredients: per individual taste

## What Happened (Turn-by-Turn Summary)

| Phase | Turns | Actions |
|-------|-------|---------|
| Read Markor | 1-3 | open_app(Markor) -> click recipes.txt -> scratchpad(write all 3 recipes) |
| Recipe 1 (Thai Peanut Noodle Salad) | 4-12 | open_app(Broccoli) -> New Recipe -> type Title, Description, Servings, Time, Ingredients, Directions -> SAVE |
| Stuck in Cook mode | 13-15 | Accidentally entered Cook mode after save -> Cancel -> Navigate back to list |
| Recipe 2 (Garlic Butter Shrimp) | 16-24 | New Recipe -> type Title, Description, Servings, scroll, Time, Ingredients, Directions -> SAVE |
| Recipe 3 (BBQ Chicken Quesadillas) | 25-34 | Navigate back -> New Recipe -> type Title, Description, Servings, Time, Ingredients, scroll, Directions -> SAVE |
| Complete | 35 | complete_task(status="success") |

## Root Cause Analysis

### Primary Failure: Field Label Contamination (Hint Text Prepended to Values)

The Broccoli app's `EditText` fields show their hint text as a prefix in the accessibility tree text property. When the agent used `type` action on these fields, the resulting stored values were contaminated with the hint label:

**Evidence from saved recipe detail views:**
- Title field: `TitleThai Peanut Noodle Salad` (should be: `Thai Peanut Noodle Salad`)
- Description field: `DescriptionA quick and easy meal, perfect for busy weekdays.` (should be: `A quick and easy meal...`)
- Servings field: `Servings8 servings` (should be: `8 servings`)
- Time field: `Time3 hrs` (should be: `3 hrs`)
- Ingredients field: `Ingredientsas desired` (should be: `as desired`)
- Directions field: `DirectionsToss cooked noodles...` (should be: `Toss cooked noodles...`)

This contamination occurs because the `type` action **appends** text to whatever is already in the field, and the Broccoli app's EditText fields apparently have their hint text present as actual content (or the type action doesn't clear existing text first). The accessibility tree shows `hint_text: "Title"` separately from `text: "TitleThai Peanut Noodle Salad"`, confirming the hint/label became part of the stored value.

**Impact**: The scorer checks exact field value matches. With every field value prefixed by its label name (e.g., "TitleThai Peanut Noodle Salad" instead of "Thai Peanut Noodle Salad"), none of the 3 recipes would pass scoring checks. This explains the 0.0 score despite all 3 recipes being mechanically created.

### Secondary Issue: No Verification Before Completion

The agent called `complete_task(status="success")` immediately after saving the third recipe without:
1. Returning to the recipe list to verify all 3 new recipes appeared
2. Opening any saved recipe to verify field values were correct
3. Noticing that the recipe list at turn 25 (visible between recipe 2 and recipe 3 creation) showed only pre-existing recipes (Chickpea Vegetable Soup, Eggplant Parmesan, Greek Salad Pita Pockets, Mango Chicken Curry, Quick Fried Rice, Salmon with Dill Sauce) -- the newly added Thai Peanut Noodle Salad did NOT appear

### Tertiary Issue: Wasted Turns on Cook Mode Navigation

After saving recipe 1 (turn 12), the agent was taken to the recipe detail view. At turn 13, it clicked element_index 23 thinking it was a "Cook button to add the second recipe" -- but this was actually the Cook mode in Broccoli. It then had to Cancel (turn 14) and navigate back (turn 15), wasting 3 turns.

## Scoring Context
- **Foreground at scoring**: `com.flauschcode.broccoli / .recipe.details.RecipeDetailsActivity`
- **UI element count**: 0 (scorer found zero matching elements)
- The final screen was viewing the last recipe's detail view (BBQ Chicken Quesadillas), not the recipe list

## Key Observations

### 1. Hint Text / Type Action Bug
The `type` action on Broccoli's EditText fields does not clear existing content (including the hint text visible in the a11y tree). This is a systemic issue that would affect ALL Broccoli recipe creation tasks. The a11y tree shows the hint as part of the text property even for empty fields -- e.g., after turn 6 when Title was typed, it read `text: "TitleThai Peanut Noodle Salad"` (hint "Title" + typed value). This suggests the `type` action appended rather than replaced.

### 2. Actually Saved or Just Displayed?
The screenshots confirm the recipes were saved -- after each SAVE click, the app transitioned to a RecipeDetailsActivity showing the recipe content. However, all values had the label prefix issue. Looking at the recipe list at turn 25, the newly created "Thai Peanut Noodle Salad" was NOT visible in the alphabetical list (it would appear between "Salmon with Dill Sauce" and nothing below), which suggests either: (a) the recipe was saved but with the corrupted title "TitleThai Peanut Noodle Salad" and sorted under T, or (b) the list was not scrolled far enough.

### 3. Agent Strategy Was Sound
The overall plan -- read from Markor, store to scratchpad, open Broccoli, create 3 recipes -- was correct and efficient. The scratchpad was used well to capture all recipe data in one write. The form-filling sequence was methodical and hit the right fields.

## Failure Classification

| Category | Details |
|----------|---------|
| **Type** | Platform/Tool Bug + No Verification |
| **Root cause** | `type` action prepends hint text to value in Broccoli app's EditText fields |
| **Agent cognition** | Agent did not notice data corruption in the a11y tree feedback after typing |
| **Verification gap** | Agent did not verify recipes were correctly saved before completing |
| **Turns wasted** | 3 turns on Cook mode detour (turns 13-15) |
| **Could succeed with more turns?** | No -- the hint-text contamination is a systemic issue the agent cannot workaround without clearing fields first |

## Recommendations

### For Platform/Tool Layer
1. **Fix `type` action for Broccoli EditText fields**: The type action should clear the field before typing, or ensure hint text is not treated as existing content. Investigate whether this is a Broccoli-specific a11y issue or a generic `type` action bug.
2. **Alternative**: Add a `clear_and_type` action or ensure the `type` action performs `setText` rather than `appendText`.

### For Agent Prompt/Cognition
1. **Post-type verification**: After typing into a field, the agent should check the resulting field value in the next turn's a11y tree. If it shows `TitleThai Peanut Noodle Salad` instead of `Thai Peanut Noodle Salad`, the agent should recognize the corruption and attempt to fix it (e.g., clear the field and retype, or use a different input strategy).
2. **Pre-completion verification for multi-item tasks**: For tasks involving creating multiple items, the agent should verify at least one item was correctly created before proceeding to create the rest. Add to the prompt: "After saving the first item, return to the list to verify it appears correctly before creating subsequent items."
3. **Recognize the Cook mode trap in Broccoli**: After saving a recipe in Broccoli, the detail view has a Cook button that should be avoided. The agent should navigate back to the list immediately.

### For Anti-Loop / Efficiency
4. The agent spent 3 turns stuck in Cook mode. The prompt's form-filling tips could include a Broccoli-specific hint: "After saving a recipe in Broccoli, navigate back to the recipe list -- do not interact with the Cook/Timer features."

## Counterfactual

If the `type` action had correctly cleared field content before typing (or if hint text was not part of the a11y text), this task would likely score 1.0. The agent's strategy was correct, all 3 recipes were created with all required fields, and the task completed in 35 turns with room to spare. The failure is primarily a tool-level bug, not an agent cognition failure -- though the agent should have noticed the data corruption if it had verified its work.

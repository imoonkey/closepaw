# RecipeAddMultipleRecipesFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 50/50 | **Reason**: MaxTurnsReached | **Duration**: 349s

**Goal**: Add the recipes from recipes.txt in Markor to the Broccoli recipe app.

## Root Cause

**Category**: TurnExhaustion

The agent successfully saved 2 of 3 recipes but ran out of turns while filling in the 3rd recipe (BBQ Chicken Quesadillas). Each recipe takes ~14 turns to fill all 6 fields (title, description, servings, time, ingredients, directions) plus scroll + save + navigate. With 50 turns and 3 recipes, the budget is tight. Two wasted turns (22-23) on a Cook button misclick and recovery further eroded the margin.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(Markor)` | Y | G | G | G | -- |
| 2 | `click(recipes.txt)` | Y | G | G | G | -- |
| 3 | `long_press(text)` | Y | G | G | G | Select text |
| 4 | `click(Select all)` | Y | G | G | G | Fallback to gesture_tap |
| 5 | `click(Copy)` | Y | G | G | G | Copied all 3 recipes |
| 6 | `open_app(Broccoli)` | Y | G | G | G | -- |
| 7-21 | Fill recipe 1 fields + save | Y | G | G | G | Thai Peanut Noodle Salad saved |
| 22 | `click(Cook button)` | Y | B | B | G | Misclick — entered Cook mode instead of navigating back |
| 23-24 | Back + navigate up | Y | G | G | G | Recovery from Cook mode |
| 25-39 | Fill recipe 2 fields + save | Y | G | G | G | Garlic Butter Shrimp saved |
| 40 | Navigate back to list | Y | G | G | G | -- |
| 41-50 | Fill recipe 3 fields (partial) | Y | G | G | G | BBQ Chicken Quesadillas — got through title, desc, servings, time. Missing ingredients, directions, save |

### Key Turns

**Turn 22** — After saving recipe 1, the agent clicked a "Cook" button (element_index 21) instead of navigating back to the recipe list. This wasted 2 turns (22 for the misclick, 23-24 for recovery). While not catastrophic, these 2 turns were the exact deficit that prevented completing recipe 3.

**Turn 50** — The agent entered "20 mins" into the Time field for recipe 3. Still needed: ingredients, directions, and save — at least 5 more turns. Turn budget exhausted.

## What Worked

- Efficient strategy: copy all text from Markor first, then switch to Broccoli once. No unnecessary app-switching.
- No hint text contamination (the R1 bug). All fields were filled with clean values.
- Successfully completed 2 full recipes with correct data.
- Good recovery from the turn 22 misclick — only lost 2 turns.
- Removing loop-detection warnings worked as intended: the agent used all 50 turns instead of self-terminating at turn 22 like in R6.

## What Didn't Work

- 14+ turns per recipe is too expensive for a 50-turn budget with 3 recipes. The task inherently requires ~45-48 turns (3 recipes x ~15 turns each, plus 5 turns for Markor read/copy + app switch).
- Turn 22 Cook button misclick cost 2 turns that were critical to completion.
- No strategic awareness: the agent didn't prioritize speed or skip optional fields when budget was tight.

## Recommendations

- **Turn efficiency**: The per-recipe cost (~15 turns: click field, type, click field, type, scroll, repeat, save, navigate) is the core bottleneck. A `batch_fill_form` or multi-field typing tool could cut this to 3-5 turns per recipe.
- **No code change needed for loop detection**: Removing the warnings achieved the desired effect — the agent persisted through all 50 turns and made real progress on every turn.
- **Progress vs R6**: Major improvement. R6 scored 0.0 with only 1 recipe partially done (self-terminated at turn 22). R7 completed 2 of 3 recipes and was 5 turns away from completing the 3rd. The score is the same (0.0) but the behavioral improvement is significant.

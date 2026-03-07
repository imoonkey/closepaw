# RecipeAddMultipleRecipesFromImage — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 50/50 | **Reason**: MaxTurnsReached | **Duration**: 437s

**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

## Root Cause

**Category**: TurnExhaustion

The agent executed the task near-perfectly — it extracted all 3 recipes from the image, saved 2 of 3 to Broccoli, and was mid-way through filling the 3rd recipe's form when it hit the 50-turn limit. Each recipe requires ~16 turns (6 fields x click+type = 12, plus scroll, save, navigate back), totaling ~48+ turns for 3 recipes plus 6 turns for app opening and image reading. The task is structurally infeasible in 50 turns with the current 1-action-per-turn architecture.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Simple Gallery Pro")` | N | G | B | B | App name wrong; error suggested "Gallery" |
| 2 | `open_app("Gallery")` | Y | G | G | G | Recovered from turn 1 |
| 3-4 | navigate to image | Y | G | G | G | Opened DCIM folder, clicked image |
| 5 | `scratchpad(write)` | — | G | G | G | Extracted all 3 recipes from screenshot |
| 6 | `open_app("Broccoli")` | Y | G | G | G | — |
| 7-21 | Recipe 1: Eggplant Parmesan | Y | G | G | G | New Recipe -> fill 6 fields -> Save (15 turns) |
| 22-23 | Navigate back + New Recipe | Y | G | G | G | — |
| 24-37 | Recipe 2: Baked Cod with Lemon and Dill | Y | G | G | G | Fill 6 fields -> Save (14 turns) |
| 38-39 | Navigate back + New Recipe | Y | G | G | G | — |
| 40-50 | Recipe 3: Chickpea Vegetable Soup (partial) | Y | G | G | G | Filled title, desc, servings, time, ingredients — ran out before directions + save |

### Key Turns

**Turn 1** — Agent tried "Simple Gallery Pro" which doesn't exist on this device; the app is registered as "Gallery". Minor grounding miss but recovered in turn 2 with zero wasted effort.

**Turn 5** — Excellent use of scratchpad to capture all 3 recipes at once from the image, avoiding repeated app switches.

**Turn 50** — Agent had just typed ingredients for recipe 3. Needed 2 more turns (click directions, type directions) plus 1 turn to save. Was 3 turns away from completion.

## What Worked

- Flawless recipe extraction from image via screenshot — all 3 recipes captured in a single scratchpad write
- Perfect form-filling workflow: systematic click-type pattern with no wasted turns
- No loops, no repeated actions, no navigation failures — a massive improvement over R6
- Removal of loop detection warnings had no negative effect; the agent was never looping to begin with on this task
- Smooth navigation between recipe list and new recipe form

## What Didn't Work

- The task is structurally at the edge of feasibility in 50 turns: 3 recipes x ~16 turns each + 6 overhead = ~54 turns needed
- Each form field requires 2 turns (click to focus + type) — no batch input capability
- The initial app name miss ("Simple Gallery Pro" vs "Gallery") wasted 1 turn

## Recommendations

- **Turn budget**: This task needs ~54 turns minimum. Either increase max_turns or optimize the form-filling pattern.
- **Batch type tool**: A tool that fills multiple form fields in a single turn would dramatically reduce turn count for data-entry tasks. Even a 2-field batch would save ~12 turns here.
- **App name fuzzy matching**: The agent tried "Simple Gallery Pro" when the installed app is "Gallery". A fuzzy match or alias table could save the retry turn.
- **No prompt changes needed**: The agent's cognition was correct throughout. This is purely a turn-budget / tool-efficiency problem.

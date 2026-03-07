# RecipeAddMultipleRecipesFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 22/50 | **Reason**: Error | **Duration**: 112s

**Goal**: Add the recipes from recipes.txt in Markor to the Broccoli recipe app.

## Root Cause

**Category**: FalseCompletion

The agent successfully read 3 recipes from Markor, stored them in the scratchpad, and entered the first recipe (Thai Peanut Noodle Salad) into Broccoli across ~16 turns. It then self-terminated at turn 22 citing a "loop warning" and admitted 2 of 3 recipes were not added. The agent gave up prematurely with 28 turns remaining (22/50 used). No actual tool failures or infra errors occurred -- every action succeeded.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `write_todos` + `open_app(Markor)` | Y | G | G | G | Good planning and app launch |
| 2 | `mobile_action(click recipes.txt)` | Y | G | G | G | Opened recipes.txt |
| 3 | `scratchpad(write recipes)` | N | G | G | G | Stored all 3 recipes before switching apps |
| 4 | `write_todos` + `open_app(Broccoli)` | Y | G | G | G | Switched to Broccoli |
| 5 | `mobile_action(click add button)` | Y | G | G | G | Opened new recipe form |
| 6 | `scratchpad(read)` | N | G | G | G | Retrieved recipe data |
| 7 | `mobile_action(type title)` | Y | G | G | G | Entered "Thai Peanut Noodle Salad" |
| 8 | `mobile_action(type description)` | Y | G | G | G | Entered description |
| 9 | `mobile_action(type servings)` | Y | G | G | G | Entered "8 servings" |
| 10 | `mobile_action(scroll down)` | Y | G | G | G | Revealed more fields |
| 11 | `scratchpad(write)` + `mobile_action(type time)` | Y | G | G | G | Redundant re-store, but typed "3 hrs" |
| 12 | `mobile_action(click save)` | Y | B | G | G | Saved incomplete recipe (no ingredients/directions) |
| 13 | `mobile_action(click cook button)` | Y | B | B | G | Wrong button; entered cook view |
| 14 | `system_button(back)` | Y | G | G | G | Recovered from wrong view |
| 15 | `mobile_action(click overflow menu)` | Y | G | G | G | Looking for edit option |
| 16 | `mobile_action(click Edit)` | Y | G | G | G | Entered edit mode |
| 17 | `mobile_action(type ingredients)` | Y | G | G | G | Filled ingredients |
| 18 | `mobile_action(scroll down)` | Y | G | G | G | Revealed directions field |
| 19 | `mobile_action(type directions)` | Y | G | G | G | Filled directions |
| 20 | `mobile_action(click save)` | Y | G | G | G | Saved completed recipe 1 |
| 21 | `system_button(back)` | Y | G | G | G | Back to recipe list |
| 22 | `complete_task(failure)` | N/A | B | N/A | N/A | Gave up with 28 turns left |

### Key Turns

**Turn 12** -- The agent saved the recipe before filling all fields (ingredients and directions were missing). This forced a detour through turns 13-16 to find and use the Edit function, wasting 4 turns. The agent should have scrolled down and filled all fields before saving.

**Turn 13** -- After saving, the agent clicked a "cook" button instead of navigating back to add the next recipe or re-editing the current one. Wrong reasoning about what the button did.

**Turn 22** -- The agent self-terminated citing a "loop warning" despite having 28 turns remaining and no actual loop. It had successfully completed recipe 1 and was back at the recipe list with a visible "add" button. This is the critical failure: the agent chose to give up rather than continue the remaining mechanical work.

## What Worked

- Good initial planning: read all recipes from Markor, stored them in scratchpad before switching apps
- Scratchpad usage was effective for cross-app data transfer
- All 25 tool calls succeeded; zero infrastructure failures
- Successfully added the first complete recipe after the edit detour

## What Didn't Work

- Agent saved recipe 1 prematurely (turn 12) before filling all fields, wasting 4 turns on re-editing
- Agent self-terminated at turn 22/50 claiming a "loop warning" that did not exist in the tool results
- Only 1 of 3 recipes was added -- the agent quit at ~44% turn budget used
- The task requires ~10 turns per recipe (form fields + scroll + save), so 3 recipes need ~30 turns, well within the 50-turn budget

## Recommendations

- **Prompt: discourage premature self-termination** -- The LLM hallucinated a "loop warning" and used it as justification to quit. Add system prompt guidance: "Never self-terminate early when progress is being made and turn budget remains. A loop means 3+ identical actions with no screen change."
- **Prompt: fill all form fields before saving** -- For recipe/form-filling tasks, the agent should scroll through the entire form and fill all fields before hitting save, avoiding costly edit detours.
- **Turn budgeting awareness** -- The agent should reason about turns remaining vs. turns needed. At turn 22 with 28 left, it had ample budget for 2 more recipes at ~10 turns each.
- **Investigate phantom loop detection** -- The agent referenced a "loop warning" that does not appear in any tool result. Either (a) the LLM fabricated it or (b) it was injected via a mechanism not captured in the trace. If (a), this is a reasoning error that prompt tuning should address. If (b), the loop detection threshold may be too aggressive for form-filling tasks.

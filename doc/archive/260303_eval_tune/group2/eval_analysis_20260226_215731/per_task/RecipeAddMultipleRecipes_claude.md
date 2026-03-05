# RecipeAddMultipleRecipes -- FAIL (MaxTurnsReached)

## Task
- **Goal**: Add 3 recipes to the Broccoli app (Chicken Alfredo Pasta, Quinoa Salad with Vegetables, Butternut Squash Soup), each with description, servings, preparation time, ingredients, and directions.
- **Turns**: 30 (max)
- **Duration**: 267.3s
- **Model**: qwen3.5
- **Tool failures**: 0

## Step-by-step Analysis

- **Turn 1**: Opened Broccoli app.
- **Turn 2**: Clicked "New Recipe" button (element 33) to start adding the first recipe.
- **Turn 3-9**: Filled in fields for the first recipe (Chicken Alfredo Pasta): title, description, servings, and preparation time. Each field requires a click + type action, consuming 2 turns per field.
- **Turn 10**: Clicked on the Time field to enter preparation time.
- **Turn 11-15**: Continued filling in the first recipe fields (ingredients, directions). Long text like directions required careful typing.
- **Turn 16-19**: Completed the first recipe and saved it, then navigated back to the recipes list.
- **Turn 20**: Clicked "New Recipe" button to start the second recipe (Quinoa Salad with Vegetables).
- **Turn 21-28**: Filled in fields for the second recipe. Similar pace of ~2 turns per field.
- **Turn 29-30**: Still working on the second recipe (around ingredients/directions). The third recipe was never started.

## Root Cause Classification
**Orchestration + Turn budget gap**

The task requires approximately 10 turns per recipe (click field + type for each of 6 fields, plus save + navigate back = ~14 turns). With 3 recipes, the minimum theoretical turns needed is ~42, far exceeding the 30-turn limit. The agent performed well -- no wasted turns -- but the task is inherently too large for the budget.

## Key Issues
- Turn budget of 30 is insufficient for adding 3 complex recipes with 6 fields each.
- Each recipe requires approximately 14 turns (7 fields x 2 actions each: click to focus + type).
- Agent completed 1 recipe and was partway through the 2nd when time ran out. The 3rd recipe was never started.
- No tool failures -- the agent was executing correctly but running out of turns.
- The single-action-per-turn model makes form-filling tasks very turn-expensive.

## Suggested Fixes
- **Increase turn budget**: For complex multi-item form-filling tasks, increase max_turns to 45-50.
- **Batch text input**: If the agent could type into multiple fields in sequence without re-capturing the screen each time, it would be faster.
- **Shell/intent shortcut**: If the Broccoli app supports content providers or intents, recipes could potentially be added via shell commands, bypassing the UI entirely.
- **Task decomposition**: If the agent had a way to detect that a task requires >30 turns, it could prioritize speed over verification, skipping redundant navigation steps.
- **Type action with less overhead**: Reduce the overhead per type action by combining click-and-type into a single operation where possible.

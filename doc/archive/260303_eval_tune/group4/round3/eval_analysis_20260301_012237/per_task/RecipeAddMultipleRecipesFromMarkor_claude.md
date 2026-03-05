# RecipeAddMultipleRecipesFromMarkor - Round 3 Analysis

## Task
Read recipes from `recipes.txt` in Markor and add them to the Broccoli recipe app.

## Result
- Score: 0.0 (FAIL)
- Turns: 30/30
- Stop reason: MaxTurnsReached (no complete_task call)
- Duration: 299s

## Agent Behavior Summary
1. Opened Markor, found and opened recipes.txt (turns 1-2)
2. Stored all 3 recipes to scratchpad (turn 3): Thai Peanut Noodle Salad, Garlic Butter Shrimp, BBQ Chicken Quesadillas
3. Opened Broccoli app (turn 4), clicked New Recipe (turn 5)
4. Started filling recipe forms sequentially
5. Completed Recipe 1 and Recipe 2 successfully
6. Was filling Recipe 3 (BBQ Chicken Quesadillas) fields when turns ran out
7. Last 3 turns: typing time (20 mins), ingredients, and directions for recipe 3 - was almost done but hit max turns without saving

## Root Cause Analysis
**Turn exhaustion (P6)**: Same pattern as RecipeAddMultipleRecipesFromImage. The agent was efficient (read all recipes via shell/UI quickly, stored in scratchpad, switched to Broccoli), but the form-filling UI for 3 recipes requires too many turns.

The agent was very close: turns 28-30 were the last 3 fields of recipe 3. It needed ~2 more turns to save and complete_task.

## Key Observations
- Agent was more efficient here than image version (only 3 turns to read + store recipes vs 5)
- Good use of scratchpad for cross-app data transfer
- No errors or missteps - purely a turn budget issue
- Agent didn't call complete_task - it was still actively working when turns expired

## Recommendations
- Same as RecipeAddMultipleRecipesFromImage: increase max_turns
- Consider shell-based recipe creation (write to Broccoli's database directly)
- Optimize to combine multiple type actions if the forms support tab-order

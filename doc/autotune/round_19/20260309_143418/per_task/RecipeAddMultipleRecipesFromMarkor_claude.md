# RecipeAddMultipleRecipesFromMarkor — R19 Analysis

## Result: 0.0 (32 turns)

## Behavior Summary
The agent correctly read all 3 recipes from Markor's recipes.txt and stored them in scratchpad. It successfully created the first recipe (Thai Peanut Noodle Salad) in Broccoli. However, instead of navigating back to the recipe list and creating a new recipe for the second entry, the agent clicked Edit on the just-saved Thai Peanut Noodle Salad detail view and overwrote all its fields with Garlic Butter Shrimp data. This destroyed the agent's Thai Peanut Noodle Salad entry. A pre-existing Thai Peanut Noodle Salad with different field values happened to exist in the database, masking the problem during the agent's final verification. BBQ Chicken Quesadillas was added correctly via a fresh new-recipe form.

## Root Cause
**Category: Reasoning (Navigation Strategy)**

The agent did not know how to navigate from a recipe detail view back to the recipe list to create a new recipe. After saving recipe 1, it was on the detail screen. It tried the FAB (which opened Cook mode), backed out, then clicked "Edit" -- intending to "reuse the form" by overwriting fields. This is a fundamental misunderstanding of app navigation: Edit updates the existing record, not creates a new one. The correct path was: press Navigate Up (back arrow) to return to the recipe list, then tap the "New Recipe" FAB.

## Key Observations
- Markor reading was flawless: all 3 recipes captured with correct fields in scratchpad
- Recipe 1 (Thai Peanut Noodle Salad): created correctly, then overwritten by recipe 2
- Recipe 2 (Garlic Butter Shrimp): saved with correct data but into the record that was originally recipe 1
- Recipe 3 (BBQ Chicken Quesadillas): created correctly via fresh new-recipe form
- A pre-existing Thai Peanut Noodle Salad (with different field values) existed in the DB, causing the agent to believe all 3 were present during verification
- The agent's thought at turn 17 explicitly states "Replace the title with the second recipe to start reusing the form for a new recipe entry" -- confirming it believed editing = creating
- FalseCompletion: the agent verified the list saw "Thai Peanut Noodle Salad" but that was the pre-existing entry, not the one from recipes.txt
- Score 0.0 indicates the evaluator uses all-or-nothing scoring (2/3 correct recipes were insufficient)

## Recommendation
Add a Broccoli app skill instruction that explicitly states: "To add multiple recipes, always navigate back to the recipe list between entries and use the New Recipe FAB. Never use Edit on an existing recipe to create a new one." Additionally, the agent should compare field values (not just names) during verification to catch pre-existing recipes with different data.

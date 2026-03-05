# RecipeAddMultipleRecipesFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: ~30 | **Reason**: Incomplete/FalseCompletion | **Duration**: ~390s
**Goal**: Add the recipes from recipes.txt in Markor to the Broccoli recipe app.

## Root Cause
**Category**: FalseCompletion
**Summary**: Agent opened Markor, read recipes.txt content, then switched to Broccoli to enter recipes. However, the recipe data entry was incomplete or incorrect — the agent was still typing description fields when it ran out of turns or completed prematurely. 31 tool calls across many turns indicate the agent was actively working but likely didn't finish entering all recipes.

## Key Actions
- Turn 1: `open_app("Markor")` — correct
- Turns 2-3: Navigate to recipes.txt file
- Turns 4-6: Read recipe content, navigate through file
- Turn 7+: Switch to Broccoli app, start entering recipes
- Last action: `type(input_text="A quick and easy meal...", elem=12)` — still entering description

## Failure Points
1. **Incomplete multi-item data entry** — adding multiple recipes requires many turns (navigating forms, filling fields, saving, creating new)
2. **May have lost context on recipe content** — recipes.txt content read early may not have been fully memorized or stored in scratchpad
3. **Turn budget insufficient** for multi-recipe entry

## Suggested Fix
- For multi-recipe tasks from text files: use shell to read file content into scratchpad once, then systematically enter each recipe
- Prompt guidance for multi-item entry turn management

---

# RecipeDeleteDuplicateRecipes2 — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 29 | **Reason**: FalseCompletion | **Duration**: ~120s
**Goal**: Delete all but one of any recipes in the Broccoli app that are exact duplicates, ensuring at least one instance of each unique recipe remains.

## Root Cause
**Category**: FalseCompletion
**Summary**: Agent deleted 4 duplicates (matching both title AND description) but the eval may define "exact duplicate" differently — possibly same title only. Agent's answer shows remaining recipes include 2× "Caprese Salad Skewers" and 3× "Pan-Seared Salmon with Quinoa" which the eval likely considers duplicates. Same root cause as ExpenseDeleteDuplicates2 — definition mismatch between agent's and eval's notion of "exact duplicate."

## Key Actions
- Turn 1: `open_app(Broccoli)`
- Turns 2-28: Systematic scanning, deleting, and verification
- Turn 28: `scratchpad(write)` — documented final state
- Turn 29: `complete_task(success)` — claimed 4 duplicates deleted

## Failure Points
1. **Duplicate definition mismatch** — agent treated title+description as unique key, eval likely uses title only
2. **Remaining duplicates by title**: 2× Caprese Salad Skewers, 3× Pan-Seared Salmon with Quinoa
3. Same systemic issue as ExpenseDeleteDuplicates2

## Suggested Fix
- Add prompt guidance: "When deleting duplicate items, treat items with the SAME NAME/TITLE as duplicates regardless of other field differences, unless the task explicitly says otherwise"
- This is a recurring cognition issue — the agent over-specifies uniqueness criteria

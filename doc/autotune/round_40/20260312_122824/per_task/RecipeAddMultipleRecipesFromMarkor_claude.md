# RecipeAddMultipleRecipesFromMarkor — Cog-Tune Analysis

**Score**: 1.0 | **Turns**: 30/30 | **Reason**: GoalAchieved | **Duration**: 247s

**Goal**: Read recipes from a Markor note and add them to Broccoli.

## Root Cause

**Category**: Success

The agent used all 30 turns (43 tool calls) to read recipe data from Markor and transcribe it into Broccoli. The maxed-out turn and tool call counts reflect the complexity of this two-app, multi-recipe transfer task. Goal was still achieved, indicating the agent stayed on track despite the high interaction volume.

## What Worked

- Correctly read recipe content from Markor before switching apps
- Accurately transferred recipe fields (name, ingredients, steps) to Broccoli
- No critical failures — stayed focused on goal despite high turn pressure

## What Didn't Work

- 30/30 turns used — no margin for error; any additional complication would have caused failure
- 43 tool calls for what should require ~25–30 suggests redundant perception or re-reads

## Turn-by-Turn Grouping

| Turns | Phase | Notes |
|-------|-------|-------|
| 1–6 | Open Markor, read recipe source note | Data gathering |
| 7–12 | Switch to Broccoli, navigate to add recipe | App switch |
| 13–20 | Add first recipe | Entry |
| 21–26 | Add second recipe | Entry |
| 27–30 | Add third recipe, verify | Entry + completion |

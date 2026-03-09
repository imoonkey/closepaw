# RecipeAddMultipleRecipesFromImage - Run 20260309_150119

## Summary

| Metric | Value |
|--------|-------|
| Score | 0.0 |
| Turns | 23 |
| Stop Reason | GoalAchieved (FalseCompletion) |
| Tool Failures | 2/29 |
| Duration | 157s |
| Model | gpt-5.4 |
| Perception | hybrid (screenshot_input: true) |

**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

**Validation**: Task expects 3 recipes (`n_rows = 3`). Checks `title`, `description`, `servings`, `preparationTime`, `source`, `ingredients`, `directions` with free-form matching.

## Root Cause: Image Perception Failure (Hallucinated Content + Missed Recipe)

The agent created **2 recipes** ("Orange soup", "Vegetable Soup") but the image contained **3 recipes**. Worse, the extracted content was hallucinated -- the actual image shows recipes like "Baked Cod with Lemon and Dill" and "Chickpea Vegetable Soup", not "Orange soup".

Two distinct failures:
1. **Hallucinated recipe content**: The agent read the image at thumbnail resolution in the Gallery folder view, not full-screen. The small text led to completely fabricated recipe names and details.
2. **Missing third recipe**: Only 2 of 3 required recipes were created, guaranteeing score 0.0 even if the content were correct.

## Turn-by-Turn Flow

| Phase | Turns | Actions |
|-------|-------|---------|
| Open Gallery | 1-3 | write_todos, open_app "Simple Gallery Pro" (failed: not found), open_app "Gallery", wait 1500ms |
| Navigate to image | 4 | Click DCIM folder thumbnail |
| Extract from image | 5 | scratchpad: wrote extracted recipes (hallucinated 2 of 3) |
| Switch to Broccoli | 6-7 | write_todos update, open_app "Broccoli" |
| Create Recipe 1 | 8-12 | Click FAB, type title/desc/ingredients, scroll, type directions, save |
| Navigate back | 13 | Click back to recipe list |
| Create Recipe 2 | 14-19 | Click FAB, type title/desc/ingredients, scroll, type directions, save |
| Verify | 20-21 | Navigate back, scroll to check both recipes visible |
| Complete | 22-23 | write_todos all complete, complete_task "success" |

## Key Observations

### Did the Broccoli skill help with navigation?
**Yes.** The Broccoli skill clearly guided navigation:
- Agent used FAB (element 34) to create new recipes
- Agent used save button (element 7) correctly
- Agent returned to recipe list (element 9) between recipes -- agent_thought explicitly says "per app behavior" / "following the Broccoli app workflow"
- Scroll-then-type pattern for directions field worked cleanly
- Each recipe took ~7 turns, efficient for the form-filling pattern

### Was image perception still unstable?
**Yes, critically so.** The core failure:
- Agent never opened the image full-screen -- it read from the DCIM folder thumbnail view (460x1024 screenshot, image was a small portion)
- Screenshot shows tiny recipe text that is barely legible even to a human
- Agent hallucinated recipe names: extracted "Orange soup" and "Vegetable Soup" when actual image contained different recipes (visible text includes "Baked Cod with Lemon and Dill", "Chickpea Vegetable Soup")
- Agent wrote to scratchpad after seeing only the folder-level thumbnail, not after clicking into the full image
- Agent confidently stated "The screenshot shows two recipes" when there were 3

### How many recipes were created?
**2 of 3 required** -- "Orange soup" and "Vegetable Soup" (both with hallucinated content).

## Failure Classification

| Category | Detail |
|----------|--------|
| Primary | Image perception: hallucinated content from low-resolution thumbnail |
| Secondary | Missing recipe: only extracted 2 of 3 recipes |
| Tertiary | No full-screen image view: agent skipped opening the image to full resolution |

## Recommendations

1. **App skill for Gallery**: Add a Gallery/Simple Gallery Pro skill instructing the agent to always open images full-screen before extracting text (click thumbnail to full view, then extract).
2. **Scratchpad verification**: After extraction, agent should cross-check by re-reading the image at higher resolution. Consider a skill hint: "For image-based tasks, always open the image full-screen and scroll to capture all content before extracting."
3. **Count verification**: The goal says "recipes" (plural) but the agent should verify the count by scrolling through the entire image before declaring extraction complete.
4. **Recipe count in scratchpad**: The extraction scratchpad entry should explicitly note "N recipes found" and the agent should sanity-check this against visual evidence.

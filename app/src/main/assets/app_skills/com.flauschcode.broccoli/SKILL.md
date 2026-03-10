---
name: com.flauschcode.broccoli
description: App-specific guidance for Broccoli recipe manager.
---

# Broccoli Skill

## Adding Multiple Recipes
- To add each new recipe, ALWAYS navigate back to the recipe list first (Navigate Up / back arrow), then tap the FAB (floating action button) to create a new recipe.
- Never use "Edit" on an existing recipe to create a different recipe — Edit updates the existing record, not creates a new one.
- After saving a recipe, you land on its detail view. Press Navigate Up to return to the list before adding the next recipe.

## Filling Recipe Fields
- Only fill fields that have a direct mapping in the source data (title, description, servings, prep time, ingredients, directions).
- Leave the **Source** and **Categories** fields empty unless the source data explicitly provides values for them. Do not fabricate values like "recipes.txt" or "Imported".

## Scrollable UI Elements
- The category row in the expense/recipe form scrolls horizontally. Swipe left to reveal more categories before concluding a category is unavailable.

## Deleting Duplicate Recipes
When asked to delete duplicate recipes:
1. **Scroll the entire recipe list first** to see all recipes. The list may be long (30+ items).
2. **Use scratchpad systematically**: record each recipe's title as you scroll. Group same-title entries.
3. **CRITICAL: Multiple recipes may share the SAME title** but differ in hidden fields (servings, prep time, description). These are NOT duplicates.
4. To find true duplicates among same-titled recipes:
   a. Open EACH recipe with the same title one at a time
   b. Record ALL 7 fields in scratchpad: title, description, servings, prep time, source, categories, ingredients
   c. Compare the recorded fields — only TWO entries that match on ALL fields are the true exact duplicates
5. To delete: open the duplicate recipe → tap 3-dot menu → Delete. Keep exactly ONE copy.
6. Do NOT delete recipes just because they share a title — you must verify ALL fields match.
7. **Track progress in scratchpad**: mark which groups you've checked and which duplicates you've already deleted.

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
2. Identify true duplicates: recipes with IDENTICAL title AND description. Variations with different servings or prep time are NOT duplicates.
3. To delete: long-press a recipe → Delete (or swipe to delete if supported). If no long-press option, open the recipe → tap 3-dot menu → Delete.
4. Keep exactly ONE copy of each unique recipe. Delete the extras.
5. Be careful with similar-looking recipes that have different titles or descriptions — those are NOT duplicates.

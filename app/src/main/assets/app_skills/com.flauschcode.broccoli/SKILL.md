---
name: com.flauschcode.broccoli
description: App-specific guidance for Broccoli recipe manager.
---

# Broccoli Skill

## Adding Recipes
- To add: navigate to recipe list (Navigate Up / back), then tap FAB for new recipe.
- Never use "Edit" on an existing recipe to create a different one.
- After saving, navigate to list and search for the title to verify it was saved. If not found, retry.

## Filling Recipe Fields
- Only fill fields with direct mappings in source data (title, description, servings, prep time, ingredients, directions).
- Leave **Source** and **Categories** empty unless source data explicitly provides them.

## Comparing Recipes (Duplicate Detection)
Same-titled recipes may differ in hidden fields. To identify true duplicates:
1. For each group sharing a title, open EACH recipe and record ALL fields: title, description, servings, prep time, ingredients, directions.
2. Two recipes are duplicates ONLY if ALL fields match.
3. NEVER delete based on list-card matching alone.

## Deleting Recipes
- Open recipe → 3-dot menu → Delete.
- After each deletion, verify the remaining count decreased. If unchanged, retry.

## Scrollable UI
- Category row scrolls horizontally. Swipe left to reveal more categories.

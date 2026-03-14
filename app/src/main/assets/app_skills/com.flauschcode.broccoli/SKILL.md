---
name: com.flauschcode.broccoli
description: App-specific guidance for Broccoli recipe manager.
---

# Broccoli Skill

## Adding Recipes
- To add: navigate to recipe list (Navigate Up / back), then tap FAB for new recipe.
- Never use "Edit" on an existing recipe to create a different one.
- After saving, go back to the recipe list and scroll to verify the recipe appears. If search doesn't find it (long titles may fail search), browse the full list instead.

## Filling Recipe Fields
- Only fill fields with direct mappings in source data (title, description, servings, prep time, ingredients, directions).
- Leave **Source** and **Categories** empty unless source data explicitly provides them.

## Comparing Recipes (Duplicate Detection)
Same-titled recipes may differ in hidden fields. Efficient approach:
1. Scan the list. Group recipes by title. Cards show title + description — if two same-title cards have DIFFERENT descriptions, they cannot be duplicates (skip opening them).
2. For same-title-same-description recipes: open the FIRST, record all fields (servings, prep time, ingredients, directions) to scratchpad. Open EACH subsequent one and compare.
3. If ALL fields match → delete the current recipe immediately (3-dot → Delete) while still viewing it. This avoids re-navigation.
4. Two recipes are duplicates ONLY if ALL fields match. NEVER delete on title alone.

## Deleting Recipes
- Open recipe → 3-dot menu → Delete.
- After each deletion, verify the remaining count decreased. If unchanged, retry.

## Scrollable UI
- Category row scrolls horizontally. Swipe left to reveal more categories.

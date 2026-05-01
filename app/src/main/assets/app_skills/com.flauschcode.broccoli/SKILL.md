---
name: app-broccoli
description: App-specific guidance for Broccoli recipe manager.
metadata:
  package: com.flauschcode.broccoli
---

- Add recipes via FAB from the recipe list. Never use "Edit" on an existing recipe to create a different one.
- Long titles may fail search. Browse the full list if search doesn't find a recipe.
- Only fill fields with direct mappings in source data (title, description, servings, prep time, ingredients, directions). Leave Source and Categories empty unless explicitly provided.
- Same-titled recipes may differ in hidden fields. Cards show title + description — if descriptions differ, they're not duplicates. If descriptions match, open each and compare all fields.
- Duplicates must match ALL fields (servings, prep time, ingredients, directions). Never delete on title alone.
- Delete: open recipe → 3-dot menu → Delete.
- Category row scrolls horizontally. Swipe left to reveal more.

## Safety

**DANGEROUS -- ask user before:**
- Deleting recipes

**SAFE -- proceed normally:**
- Adding new recipes
- Reading and browsing recipes
- Editing recipe fields as specified in the task

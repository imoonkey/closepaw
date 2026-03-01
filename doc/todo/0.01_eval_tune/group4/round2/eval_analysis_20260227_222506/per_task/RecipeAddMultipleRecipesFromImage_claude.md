# RecipeAddMultipleRecipesFromImage — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 30 (max) | **Reason**: Added 2/3 recipes, ran out of turns before 3rd
**Goal**: Add the recipes from recipes.jpg in Simple Gallery Pro to the Broccoli recipe app.

## Root Cause
**Category**: TurnExhaustion (reclassified from PerceptionGap)
**Summary**: The agent successfully read 3 recipes from the image via hybrid mode (screenshot), stored them in scratchpad, and entered 2 of 3 recipes into Broccoli. It ran out of turns before entering the 3rd recipe (Chickpea Vegetable Soup). Scoring is binary (all 3 required) so 2/3 = 0.0. Hybrid mode worked correctly. The agent also wasted 3 turns (28-30) on verification/searching instead of starting recipe 3.

## Turn-by-Turn Analysis

### Phase 1: Navigate to image (Turns 1-5)
- Turn 1: `open_app("Simple Gallery Pro")` → FAILED (app not found)
- Turn 2: `open_app("Gallery")` → success, opened alternative gallery
- Turn 3: `click(idx=0)` → clicked DCIM folder
- Turn 4: `click(idx=10)` → enter DCIM subfolder
- Turn 5: `click(idx=10)` → opened recipes.jpg fullscreen

### Phase 2: Extract recipes from image (Turn 6)
- Turn 6: `scratchpad(write)` — stored all 3 recipes extracted from screenshot:
  1. **Eggplant Parmesan**: 8 servings, 10 mins, ingredients "varies"
  2. **Baked Cod with Lemon and Dill**: 2 servings, 45 mins, ingredients "subject to change"
  3. **Chickpea Vegetable Soup**: 2 servings, 2 hrs, ingredients "as desired"
- Turn 6: `open_app("Broccoli")` → opened recipe app
- **Note**: Ingredient fields contain placeholder text ("varies", "subject to change", "as desired") — model may have failed to read actual ingredients from the image, or the image itself may use these generic placeholders.

### Phase 3: Enter recipe 1 — Eggplant Parmesan (Turns 7-17, 11 turns)
- Turn 7: `wait(2000ms)` — wait for Broccoli to load
- Turn 8: `click(idx=32)` — New Recipe button
- Turn 9: `type("Eggplant Parmesan", idx=10)` — title
- Turn 10: `type("An ideal recipe for...", idx=12)` — description
- Turn 11: `type("8 servings", idx=14)` — servings
- Turn 12: `type("10 mins", idx=15)` — prep time
- Turn 13: `scroll(down, idx=7)` — reveal ingredients/directions
- Turn 14: `type("varies", idx=16)` — ingredients
- Turn 15: `type("Slice eggplant, bread, and fry...", idx=17)` — directions
- Turn 16: `click(idx=5)` — SAVE
- Turn 17: `click(idx=7)` — navigate back to recipe list

### Phase 4: Enter recipe 2 — Baked Cod (Turns 18-27, 10 turns)
- Turn 18: `click(idx=32)` — New Recipe button
- Turn 19: `type("Baked Cod with Lemon and Dill", idx=10)` — title
- Turn 20: `type("A quick and easy meal...", idx=12)` — description
- Turn 21: `type("2 servings", idx=14)` — servings
- Turn 22: `type("45 mins", idx=15)` — prep time
- Turn 23: `scroll(down, idx=7)` — reveal ingredients/directions
- Turn 24: `type("subject to change", idx=16)` — ingredients
- Turn 25: `type("Place cod fillets in a baking dish...", idx=17)` — directions
- Turn 26: `click(idx=5)` — SAVE
- Turn 27: `click(idx=7)` — navigate back to recipe list

### Phase 5: Wasted on verification (Turns 28-30)
- Turn 28: `scroll(down, idx=9)` — "check if Baked Cod was saved"
- Turn 29: `click(idx=7)` — "Search button to verify recipes"
- Turn 30: `type("Eggplant Parmesan", idx=7)` — searching instead of adding recipe 3
- **SESSION ENDED: MaxTurnsReached**

## Failure Points
1. **Turn exhaustion**: Each recipe takes ~10 turns (new recipe + 6 fields + scroll + save + navigate back). With 3 recipes that's ~30 turns just for data entry, plus 7 turns for initial image navigation = 37 turns needed, but only 30 available.
2. **Turn waste (3 turns)**: Turns 28-30 spent verifying instead of starting recipe 3. If agent had immediately clicked "New Recipe" at turn 28, it would have had 3 turns — still not enough for 10-turn recipe entry, but would have gotten further.
3. **Scoring is binary**: Scorer checks ALL 3 recipes must be in the database. 2/3 = 0.0, no partial credit.
4. **Data accuracy concern**: Ingredients ("varies", "subject to change", "as desired") may not match expected values. Even if all 3 recipes were entered, fuzzy matching of ingredients might still fail.

## What Worked
- **Hybrid mode worked perfectly** — agent read 3 full recipes from the image screenshot in a single turn
- Clean recipe entry workflow: consistent pattern of type all fields → scroll → type remaining → save → navigate back
- Good scratchpad usage: stored all recipes upfront for reference during entry
- Successfully saved 2 complete recipes

## What Didn't Work
- **Not enough turns**: 30 turns insufficient for 7 (navigation) + 30 (3 recipes x 10) = 37 turns needed
- **Verification waste**: 3 precious turns spent confirming saves instead of entering recipe 3
- **Ingredient quality**: placeholder values instead of actual ingredient lists

## Analysis: Is this just a turn budget issue?
**Yes, primarily.** The agent's workflow was clean and efficient. The core issue is arithmetic:
- 7 turns for image navigation + app opening
- ~10 turns per recipe (create, fill 6 fields with scroll, save, navigate back)
- 3 recipes × 10 = 30 turns for data entry
- Total needed: ~37 turns. Available: 30. Deficit: ~7 turns.

Even optimizing away the verification waste (3 turns) and the wait (1 turn), the agent would have ~33 turns of useful work with a 30-turn budget. Recipe 3 would still be incomplete.

**Possible mitigations** (not prompt changes — structural):
1. **Increase max_turns to 40** for multi-recipe tasks
2. **Efficiency prompt**: "When adding multiple items, skip verification between items. Verify only after all items are added."
3. **Faster entry**: If the agent could type multiple fields without navigating (e.g., if the form doesn't require scrolling), it could save ~1 turn per recipe. But the Broccoli app form genuinely requires scrolling.
4. **App name mapping**: Turns 1-2 could have been 1 turn if Simple Gallery Pro name was known.

## Scoring Logic
Scoring (recipe.py:354-384, sqlite_validators.py:118-192) checks:
- ALL 3 expected recipes must exist in Broccoli's SQLite database
- Uses fuzzy matching for text fields (title, description, servings, preparationTime, ingredients, directions)
- Must have exactly `before_count + 3` total rows (no extras, no missing)
- Binary: 1.0 if all pass, 0.0 otherwise

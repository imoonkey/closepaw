# RecipeAddMultipleRecipesFromMarkor2 — R19 Analysis

## Result: 0.0 (24 turns)

## Behavior Summary
The agent correctly identified the 3 recipes with "10 mins" prep time from Markor's recipes.txt (Beef Stir Fry, Pan-Seared Salmon with Quinoa, Raspberry Almond Smoothie), stored them in scratchpad, switched to Broccoli, and filled all form fields for each recipe across turns 8-22. All three save clicks reported success. However, the agent filled the "Source" field with `recipes.txt` on every recipe, while the evaluator expected `source=''` (empty). The evaluator confirmed: "Expected row Recipe(title='Raspberry Almond Smoothie', ... source='', ...) not found in the 'after' state."

## Root Cause
**Category: Reasoning (field mapping)**

The agent invented a value for the Source field (`recipes.txt`) that was not present in the Markor data. The pipe-delimited file has columns: title|description|servings|preparationTime|ingredients|directions — there is no "source" column. The Broccoli form has a Source field, and instead of leaving it empty (matching the absence of source data in the file), the agent fabricated a plausible-sounding value. This single field mismatch caused the evaluator's exact-match row comparison to fail, resulting in 0.0 for the entire task.

## Key Observations
- Recipe identification was correct: all 3 recipes with "10 mins" prep time were found
- Recipe data was faithfully transcribed from the file for title, description, servings, time, ingredients, and directions
- The "Categories" field was also filled with `Imported` (fabricated), but this is not checked by the evaluator
- The evaluator performs exact row matching against the Broccoli SQLite database — any extra non-empty field that should be empty causes a mismatch
- The file had duplicate recipe names with different data (e.g., two Pan-Seared Salmon entries with different prep times); the agent correctly used the 10-min version
- Only one WARNING was logged by the evaluator (Raspberry Almond Smoothie), suggesting either only one was checked or all-or-nothing scoring was used after the first mismatch

## Recommendation
Update the Broccoli app skill to instruct the agent: **leave the Source field empty unless the source data is explicitly provided in the input**. Specifically, when copying structured data from another app, only fill fields that have a direct column mapping in the source data. Do not fabricate values for unmapped fields. The Categories field should also be left empty for the same reason, though it did not affect scoring here.

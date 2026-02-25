# RecipeAddSingleRecipe — Cog-Tune Analysis

**Result**: SUCCESS (scripted_score=1.0)
**Turns**: 17 | **Tool failures**: 0

## Task

Open the Broccoli recipe app and add a full recipe with name, servings, prep/cook time, nutrition, ingredients, and instructions.

## Turn-by-Turn Analysis

| Turn | Action | Detail |
|------|--------|--------|
| 1 | open_app("Broccoli") | Opens recipe app |
| 2 | click "New Recipe" (idx 33) | Start new recipe |
| 3 | click recipe name field (idx 12) | Focus name field |
| 4 | type "Spaghetti Carbonara" | Enter recipe name |
| 5 | type "4" | Enter servings |
| 6 | type "15" | Enter prep time |
| 7 | type "15" | Enter cook time |
| 8 | type "500" | Enter calories |
| 9 | type "20" | Enter protein |
| 10 | type "60" | Enter carbs |
| 11 | type "200g spaghetti..." | Enter ingredient 1 |
| 12 | type "100g pancetta..." | Enter ingredient 2 |
| 13 | type "2 large eggs..." | Enter ingredient 3 |
| 14 | type "Cook spaghetti..." | Enter instruction 1 |
| 15 | type "Fry pancetta..." | Enter instruction 2 |
| 16 | type "Mix eggs..." | Enter instruction 3 |
| 17 | complete_task("success") | Task completed |

## Assessment

**Category**: Clean execution

**Execution quality**: Good — comprehensive data entry with 14 fields filled in 17 turns.

**Strengths**:
- Methodical filling of all recipe fields (name, nutrition, ingredients, instructions)
- No wasted turns despite complex multi-section form
- One field per turn maintains clarity

**Inefficiencies**: None — 17 turns for a complex multi-section form is efficient.

**Recommendations**: None.

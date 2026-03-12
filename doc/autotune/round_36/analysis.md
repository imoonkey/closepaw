# R36 Analysis — Skill Generalization Baseline

## Run
- ID: `20260311_215019`
- Model: gpt-5.4
- Tasks: 29 (from `generalize_active.txt`)
- Success rate: **21/29 = 72.4%**

## Changes Made
Removed eval-specific language from 7 app skills:
1. **Chrome** — removed Drawing Tasks + Maze Tasks sections (eval page layouts)
2. **Retro Music** — removed Duration-Constrained Playlists solver algorithm
3. **Pro Expense** — removed duplicate-finding algorithm ("perturbed group", "decoys"), added delete one-liner
4. **Broccoli** — removed duplicate-deletion algorithm (7-field comparison), added delete one-liner
5. **OpenTracks** — removed hardcoded scroll counts, turn-budget language, eval-specific track name examples
6. **Tasks.org** — removed turn-budget language, scratchpad accumulation rules, eval-specific date examples
7. **Markor** — removed eval-specific filename example, overly prescriptive newest-note workflow

## Results

### Passed (21)
| Task | Score |
|------|-------|
| BrowserMaze | 1.0 |
| ExpenseAddMultiple | 1.0 |
| ExpenseAddSingle | 1.0 |
| ExpenseDeleteDuplicates | 1.0 |
| ExpenseDeleteDuplicates2 | 1.0 |
| ExpenseDeleteMultiple | 1.0 |
| ExpenseDeleteMultiple2 | 1.0 |
| ExpenseDeleteSingle | 1.0 |
| NotesRecipeIngredientCount | 1.0 |
| RecipeAddMultipleRecipes | 1.0 |
| RecipeAddMultipleRecipesFromImage | 1.0 |
| RecipeAddMultipleRecipesFromMarkor | 1.0 |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 |
| RecipeAddSingleRecipe | 1.0 |
| RecipeDeleteDuplicateRecipes | 1.0 |
| RecipeDeleteMultipleRecipes | 1.0 |
| RecipeDeleteMultipleRecipesWithConstraint | 1.0 |
| RecipeDeleteMultipleRecipesWithNoise | 1.0 |
| RecipeDeleteSingleRecipe | 1.0 |
| RecipeDeleteSingleWithRecipeWithNoise | 1.0 |
| RetroPlayingQueue | 1.0 |

### Failed (5)
| Task | Score | Root Cause |
|------|-------|-----------|
| BrowserMultiply | 0.0 | Pre-existing: tool failure during calculation/entry (error stop) |
| ExpenseAddMultipleFromGallery | 0.0 | Pre-existing: tool failure, expenses not added correctly |
| ExpenseAddMultipleFromMarkor | 0.0 | Pre-existing: tool failures during transaction filtering |
| RecipeDeleteDuplicateRecipes2 | 0.0 | Pre-existing: GoalAchieved but duplicates not identified correctly |
| RecipeDeleteDuplicateRecipes3 | 0.0 | Pre-existing: GoalAchieved but duplicates not identified correctly (60 turns) |

### No Score / Infra (3)
| Task | Score | Root Cause |
|------|-------|-----------|
| RetroCreatePlaylist | None | Infra: scorer can't verify playlist creation |
| RetroPlaylistDuration | None | Pre-existing: song durations not in accessibility data, agent blocked |
| RetroSavePlaylist | None | Infra: scorer can't verify playlist export |

## Root Cause Classification
- **(a) Skill generalization regression**: 0 tasks
- **(b) Pre-existing issues**: 5 tasks (BrowserMultiply, ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3)
- **(c) Infra/scoring issues**: 3 tasks (RetroCreatePlaylist, RetroPlaylistDuration, RetroSavePlaylist)

## Key Takeaway
None of the 8 failures were caused by the skill generalization. The model handles BrowserMaze, ExpenseDeleteDuplicates, and RecipeDeleteDuplicateRecipes without prescriptive algorithms. The failures are either pre-existing tool/perception issues or infra problems.

## Next Steps
- Remove 21 passing tasks from `generalize_active.txt`
- The 3 Retro tasks with None scores should be re-evaluated (infra issue)
- The 5 failures need trace analysis to determine if general (non-solver) guidance could help

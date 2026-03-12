# Round 36 Eval Analysis — Common Problems

**Run ID**: `20260311_215019` | **Date**: 2026-03-11 | **Model**: gpt-5.4
**Tasks**: 29 evaluated | **Pass Rate**: 21/29 = 72.4%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| BrowserMaze | 1.0 | 18 | Success |
| BrowserMultiply | 0.0 | 29 | ActionFailure |
| ExpenseAddMultiple | 1.0 | 30 | Success |
| ExpenseAddMultipleFromGallery | 0.0 | 27 | ActionFailure |
| ExpenseAddMultipleFromMarkor | 0.0 | 19 | ActionFailure |
| ExpenseAddSingle | 1.0 | 13 | Success |
| ExpenseDeleteDuplicates | 1.0 | 14 | Success |
| ExpenseDeleteDuplicates2 | 1.0 | 12 | Success |
| ExpenseDeleteMultiple | 1.0 | 17 | Success |
| ExpenseDeleteMultiple2 | 1.0 | 15 | Success |
| ExpenseDeleteSingle | 1.0 | 5 | Success |
| NotesRecipeIngredientCount | 1.0 | 5 | Success |
| RecipeAddMultipleRecipes | 1.0 | 20 | Success |
| RecipeAddMultipleRecipesFromImage | 1.0 | 30 | Success |
| RecipeAddMultipleRecipesFromMarkor | 1.0 | 22 | Success |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | 30 | Success |
| RecipeAddSingleRecipe | 1.0 | 9 | Success |
| RecipeDeleteDuplicateRecipes | 1.0 | 11 | Success |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 22 | FalseCompletion |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 60 | FalseCompletion |
| RecipeDeleteMultipleRecipes | 1.0 | 18 | Success |
| RecipeDeleteMultipleRecipesWithConstraint | 1.0 | 30 | Success |
| RecipeDeleteMultipleRecipesWithNoise | 1.0 | 30 | Success |
| RecipeDeleteSingleRecipe | 1.0 | 7 | Success |
| RecipeDeleteSingleWithRecipeWithNoise | 1.0 | 9 | Success |
| RetroCreatePlaylist | None | 23 | ScorerBug |
| RetroPlayingQueue | 1.0 | 16 | Success |
| RetroPlaylistDuration | None | 9 | PerceptionGap |
| RetroSavePlaylist | None | 30 | ScorerBug |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 21 | — |
| ActionFailure | 3 | BrowserMultiply, ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor |
| FalseCompletion | 2 | RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3 |
| ScorerBug | 2 | RetroCreatePlaylist, RetroSavePlaylist |
| PerceptionGap | 1 | RetroPlaylistDuration |

## Previous Round Comparison

This is the first round of the skill generalization loop. Comparing against pre-generalization state:

| Change Applied | Status | Impact |
|---------------|--------|--------|
| Removed Chrome Drawing/Maze sections | No regression | BrowserMaze still passes (1.0) |
| Removed Retro Music duration algorithm | Expected regression | RetroPlaylistDuration fails (was already parked pre-R36) |
| Removed Expense duplicate algorithm | No regression | ExpenseDeleteDuplicates/2 both pass |
| Removed Broccoli duplicate algorithm | Partial regression | RecipeDeleteDuplicateRecipes passes; RecipeDeleteDuplicateRecipes2/3 fail |
| Removed OpenTracks scroll counts/turn budget | N/A | No OpenTracks tasks in this run |
| Removed Tasks.org turn budget/scratchpad rules | N/A | No Tasks.org tasks in this run |
| Removed Markor eval-specific examples | N/A | No Markor tasks in this run |

## Common Problems

### P1: Over-Deletion of Visually-Identical Noise Rows — MED — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3

**Root Cause**: Without the prescriptive 7-field comparison algorithm, the agent compares recipes by title+description visible in the list view only. The eval setup fills noise with same-title-same-description entries that differ only in hidden DB fields (servings, ingredients, directions). Agent cannot distinguish target duplicates from noise rows and over-deletes: task 2 deleted 4 (needed 1), task 3 deleted 11 (needed 1). Scorer's `validate_rows_removal_integrity` fails because noise rows are missing.

**Proposed Fix**:
- Added general one-liner to Broccoli skill: "Recipes with the same title may differ in other fields — open each to compare all fields." [app skill: com.flauschcode.broccoli]
- NOT adding prescriptive deletion algorithm back — that's eval-specific.

---

### P2: Form Field Mismatch — MED — 2 tasks

**Affected**: ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor

**Root Cause**: When filling Pro Expense forms, text sometimes routes to the wrong field (e.g., note text enters name field). Agent recovers the target field but doesn't re-verify the corrupted field before saving. Multi-app workflow (Gallery/Markor → Expense) adds complexity.

**Proposed Fix**:
- Added general one-liner to Expense skill: "Before tapping Save, re-read the name and amount fields to confirm they match intended values." [app skill: com.arduia.expense]

---

### P3: Retro Music Scorer SQL Bug — LOW — 2 tasks

**Affected**: RetroCreatePlaylist, RetroSavePlaylist

**Root Cause**: Android World scorer throws SQL syntax error when querying Retro Music playlist database. Agent actually completed tasks successfully. Not an agent issue.

**Proposed Fix**:
- None (infrastructure issue in the eval framework, not the agent)

---

### P4: Song Duration Inaccessible — LOW — 1 task

**Affected**: RetroPlaylistDuration

**Root Cause**: Song durations are not exposed in the accessibility tree or any UI element the agent can read. The removed "Duration-Constrained Playlists" section prescribed an add-all-then-prune workaround. Without it, the agent correctly identifies the limitation and gives up.

**Proposed Fix**:
- None — restoring the algorithm would be eval-specific. Accepted regression.

## Next Steps

### Must Fix
(None — this was a generalization round, not a fix round)

### Should Fix
1. **Form field verification**: Already applied — Expense skill one-liner about re-reading fields before Save.
   - Targets: ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor
   - Implementation: app skill: com.arduia.expense
   - Owner layer: app skill

2. **Recipe field comparison**: Already applied — Broccoli skill one-liner about comparing all fields.
   - Targets: RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3
   - Implementation: app skill: com.flauschcode.broccoli
   - Owner layer: app skill

### Accept as Capability Gap
- **RetroPlaylistDuration**: Song durations not accessible. Accepted regression from generalization.
- **RetroCreatePlaylist / RetroSavePlaylist**: Scorer SQL bug. Not agent issue.

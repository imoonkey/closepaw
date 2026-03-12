# Generalization Issues

Tasks that regressed during skill generalization and cannot be fixed with general (non-task-specific) guidance.

## Accepted Regressions (3 rounds failed each)

### RecipeDeleteDuplicateRecipes2 / RecipeDeleteDuplicateRecipes3
- **Failed rounds**: R36, R37, R38 (3 rounds)
- **Root cause**: Agent identifies duplicates by title+description from list view but loses count during deletion of high-multiplicity groups (5+ copies). List reindexing after each delete causes visual confusion.
- **Why not fixable with general guidance**: The "compare all fields" one-liner is already in the skill. The count-tracking failure is generic agent reasoning, not app-specific. Adding prescriptive deletion algorithms would be eval-specific.
- **Status**: ACCEPTED REGRESSION.

### ExpenseAddMultipleFromMarkor
- **Failed rounds**: R36, R37, R38 (3 rounds)
- **Root cause**: Agent typed note text into name field (element_index mismatch), recovered note field but forgot to re-type name. Field-verification guidance added in R37 but didn't help.
- **Why not fixable with general guidance**: The field-index mismatch is an action execution bug, not a knowledge gap. The general "re-verify fields" guidance is already in the skill. Further guidance would be task-specific.
- **Status**: ACCEPTED REGRESSION.

### RetroPlaylistDuration
- **Failed rounds**: R36, R37 (2 rounds, both Error + scorer bug)
- **Root cause**: Agent cannot find song durations via accessibility tree. The removed "Duration-Constrained Playlists" section prescribed an add-all-then-prune algorithm. Without it, the agent is stuck.
- **Why not fixable with general guidance**: Teaching the agent a specific pruning algorithm for duration constraints is eval-specific behavior.
- **Status**: ACCEPTED REGRESSION.

## Infra / Scoring Issues (not agent failures)

### RetroCreatePlaylist
- **Failed rounds**: R36 (None), R37 (None)
- **Root cause**: SQL syntax error in the Android World scorer. Agent actually succeeded.
- **Status**: Scorer bug, not agent issue.

### RetroSavePlaylist
- **Failed rounds**: R36 (None), R37 (None/Error)
- **Root cause**: Scorer SQL bug + possible export permission issue.
- **Status**: Scorer bug + possible action issue.

## Flaky (Resolved in R37)

### BrowserMultiply
- R36: FAIL, R37: PASS.

### ExpenseAddMultipleFromGallery
- R36: FAIL, R37: PASS.

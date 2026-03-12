# Generalization Issues

Tasks that regressed during skill generalization and cannot be fixed with general (non-task-specific) guidance.

## Accepted Regressions

### RecipeDeleteDuplicateRecipes2 / RecipeDeleteDuplicateRecipes3
- **Failed rounds**: R36, R37 (2 rounds)
- **Root cause**: Agent identifies duplicates by title+description from list view but loses count during deletion of high-multiplicity groups (5+ copies). List reindexing after each delete causes visual confusion.
- **Why not fixable with general guidance**: The "compare all fields" one-liner is already in the skill. The count-tracking failure is generic agent reasoning, not app-specific. Adding prescriptive deletion algorithms would be eval-specific.
- **Status**: Accepted regression. 3rd failure would confirm.

### RetroPlaylistDuration
- **Failed rounds**: R36, R37 (2 rounds, both Error + scorer bug)
- **Root cause**: Agent cannot find song durations via accessibility tree. The removed "Duration-Constrained Playlists" section prescribed an add-all-then-prune algorithm. Without it, the agent is stuck.
- **Why not fixable with general guidance**: Teaching the agent a specific pruning algorithm for duration constraints is eval-specific behavior. A real user would likely select songs they know, not optimize from a library.
- **Status**: Accepted regression.

## Infra / Scoring Issues

### RetroCreatePlaylist
- **Failed rounds**: R36 (None), R37 (None)
- **Root cause**: SQL syntax error in the Android World scorer when querying Retro Music playlist database. Agent actually succeeded.
- **Status**: Scorer bug, not agent issue.

### RetroSavePlaylist
- **Failed rounds**: R36 (None), R37 (None/Error)
- **Root cause**: "Save as file" export may not have completed (permissions or path issue). Also affected by scorer SQL bug.
- **Status**: Scorer bug + possible export permission issue.

## Flaky (Resolved)

### BrowserMultiply
- R36: FAIL, R37: PASS — was flaky.

### ExpenseAddMultipleFromGallery
- R36: FAIL, R37: PASS — was flaky.

## Still Active

### ExpenseAddMultipleFromMarkor
- **Failed rounds**: R36, R37 (2 rounds)
- **Root cause**: Agent typed note text into name field (element_index mismatch), recovered note field but forgot to re-type name. Added general guidance about re-verifying fields before save.
- **Status**: R38 will be 3rd attempt with new guidance.

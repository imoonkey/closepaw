# Unpark Triage — R22+ Loop

## Priority Tier 1: Has passed before (try first)

| Task | Park Reason | Ever Passed | Hypothesis | Suggested First Fix |
|------|-------------|-------------|-----------|---------------------|
| BrowserDraw | Canvas pixel-level drawing beyond a11y | Yes (1/3) | Prior park cited infra pollution (Chrome first-run, developer settings pointer_location/show_touches). Not a fundamental capability gap — passed once with hybrid perception. | Ensure emulator developer settings are clean; verify hybrid perception_mode is set; add Browser app skill for drawing page navigation |
| BrowserMaze | Canvas pixel-level maze navigation | Yes (1/3) | Same infra pollution as BrowserDraw. Passed once with hybrid perception. Maze navigation via screenshot is feasible if agent reaches the page. | Same as BrowserDraw — clean env + hybrid perception |
| SportsTrackerTotalDistanceForCategoryOverInterval | Agent ignores scroll guidance, misses kayaking tracks below fold | Yes (1/3) | Passed once in R38. Recent failures: agent never scrolls. Skill already has scroll guidance but agent ignores it. | Strengthen OpenTracks skill scroll instruction to CRITICAL; consider adding explicit "you MUST scroll to see all tracks" |
| MarkorDeleteNewestNote | Shell can't access files, UI shows modification time not creation time | Yes (1/3) | Passed once in R34. Recent issue: a11y tree timestamp perception gap — swapped vs screenshot. With gpt-5.4 + better perception, might resolve. | Add Markor skill guidance: "newest note = most recently modified in list; sort by modified date descending, first item is newest" |

## Priority Tier 2: Never passed, but plausibly fixable

| Task | Park Reason | Ever Passed | Hypothesis | Suggested First Fix |
|------|-------------|-------------|-----------|---------------------|
| RecipeAddMultipleRecipesFromImage | Image OCR from thumbnails unreliable | No (0/3) | R7 got 2/3 recipes done in 50 turns — TurnExhaustion, not capability gap. gpt-5.4 is faster than qwen. Image OCR improved with hybrid mode. | Increase max_turns to 60; ensure hybrid perception_mode; add Broccoli skill guidance for efficient multi-recipe-from-image workflow |
| RecipeDeleteDuplicateRecipes3 | 10+ duplicates to delete, TurnExhaustion at 50 turns | No (0/3) | Turn budget issue, not capability. Agent wasted 18% of turns on write_todos. With efficient workflow, 50 turns may suffice. | Increase max_turns to 60; add Broccoli skill guidance: "for bulk deletion, use long-press multi-select, not one-by-one" |
| RetroPlaylistDuration | 7 consecutive failures, TurnExhaustion, only 2/45 songs added | No (0/3) | Agent pre-reads individual song durations instead of batch-add-and-check. All failures were with qwen3.5. gpt-5.4 untested. | Keep max_turns=45; add RetroMusic app skill: "add songs iteratively to playlist, then check total duration — do NOT pre-read each song" |
| AudioRecorderRecordAudioWithFileName | Scoped storage — UI rename ≠ filesystem rename | No (0/3) | Evaluator checks filesystem path. If app uses scoped storage, UI rename may not propagate. But maybe the app DOES rename the file and we just need the right workflow. | Read task definition carefully; test if recorder app actually renames filesystem file; add AudioRecorder skill with exact rename workflow |
| ExpenseAddMultipleFromGallery | 6 expenses × 5 turns each = 30 = full budget | No (0/3) | Math problem: 30 turns for 6 expenses leaves zero margin. But with gpt-5.4 efficiency, maybe 3-4 turns per expense is achievable. | Increase max_turns to 50; add Pro Expense skill for efficient gallery-to-expense workflow; ensure hybrid perception for image reading |

## Priority Tier 3: Never passed, structurally hard

| Task | Park Reason | Ever Passed | Hypothesis | Suggested First Fix |
|------|-------------|-------------|-----------|---------------------|
| ExpenseDeleteDuplicates2 | DB-level duplicates indistinguishable via UI | No (0/3) | Visually identical entries differ in hidden DB fields. Agent correctly refused to delete. Might need different strategy: delete ALL matching entries then re-add the one to keep? | Investigate task definition — what exactly counts as "duplicate"? Try delete-all-then-readd or count-based strategy |
| RecipeDeleteDuplicateRecipes2 | Same-description noise trap, over-deleted 4 vs 1 | No (0/3) | Entries differ only in hidden fields (source_url, servings). Agent can't distinguish which is the true duplicate. Similar to ExpenseDeleteDuplicates2. | Investigate task definition — are there ANY visible differences? Check if detail view shows different fields |
| OsmAndMarker | Agent misbound final action (clicked Share instead of Marker) | No (0/3) | Action binding error, not perception gap. Agent DID navigate to correct location. Just needs correct button targeting. | Add OsmAnd app skill with explicit marker creation flow: "long-press on map → Add marker (NOT Share)" |
| OsmAndTrack | UI doesn't expose locality waypoints reliably | No (0/3) | Agent tried multiple strategies but OsmAnd UI degraded to country-level. Maybe need different approach: import GPX via shell or use different navigation method. | Investigate if shell can write GPX file to OsmAnd import directory; add OsmAnd skill with track creation best practice |

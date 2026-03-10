# Unpark Triage — R22+ Loop

Treat the notes below as reference, the hypothesis and suggested fixes may not be accurate or complete. Do your own research/analysis from first principle thinking.

## Priority Tier 1: Has passed before (try first)

| Task | Park Reason | Ever Passed | Hypothesis | Suggested First Fix |
|------|-------------|-------------|-----------|---------------------|
| BrowserDraw | Canvas pixel-level drawing beyond a11y | Yes (1/3) | Prior park cited infra pollution (Chrome first-run, developer settings pointer_location/show_touches). Not a fundamental capability gap — passed once with hybrid perception. | Ensure emulator developer settings are clean; verify hybrid perception_mode is set; add Browser app skill for drawing page navigation |
| ~~BrowserMaze~~ | ~~Canvas pixel-level maze navigation~~ | ~~Yes (1/3)~~ | **FIXED R22** — Chrome skill + hybrid perception worked. Passed 27 turns. | ~~N/A~~ |
| SportsTrackerTotalDistanceForCategoryOverInterval | Agent ignores scroll guidance, misses kayaking tracks below fold | Yes (1/3) | Passed once in R38. Recent failures: agent never scrolls. Skill already has scroll guidance but agent ignores it. | Strengthen OpenTracks skill scroll instruction to CRITICAL; consider adding explicit "you MUST scroll to see all tracks" |
| MarkorDeleteNewestNote | Shell can't access files, UI shows modification time not creation time | Yes (1/3) | Passed once in R34. Recent issue: a11y tree timestamp perception gap — swapped vs screenshot. With gpt-5.4 + better perception, might resolve. | Add Markor skill guidance: "newest note = most recently modified in list; sort by modified date descending, first item is newest" |

### R22 Attempt Log

**BrowserDraw (0.0)**: Created Chrome skill + hybrid perception. Agent spent all 30 turns in Files app — kept hitting hamburger/nav button (x<150 danger zone) instead of task.html row. Never reached Chrome. **Root cause**: Files app left-edge click issue. Fix needed: add Files app skill warning about hamburger zone; or use element_index targeting instead of coordinates.

**BrowserMaze (1.0)**: PASSED. Chrome skill + hybrid perception. Agent opened Files → task.html → Chrome, navigated maze using direction buttons with screenshot feedback in 27 turns.

**MarkorDeleteNewestNote (0.0)**: Agent correctly sorted by Date as instructed, but deleted wrong note. Markor Date sort = modification time. For this task, one older note was edited after creation, giving it a later modification time than the actually-newest note. The skill's "modification time == creation time for fresh files" assumption was wrong — some pre-existing notes have been modified. **Root cause**: Fundamental mismatch between modification time and creation time. Shell stat/ls don't work. Need alternative approach.

**SportsTracker (0.0)**: Agent scrolled DOWN but never UP. Missed kayaking tracks above initial scroll position. Found only 2 kayaking tracks (6518m), but likely missed tracks at top of list. **Root cause**: Unidirectional scrolling. Fix: skill must say "scroll to TOP first, then scroll down through entire list."

### R23 Attempt Log

**BrowserDraw (0.0)**: Files nav FIXED (no hamburger issue this time — Files skill helped). Agent reached Chrome and opened task.html. However, **Android Agent UI overlay covered the canvas and target colors** at the bottom of the screen. Agent couldn't see what colors to draw. **Root cause**: Agent UI overlay occlusion. This is an infra/config issue, not a skill issue. May need to auto-minimize agent overlay during hybrid perception tasks.

**MarkorDeleteNewestNote (0.0)**: **Critical discovery**: Markor Date sort is ASCENDING (oldest first), NOT descending. Two notes visible after sort: `fine_jelly_final.md` (15:34, first/top) and `final_friendly_king.md` (15:17, second). Agent deleted the first item (15:34) thinking it was newest. But first = oldest in ascending sort! Agent should have deleted the LAST item. **Fix**: Skill must tell agent to enable "Reverse order" or pick the LAST item after Date sort.

**SportsTracker (0.0)**: Agent attempted scroll-up (per updated skill) but scroll failed with "no observable effect." Then scrolled down and found 0 kayaking tracks for Oct 9-10. Answered 0, eval said wrong. **Root cause**: Either (a) kayaking tracks exist above initial view and scroll-up truly failed, or (b) agent misidentified activity types. The scroll infrastructure may not support upward scrolling in OpenTracks.

**RecipeDeleteDuplicateRecipes3 (0.0)**: Used 53/60 turns, false completion. Agent navigated through 30+ recipes but either deleted the wrong one or missed the true duplicate. Need to investigate trace for exact failure.

### R24 Attempt Log

**BrowserDraw (0.0)**: Agent successfully opened Files → task.html → Chrome (Files skill worked!). Drew on canvas and submitted. But wrong colors — agent couldn't accurately identify which palette colors matched the 3 target colors. **Root cause**: Color perception limitation in hybrid mode. gpt-5.4 vision can't reliably match colors from screenshot palette to targets.

**MarkorDeleteNewestNote (0.0)**: **RE-PARKED after 3 consecutive failures.** R22: wrong sort assumption. R23: ascending vs descending confusion. R24: timestamps not visible for one note, agent still picked wrong one. **Park reason**: Agent cannot reliably determine "newest" note. Markor's UI shows modification time but the evaluator uses `ls -ll -au` change_time (access time). These may diverge. Shell access from agent process fails for Markor's storage directory. No remaining fix ideas without code changes.

**OsmAndMarker (0.0)**: Agent found Planken, Liechtenstein on map. But the "Marker" button was NOT present in the accessibility tree. Agent tried element_index 15 which was out of bounds (only 0-14 existed). The marker action may not be accessible via a11y. **Root cause**: OsmAnd's marker action is not exposed in a11y tree from the location detail sheet. Need to investigate if there's an alternative path (e.g., context menu, long-press).

**RetroPlaylistDuration (0.0)**: Agent created playlist "Reggae Rhythms 51" but hit Error at turn 24. Couldn't complete song selection within the turn budget. Need more turns and better strategy guidance.

### R25 Attempt Log

**AudioRecorderRecordAudioWithFileName (0.0)**: Agent correctly recorded, stopped, and renamed via UI (9 turns). File appeared as "presentation_fGwr" in the recording list. But evaluator checks filesystem at `/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records/`. **UI rename only changes display name, not filesystem filename.** Agent process cannot access another app's private `Android/data/` directory. **RE-PARKED**: Structural limitation — no way to rename filesystem file without root or code changes.

**ExpenseDeleteDuplicates2 (0.0)**: Agent scanned ~30 of 42 expenses, found no exact duplicates. Task has 2 identical entries + 3 perturbations (same name, different amounts) among 40 noise rows. Agent stopped scrolling too early — missed the actual duplicate pair. **Root cause**: Insufficient scrolling. Skill needs stronger emphasis on scrolling the ENTIRE list (42+ items), and explicit warning that there will be same-name items with different amounts (perturbations) alongside the true identical pair.

**RetroPlaylistDuration (0.0)**: Agent couldn't read song durations from accessibility tree (not exposed inline). Found one song = 00:49 via details dialog. Gave up at turn 19 saying durations don't support a 45-50 min playlist. **Root cause**: (1) Durations not in a11y tree, (2) checking each song individually is turn-expensive, (3) agent misunderstood that short test songs can still combine to target duration. Need hybrid perception to read durations from screenshot, or a strategy to batch-add songs and check total.

**SportsTrackerTotalDistanceForCategoryOverInterval (1.0)**: **PASSED!** 45 turns used. The simplified downward-only scroll guidance (5-6 scrolls) worked — agent found all kayaking tracks and computed correct total distance (6502m).

**Re-parked**: BrowserDraw (3 consecutive failures R22-R24: Files nav fixed R23+, but color perception limitation in hybrid mode prevents correct drawing), AudioRecorderRecordAudioWithFileName (UI rename ≠ filesystem rename, structural limitation).

### R26 Attempt Log

**ExpenseDeleteDuplicates2 (0.0)**: Agent found 41 of 42 items (improved from 30 in R25). Still no exact duplicate pair found. The missing 42nd entry is likely one half of the duplicate pair. The agent may be losing items during scroll transitions. **Root cause**: Scroll coverage still insufficient by 1 item. May need approach change: use "All" expenses view instead of "Recent", or systematically compare amounts after full scan.

**OsmAndMarker (0.0)**: Infra failure on first attempt (score=None), retried and scored 0.0 at 17 turns. Agent claimed success but evaluator disagreed. Need trace investigation.

**RecipeDeleteDuplicateRecipes3 (0.0)**: Used 59 of 60 turns, claimed success but evaluator scored 0.0. Near-miss on turn budget. The agent is spending too many turns navigating and not enough on efficient deletion.

**RetroPlaylistDuration (0.0)**: **RE-PARKED after 3 consecutive failures (R24, R25, R26).** Agent gave up at turn 8 out of 50 — couldn't read durations from accessibility tree OR screenshot. Hybrid perception didn't help. **Park reason**: Song durations not accessible via any available perception mode. Agent can't plan duration-constrained playlist without knowing individual song lengths.

### R27 Attempt Log

**ExpenseDeleteDuplicates2 (0.0)**: Bad guidance — told agent to sort by NAME in Filter, but Pro Expense Filter only supports date sorting. Agent wasted all 15 turns trying to find nonexistent name sort option. **Root cause**: Incorrect skill guidance. Not a fair 3rd failure — corrected skill for R28.

**OsmAndMarker (0.0)**: **RE-PARKED after 3 consecutive failures (R24, R26, R27).** Infra failure on first attempt. Retry: agent searched for "Planken, Liechtenstein" but OsmAnd opened blank/wrong view, couldn't find location or create marker. **Park reason**: OsmAnd search doesn't reliably resolve small localities; Marker button not accessible in a11y tree.

**RecipeDeleteDuplicateRecipes2 (0.0)**: 1st attempt. Agent identified "Caprese" recipes appearing multiple times, started comparing them but ran out of turns at 30. Needed more turns to open each variation and compare fields. **Fix**: Increase max_turns to 50 for this task.

**RecipeDeleteDuplicateRecipes3 (0.0)**: **RE-PARKED after 3 consecutive failures (R23, R26, R27).** 49 of 60 turns used, false success. Agent navigated 30+ recipes but couldn't efficiently identify and delete the correct duplicate. **Park reason**: Turn budget structurally insufficient (complexity 3.4, 30+ recipes to scan and compare).

### R28 Attempt Log

**ExpenseAddMultipleFromGallery (0.0)**: 1st attempt. Agent used 30 turns (max) and hit Error. Hybrid perception active. Need to investigate what went wrong.

**ExpenseDeleteDuplicates2 (0.0)**: **RE-PARKED after 3 real failures (R25, R26, R28).** Agent found 41/42 items consistently but misses the duplicate's partner during scroll transitions. The scroll mechanism can't guarantee 100% coverage. R27 excluded (unfair test: bad name-sort guidance). **Park reason**: Scroll gap prevents finding all 42 items; 1 missing item is always the duplicate.

**RecipeAddMultipleRecipesFromImage (0.0)**: 1st attempt. Agent used 30 turns (max). Need more turns + investigation.

**RecipeDeleteDuplicateRecipes2 (1.0)**: **PASSED!** 41 turns out of 50. Updated Broccoli skill guided agent to open each same-titled recipe, compare ALL visible fields, and identify the true duplicate pair.

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

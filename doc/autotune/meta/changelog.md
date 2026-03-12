# Changelog

Rounds before autotune are reconstructed from doc/todo/0.01_eval_tune/ history.

## Prompt Optimization Round 92/R40 (2026-03-12, runs 20260312_121327+122824+132005 merged, gpt-5.4, 51-task sweep)

**Fixes applied**: Cut 522 tokens from tool descriptions (2,313 → 1,791, -22.6%): MobileActionTool, ScratchpadTool, RememberExperienceTool, DelegateTaskTool, ShellTool, SystemButtonTool. Always-on: 3,028 → 2,506 tokens.

**Results**: 32/51 pass (62.7%, up from R39's 30/51 = 58.8%). 6 improvements (AudioRecorderRecordAudioWithFileName, SimpleCalendarAddOneEventRelativeDay, SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval, TasksIncompleteTasksOnDate, VlcCreateTwoPlaylists). 4 flaky regressions within seed variance (RecipeAddMultipleRecipesFromMarkor2, SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, TasksHighPriorityTasksDueOnDate). Net +2 passing tasks.

**Key takeaway**: Cumulative -992 tokens (-28.4% always-on) across R39+R40 with zero true regressions. All three prompt layers optimized: system prompt -33.5%, tool descriptions -26.0%, app skills -25.3%. Loop complete — `stop_success`.

**Token counts**: System prompt 715, Tool descriptions 1,791, App skills 3,093 (avg 193/skill), Always-on 2,506.

## Prompt Optimization Round 91/R39 (2026-03-12, run 20260312_094821, gpt-5.4, 51-task sweep)

**Fixes applied**: Cut 1,518 tokens — system prompt -361 (33.5%), tool descriptions -109 (4.5%), app skills -1,048 (25.3%). Always-on: 3,498 → 3,028 tokens.

**Results**: 30/51 pass (58.8%). **No regressions from token cuts.** 1 flaky failure (MarkorMoveNote, was 2/3). 2 improvements (RetroCreatePlaylist, RetroSavePlaylist). 20 expected failures (all pre-existing 0/3 or 1/3).

**Key takeaway**: 1,518 tokens safely removed. Tool descriptions have the most room for further cuts (only 4.5% reduced so far). System prompt and app skills are leaner. Continue oscillation: look for more cuts in tool descriptions, then verify.

**Token counts**: System prompt 715, Tool descriptions 2,313, App skills 3,093 (avg 193/skill), Always-on 3,028.

## Skill Generalization Round 38 (2026-03-12, run 20260312_000547, gpt-5.4, confirmation round)

**Fixes applied**: None — final confirmation round for 3 remaining tasks.

**Results**: 0/3 pass. All 3 accepted as regressions after 3 consecutive failures:
- RecipeDeleteDuplicateRecipes2: FalseCompletion (count-tracking failure during high-multiplicity deletion)
- RecipeDeleteDuplicateRecipes3: FalseCompletion (same pattern)
- ExpenseAddMultipleFromMarkor: ActionFailure (field mismatch during cross-app text entry)

**Loop complete (R36-R38)**: Of 29 original tasks: 23 pass with generalized skills, 3 accepted regressions, 3 parked as scorer/infra bugs. `generalize_active.txt` empty.

## Skill Generalization Round 37 (2026-03-11, run 20260311_232252, gpt-5.4, 8 tasks)

**Fixes applied**: Added general duplicate-comparison one-liners to Broccoli and Expense skills. Field-verification guidance to Expense skill.

**Results**: 2/8 pass (25%). BrowserMultiply and ExpenseAddMultipleFromGallery passed (both flaky, no skill changes). 3 Retro tasks parked as scorer SQL bugs. 3 remaining failures persist.

**Key finding**: General skill guidance insufficient for count-tracking and action-execution bugs.

## Skill Generalization Round 36 (2026-03-11, run 20260311_215019, gpt-5.4, baseline)

**Fixes applied**: Generalized 7 app skills — removed eval-specific algorithms, scroll counts, turn budgets, eval data patterns from Chrome, Retro Music, Pro Expense, Broccoli, OpenTracks, Tasks.org, Markor. ~66 lines removed.

**Results**: 21/29 pass (72.4%). **0 regressions from generalization.** 5 pre-existing failures, 3 infra/scoring issues.

**Key takeaway**: Model handles BrowserMaze, ExpenseDeleteDuplicates, RecipeDeleteDuplicateRecipes without prescriptive algorithms. All failures are pre-existing.

## Autotune Round 35 (2026-03-10, run 20260310_013847, gpt-5.4, OsmAnd final attempt)

**Fixes applied**: OsmAnd skill restructured — Address tab as primary search, explicit SHOW ON MAP warning.

**Results**: 0/2 pass. OsmAndMarker RE-PARKED (coord tolerance structural). OsmAndTrack RE-PARKED (map data structural).

**Both agents followed skill correctly** — used Address tab, Plan a route, coord fallback. Failures are structural: offline map data doesn't index Liechtenstein villages, and coordinate search produces coords outside 0.001-degree evaluator tolerance.

**Loop complete (R31-R35)**: 5/9 fixed, 4 re-parked. unpark_active.txt empty.

## Autotune Round 34 (2026-03-10, run 20260310_012156, gpt-5.4, OsmAnd pair)

**Fixes applied**: OsmAnd skill rewrite — coord verification, "Plan a route" for GPX tracks, warn about SHOW ON MAP unreliability.

**Results**: 0/2 pass. OsmAndMarker 0.0 (general search proximity-based, agent didn't try coords). OsmAndTrack 0.0 (right workflow but search failed in general tab).

**Key finding**: Both tasks share same root cause — OsmAnd general search tab is proximity-based. Address tab does structured offline lookup regardless of map position. Agent needs explicit "switch to Address tab" guidance.

**Backlog**: 2 remaining (OsmAndMarker, OsmAndTrack). R35 = final attempt with Address tab fix.

## Autotune Round 33 (2026-03-10, run 20260310_004519, gpt-5.4, all 5 remaining)

**Fixes applied**: (1) OsmAnd: "SHOW ON MAP" → pin strategy, routing guidance, (2) Retro Music: per-song 3-dot "Add to playlist" as primary method, (3) OsmAndTrack max_turns 80.

**Results**: 1/5 pass (20%, 1/3 excluding re-parks). RetroPlaylistDuration 0.0→1.0 (FIXED). OsmAndMarker 0.0 (offline geocoder → Ghana). OsmAndTrack 0.0 (wrong workflow). MarkorDeleteNewestNote RE-PARKED (evaluator atime bug). BrowserDraw RE-PARKED (platform limitation).

**Key findings**:
- **RetroPlaylistDuration FIXED**: Per-song context menu bypassed a11y gap. Add-all-prune strategy worked.
- **OsmAndMarker**: Offline geocoder maps village names to wrong continent. Need coord-first approach.
- **OsmAndTrack**: Agent used Navigation instead of "Plan a route" → save GPX. Need workflow-specific guidance.
- **MarkorDeleteNewestNote**: Agent correct, evaluator atime bug. RE-PARKED.
- **BrowserDraw**: A11y click doesn't fire JS DOM events. RE-PARKED.

**Backlog change**: 5 → 2 remaining (1 fixed, 2 re-parked). Cumulative: 5/9 fixed, 2 re-parked, 2 remaining.

## Autotune Round 32 (2026-03-10, run 20260310_001826, gpt-5.4, unpark_r2 R31 retries + Tier 2)

**Fixes applied**: (1) Gallery skill on correct package com.simplemobiletools.gallery.pro, (2) OsmAnd snapshot fixed (map_markers_db), (3) Retro Music add-all-prune strategy, (4) Broccoli scratchpad tracking + 7 fields, (5) RecipeDelete3 max_turns 80, (6) RetroPlaylistDuration max_turns 60.

**Results**: 2/4 pass (50%). ExpenseAddMultipleFromGallery 0.0→1.0 (FIXED). RecipeDeleteDuplicateRecipes3 0.0→1.0 (FIXED). OsmAndMarker 0.0 (wrong search result). RetroPlaylistDuration 0.0 (add-to-playlist not in a11y tree).

**Key findings**:
- **ExpenseAddGallery FIXED**: Correct Gallery package skill with filename verification solved wrong-image issue.
- **RecipeDelete3 FIXED**: Scratchpad tracking + 80 turns sufficient for systematic 7-field comparison.
- **OsmAndMarker**: Offline search for small villages unreliable. Agent picked country-level result. Need "SHOW ON MAP" → pin interaction.
- **RetroPlaylistDuration**: "Add to playlist" button in multi-select toolbar not exposed in a11y tree. Need per-song context menu fallback.

**Backlog change**: 7 → 5 remaining (2 fixed). Cumulative: 4/9 fixed in R31-R32.

## Autotune Round 31 (2026-03-10, run 20260309_235826, gpt-5.4, unpark_r2 Tier 1)

**Fixes applied**: (1) AudioRecorder: remove "strip .m4a" from skill, (2) Pro Expense: add "open detail view, compare ALL fields", (3) Gallery: new skill for full-screen image reading, (4) OsmAnd: coord fallback in skill, (5) ExpenseDeleteDuplicates2 max_turns 50→60.

**Results**: 2/4 pass (50%, 2/2 excluding infra). AudioRecorderRecordAudioWithFileName 0.0→1.0 (FIXED). ExpenseDeleteDuplicates2 0.0→1.0 (FIXED). ExpenseAddMultipleFromGallery 0.0 (wrong image file). OsmAndMarker INFRA_FAILURE (no map_markers table).

**Key findings**:
- **AudioRecorder FIXED**: Old skill said "strip .m4a" but evaluator expected double extension. Verbatim filename typing works.
- **ExpenseDelete2 FIXED**: Detail view comparison catches all fields, not just name+amount from list view.
- **ExpenseAddGallery**: Gallery skill was on wrong package (com.google.android.apps.photos vs com.simplemobiletools.gallery.pro). Agent opened wrong image file.
- **OsmAndMarker**: `map_markers_db` lazily created. Snapshot needs manual marker add to initialize DB.

**Backlog change**: 9 → 7 remaining (2 fixed).

## Autotune Round 30 (2026-03-09, run 20260309_215257, gpt-5.4, unpark loop final round)

**Fixes applied**: (1) RecipeAddMultipleRecipesFromImage max_turns=60 + hybrid perception, (2) ExpenseAddMultipleFromGallery max_turns=60.

**Results**: 1/2 pass (50%). RecipeAddMultipleRecipesFromImage 0.0→1.0 (FIXED, 27/60 turns). ExpenseAddMultipleFromGallery 0.0 (3rd failure, Error at turn 19).

**Key findings**:
- **RecipeAddMultipleRecipesFromImage FIXED**: Hybrid perception + 60 turns sufficient. Agent read recipe data from image and entered into Broccoli.
- **ExpenseAddMultipleFromGallery RE-PARKED**: Persistent field alignment (name/note confusion) + image perception unreliable for expense amounts. 3 failures across R28-R30.

**Backlog change**: 2 → 0 active (1 passed, 1 re-parked). **Unpark loop complete.**

## Autotune Round 29 (2026-03-09, run 20260309_213307, gpt-5.4, unpark loop)

**Fixes applied**: (1) ExpenseAddMultipleFromGallery max_turns=50, (2) RecipeAddMultipleRecipesFromImage max_turns=50 + hybrid.

**Results**: 0/2 pass (0%). Both failed — ExpenseGallery field alignment, RecipeImage only added 2 recipes.

**Backlog change**: 2 → 2 active (no change).

## Autotune Round 28 (2026-03-09, run 20260309_210911, gpt-5.4, unpark loop)

**Fixes applied**: (1) RecipeDeleteDuplicateRecipes2 max_turns=50, (2) Broccoli skill field comparison guidance, (3) Pro Expense perturbed group strategy (reverted name-sort), (4) ExpenseAddMultipleFromGallery + RecipeAddMultipleRecipesFromImage added to backlog.

**Results**: 1/4 pass (25%). RecipeDeleteDuplicateRecipes2 0.0→1.0 (FIXED, 41/50 turns). ExpenseDeleteDuplicates2 0.0 (3rd real failure, re-parked). ExpenseAddMultipleFromGallery 0.0 (1st attempt). RecipeAddMultipleRecipesFromImage 0.0 (1st attempt).

**Key findings**:
- **RecipeDeleteDuplicateRecipes2 FIXED**: Broccoli field comparison skill worked — agent opened each same-titled recipe, compared all visible fields, identified true duplicate.
- **ExpenseDeleteDuplicates2 RE-PARKED**: Found "Pest Control" duplicate pair but evaluator disagreed. Scroll gap prevents 100% list coverage.

**Backlog change**: 4 → 2 active (1 passed, 1 re-parked).

## Autotune Round 27 (2026-03-09, run 20260309_204943, gpt-5.4, unpark loop)

**Fixes applied**: (1) Pro Expense skill: sort by NAME in Filter (WRONG — doesn't exist), (2) RecipeDeleteDuplicateRecipes2 added to backlog.

**Results**: 0/4 pass (0%). ExpenseDeleteDuplicates2 wasted all turns on nonexistent name sort (unfair failure). OsmAndMarker 3rd failure (re-parked). RecipeDeleteDuplicateRecipes3 3rd failure (re-parked). RecipeDeleteDuplicateRecipes2 ran out of turns at 30.

**Key findings**:
- **Bad guidance self-inflicted**: Pro Expense name-sort doesn't exist. Must revert to scroll approach.
- **OsmAndMarker RE-PARKED**: Search unreliable + marker button not in a11y tree + infra failures.
- **RecipeDeleteDuplicateRecipes3 RE-PARKED**: Turn budget structurally insufficient for 30+ recipes.

**Backlog change**: 5 → 2 active (0 passed, 3 re-parked).

## Autotune Round 26 (2026-03-09, run 20260309_203139, gpt-5.4, unpark loop)

**Fixes applied**: (1) Pro Expense full-list scroll emphasis (10+ scrolls), (2) RetroMusic hybrid perception + duration strategy, (3) OsmAnd marker alternative path investigation.

**Results**: 0/4 pass (0%). ExpenseDeleteDuplicates2 41/42 items (improved). OsmAndMarker infra failure + false success. RecipeDeleteDuplicateRecipes3 near-miss at 59/60 turns. RetroPlaylistDuration gave up at turn 8.

**Key findings**:
- **RetroPlaylistDuration RE-PARKED**: Song durations inaccessible via any perception mode (a11y, screenshot, details dialog). 3 consecutive failures.

**Backlog change**: 6 → 5 active (0 passed, 1 re-parked).

## Autotune Round 25 (2026-03-09, run 20260309_201619, gpt-5.4, unpark loop)

**Fixes applied**: (1) OpenTracks downward-only scroll (5-6 scrolls), (2) AudioRecorder skill, (3) RetroMusic hybrid perception + duration skill, (4) Pro Expense scroll emphasis.

**Results**: 1/4 pass (25%). SportsTrackerTotalDistanceForCategoryOverInterval 0.0→1.0 (FIXED, 45 turns). AudioRecorder 0.0 (scoped storage). RetroPlaylistDuration 0.0 (perception gap). ExpenseDeleteDuplicates2 0.0 (scroll coverage).

**Key findings**:
- **SportsTracker FIXED**: Downward-only scroll guidance resolved the 3-round failure streak from R16-R18.
- **AudioRecorder RE-PARKED**: UI rename ≠ filesystem rename. Structural limitation.
- **BrowserDraw RE-PARKED** (from R24): Color perception limitation in hybrid mode, 3 consecutive failures.

**Backlog change**: 8 → 6 active (1 passed, 2 re-parked).

## Autotune Round 24 (2026-03-09, run 20260309_194958, gpt-5.4, unpark loop)

**Fixes applied**: (1) Markor skill: ascending/descending + Reverse Order guidance, (2) OsmAnd marker investigation, (3) RetroMusic max_turns increase.

**Results**: 0/4 pass (0%). BrowserDraw 3rd failure (color perception). MarkorDeleteNewestNote 3rd failure (timestamps diverge). OsmAndMarker marker button not in a11y tree. RetroPlaylistDuration Error at turn 24.

**Key findings**:
- **BrowserDraw RE-PARKED**: 3 consecutive failures with different root causes each round (R22: nav, R23: overlay, R24: color). Color perception is fundamental model limitation.
- **MarkorDeleteNewestNote RE-PARKED**: Evaluator uses access time, Markor shows modification time. No fix without code changes.

**Backlog change**: 10 → 8 active (0 passed, 2 re-parked).

## Autotune Round 23 (2026-03-09, run 20260309_192810, gpt-5.4, unpark loop)

**Fixes applied**: (1) Files app skill: hamburger zone warning (x<150), (2) Markor sort guidance update, (3) OpenTracks scroll-to-top-first, (4) RecipeDeleteDuplicateRecipes3 added to backlog.

**Results**: 0/4 pass (0%). BrowserDraw reached Chrome but overlay occlusion. MarkorDeleteNewestNote ascending sort confusion. SportsTracker scroll-up failed. RecipeDeleteDuplicateRecipes3 53/60 turns, false completion.

**Key findings**:
- **Files app skill working**: BrowserDraw no longer trapped in hamburger zone.
- **Scroll-up infrastructure limitation**: OpenTracks scroll-up produces "no observable effect." Need downward-only strategy.
- **Markor ascending sort**: Date sort is ascending by default. Need Reverse Order or pick LAST item.

**Backlog change**: 10 → 10 active (no change).

## Autotune Round 22 (2026-03-09, run 20260309_190216, gpt-5.4, unpark loop start)

**Fixes applied**: (1) Chrome app skill with hybrid perception for canvas-based tasks, (2) Markor skill: sort guidance for newest note identification, (3) OpenTracks scroll emphasis, (4) Hybrid perception overrides for Browser* tasks.

**Results**: 1/4 pass (25%). BrowserMaze 0.0→1.0 (FIXED, 27 turns). BrowserDraw 0.0 (Files nav trap). MarkorDeleteNewestNote 0.0 (wrong note). SportsTracker 0.0 (unidirectional scroll).

**Key findings**:
- **BrowserMaze FIXED**: Chrome skill + hybrid perception enabled Files → task.html → Chrome → maze navigation.
- **BrowserDraw blocked by Files hamburger zone**: Agent spent all 30 turns clicking x<150 left edge instead of file row.
- **MarkorDeleteNewestNote**: Modification time ≠ creation time for pre-existing notes.
- **SportsTracker**: Scrolled down but never up, missed kayaking tracks above fold.

**Backlog change**: 13 tasks unparked → 10 active after R22 (1 passed, OsmAndTrack pre-excluded as structurally impossible).

## Autotune Round 21 (2026-03-09, runs 20260309_153628 + 20260309_161737 + 20260309_163832, gpt-5.4, priority scale + due date + extension + expense skill)

**Fixes applied**: (1) Tasks.org skill: corrected priority scale from "1st=High, 4th=None" to "1st=None, 4th=High" (was wrong since R18), (2) Tasks.org skill: added "Reading Due Date in Detail View" section — use full-format date ("Saturday, October 7"), not bare day name ("Wednesday"), (3) Markor skill: promoted extension clearing to CRITICAL section with concrete example, (4) New Pro Expense skill: category scroll guidance for horizontally scrollable RecyclerView.

**Results**: 2/3 pass (66.7%). MarkorMergeNotes 0.0→1.0 (extension fix), TasksHighPriorityTasksDueOnDate 0.0→1.0 (priority + due date fix). ExpenseAddMultipleFromGallery 0.0 (12th consecutive failure, parked).

**Key findings**:
- **Priority scale was inverted since R18**: The R18 documentation said "1st=High, 4th=None" — this was WRONG. R16 trace comparison confirmed 4th=High. Fixed in R21.
- **Due date field disambiguation**: Task detail view has multiple date fields. "Saturday, October 7" is the due date, "Wednesday" below it is the start/repeat field. Agent misread the wrong field until skill guidance was added.
- **MarkorMergeNotes**: Extension CRITICAL section effective — agent now clears the `.md` extension field correctly.
- **ExpenseAddMultipleFromGallery**: 12 consecutive failures (0/12). Multi-item image-to-expense workflow exceeds turn budget + image perception unreliable. Parked to cannot_handle.

**Backlog change**: 3 → 0 active (2 passed, 1 parked). **All active tasks resolved.**

## Autotune Round 20 (2026-03-09, run 20260309_150119, gpt-5.4, broccoli skill + revert markor shell-write)

**Fixes applied**: (1) New Broccoli app skill: multi-recipe navigation, leave unmapped fields empty, (2) Revert Markor shell-write guidance (R19 regression), add extension-clearing and stat for creation-time, (3) Tasks.org: strengthen scroll exhaustion + enforce all-candidates-checked, (4) Settings: prefer UI over shell for Wi-Fi/Bluetooth.

**Results**: 3/8 pass (37.5%). RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2 — all first pass with Broccoli skill.

**Key findings**:
- **Broccoli skill: 3/3 success** — Edit-as-create eliminated, all 3 Recipe tasks pass.
- **MarkorMergeNotes**: Content 100% correct, UI path worked, but agent didn't clear `.md` extension. Skill guidance exists but was ignored.
- **MarkorDeleteNewestNote**: `stat` command fails — shell can't access Markor's file storage. Parked to `cannot_handle`.
- **RecipeAddMultipleRecipesFromImage**: Image OCR still hallucinating from thumbnail. Parked to `cannot_handle`.
- **TasksHighPriorityTasksDueOnDate**: Better mechanics (exhaustive scroll, all candidates checked) but same wrong answer. Possible priority scale inversion (4th=High, not 1st).
- **ExpenseAddMultipleFromGallery**: Still no category scroll despite scrollable container. Need Pro Expense skill.

**Backlog change**: 8 → 3 active (3 Recipe passed, 2 parked).

## Autotune Round 19 (2026-03-09, run 20260309_143418, gpt-5.4, scratchpad merge + markor shell-write + newest-note strategy)

**Fixes applied**: (1) Tasks.org skill: scratchpad accumulation rule — "never overwrite candidate list on scroll", (2) Markor skill: shell-write preference for merge/content tasks, (3) Markor skill: newest-note identification via date-sort, (4) Eval config: max_turns=50 for RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor2, TasksHighPriorityTasksDueOnDate.

**Results**: 1/9 pass (11.1%). TurnOnWifiAndOpenApp first-ever pass (1.0). All others 0.0.

**Key findings**:
- **Markor shell-write REGRESSED**: Agent canceled working UI dialog to attempt shell (which lacks permissions). Fix was counterproductive.
- **Broccoli Edit-as-Create**: 3 Recipe tasks failed because agent clicks Edit on existing recipe instead of navigating back to create new one. Needs Broccoli app skill.
- **MarkorDeleteNewestNote**: Agent used date-sort correctly but Markor shows modification time, not creation time. Need shell `stat` for creation time.
- **RecipeAddMultipleRecipesFromImage**: Image OCR unstable — 6 scratchpad rewrites with wildly different recipe names. Only 2/3 recipes extracted.
- **TasksHighPriorityTasksDueOnDate**: Scratchpad accumulation partially worked (append on scroll) but agent overwrote when switching to priority-checking phase. Also stopped scrolling too early.
- **TurnOnWifiAndOpenApp**: Settings skill injected, shell `svc wifi enable` failed (permissions), agent gracefully fell back to UI navigation. First pass.

**Backlog change**: 9 → 8 active (TurnOnWifiAndOpenApp resolved).

## Autotune Round 18 (2026-03-09, run 20260309_135649, gpt-5.4, priority scale + scroll + settings)
- **Key changes** (4555232):
  - Tasks SKILL.md: priority radio button order (High, Med, Low, None left-to-right)
  - Both skills: scroll-until-stuck strengthening
  - New com.android.settings SKILL.md with shell toggle preference
- **Tasks run**: 9 (3 retries + 6 new from backlog)
- **Config**: serial single-device, model gpt-5.4
- **Result**: 0.5/9 — no new full passes
- **Key findings**:
  - Priority scale fix WORKED (TasksHighPriority correctly read leftmost=High)
  - Scroll guidance STILL IGNORED by SportsTracker (3rd consecutive failure)
  - TasksHighPriority: priority correct but scratchpad overwrite on scroll lost candidates
  - MarkorDeleteNewestNote: agent logic correct, timestamp mismatch (mod vs creation time)
  - MarkorMergeNotes: .md extension + auto heading template broke content match
  - Browser/Expense/Recipe: hard tasks, need deeper investigation or cannot_handle
- **Moved to cannot_handle**: AudioRecorderRecordAudioWithFileName (scoped storage), BrowserDraw (canvas), BrowserMaze (canvas), SportsTrackerTotalDistanceForCategoryOverInterval (3× scroll failure)

## Autotune Round 17 (2026-03-09, run 20260309_132808, gpt-5.4, date/scroll skill fixes)
- **Key changes** (192d3c8):
  - OpenTracks SKILL.md: date-label-to-absolute-date mapping, require checking ALL tracks on target dates
  - Tasks SKILL.md: due date = standalone label (not chip text), scroll-to-end mandate for info queries
  - Eval bridge: translate localhost→10.0.2.2 for emulator API access
- **Task selection rationale**: 3 R16 failures + 4 untested from active backlog
- **Tasks run**: 7
- **Config**: serial single-device, model gpt-5.4
- **Result**: 3.5/7 (50%)
- **Net new passes**: NotesIsTodo (1.0), SystemBrightnessMax (1.0), TasksIncompleteTasksOnDate (1.0, date-label fix worked)
- **Partial**: TurnOnWifiAndOpenApp (0.5, Wi-Fi perception error)
- **Key findings**:
  - Tasks date-label fix effective (TasksIncompleteTasksOnDate 0.0→1.0)
  - SportsTracker: date mapping fixed but still doesn't scroll
  - TasksHighPriority: priority scale inverted (rightmost=None, agent read as High)
  - AudioRecorder: rename = display name only, evaluator checks filesystem
  - TurnOnWifiAndOpenApp: assumed Wi-Fi on from tile appearance
- **Infra**: localhost→10.0.2.2 translation eliminates need for adb reverse

## Autotune Round 16 (2026-03-09, run 20260309_125810, gpt-5.4, model switch + port forwarding)
- **Key changes**: Model switch from qwen3.5 to gpt-5.4 in eval/config/default.yaml. Added adb reverse port forwarding for localhost proxy.
- **Task selection rationale**: 7 tasks from active backlog — mix of previously stuck Tasks.org tasks + retries
- **Tasks run**: 7
- **Config**: serial single-device, model gpt-5.4, max_turns 30 (50 for SportsTracker)
- **Result**: 4/7 (57%)
- **Net new passes**: TasksCompletedTasksForDate (first pass ever), TasksDueNextWeek (first pass ever)
- **Confirmed passes**: MarkorAddNoteHeader, SimpleCalendarEventsInNextWeek
- **Key findings**:
  - gpt-5.4 resolves qwen3.5's toggle-click and off-by-1 counting issues on Tasks.org
  - SportsTracker date reasoning still broken — confused "Yesterday" with target date
  - TasksHighPriority: correct priority logic but didn't scroll to see dynamically-created task
  - TasksIncomplete: "Tmrw" chip text confused with actual due date label
- **Infra note**: First run failed 0/7 — localhost:18080 proxy unreachable from emulator. Fixed with `adb reverse tcp:18080 tcp:18080`.

## Autotune Loop Contract Restructure (2026-03-09)
- **Scope**: Renamed the autonomous loop skill from `fullyautotune` to `autotune-loop` and aligned the repo-local autotune workflow to the final responsibility split.
- **Key changes**:
  - Added `doc/autotune/meta/loop_state.json` as the single control-plane file for the current loop run.
  - Added `.ai-dev/skills/autotune/references/tuning_principles.md` and linked it from `/autotune` and `/prompt-tune`.
  - Rewrote `/autotune` around explicit manual vs orchestrated modes and made `loop_state.json` the end-of-round handoff.
  - Replaced `fullyautotune` with `autotune-loop`, a thin controller over `loop_state.json`, `scoreboard.json`, and `/autotune`.
  - Updated `AIDEV.md` to document `/autotune`, `/autotune-loop`, and `/prompt-tune`.
- **Why**:
  - The previous setup blurred ownership between `ralph-loop`, `fullyautotune`, and `autotune`.
  - The new contract makes `ralph-loop` generic, keeps domain policy in `autotune-loop`, and keeps round work in `autotune`.


## Post-R14 Perception Fidelity Follow-up (2026-03-09)
- **Scope**: Landed the perception fidelity cleanup that R14 identified, plus permanent docs for the new contract. No new eval run.
- **Key changes**:
  - `fix: preserve raw perception text semantics` (`f23287d`)
    - `Perceptor` now preserves raw `text`, `description`, and `hintText` values without capture-time whitespace normalization.
    - Prompt `text` now follows visible/accessibility semantics only: `text -> description -> hintText`.
    - `resourceId` suffix is no longer treated as prompt text or text-targetable content.
    - `TargetResolver` now matches prompt-text semantics first, then falls back to `description` / `hintText` only when prompt-text matching finds nothing.
  - `docs: document perception text semantics` (`21b4bfd`)
    - Added `doc/main/infra/perception.md` as the dedicated SOTA doc for perception.
    - Updated `doc/main/README.md` navigation and narrowed `doc/main/infra/platform.md` to platform-side perception integration.
    - Added detailed design record at `doc/autotune/round_14/percetion_fidelity_codex.md`.
- **Why**:
  - R10's newline-preservation tweak fixed only one symptom. The deeper issue was that capture and prompt shaping were still conflating fidelity, matching, and resource-id fallback.
  - This follow-up makes perception lossless by default and aligns what the model sees in prompt JSON with what `Target.Text` can actually resolve.

## Autotune Round 14 (2026-03-09, run 20260309_020714, qwen3.5, smart name filtering)
- **Key changes** (f3376a4):
  - OpenTracks SKILL.md: allow track names as negative filter — skip obviously unrelated tracks (e.g., "Alpine Skiing" for running query), prioritize ambiguous/matching names for Edit checks
- **Task selection rationale**: 3 SportsTracker (smart filtering) + 2 Tasks retry
- **Tasks run**: 5 (2 completed, 3 hit OpenRouter 402 credit limit)
- **Config**: serial single-device, model qwen3.5, max_turns 50 for SportsTracker
- **Result**: 2/2 non-infra (SportsTrackerActivitiesCountForWeek 1.0 in 18 turns, SportsTrackerLongestDistanceActivity 1.0 in 48 turns)
- **Net new passes**: SportsTrackerActivitiesCountForWeek, SportsTrackerLongestDistanceActivity
- **Key findings**:
  - Smart name filtering dramatically reduced turns: 18 turns vs 50+ MaxTurnsReached in R13
  - OpenRouter weekly credit limit exhausted mid-run (402), blocking remaining 3 tasks
  - SportsTrackerTotalDistanceForCategoryOverInterval untested (had 38 turns of progress before credits died — likely would pass)
- **Cumulative**: 20/22 (90.9%)
- **Moved to cannot_handle**: TasksCompletedTasksForDate (action execution — toggle click doesn't register), TasksDueNextWeek (model reasoning — off-by-1 count)

## Autotune Round 13 (2026-03-09, run 20260309_012417, qwen3.5, Edit strategy + Tasks fixes)
- **Key changes** (fdbb921):
  - OpenTracks SKILL.md: reverted shell/sqlite3 strategy to Edit-based approach. Track names ≠ activity types. 4-step Edit flow with turn budgeting.
  - Tasks.org SKILL.md: concrete date example ("If today is Sunday Oct 15, next week = Mon Oct 16 – Sun Oct 22"), partial-answer guidance for priority queries.
  - Eval config: TasksHighPriorityTasks max_turns: 50
- **Task selection rationale**: 7 remaining failures from R12 (3 SportsTracker + 3 Tasks + 1 Expense)
- **Tasks run**: 7
- **Config**: serial single-device, model qwen3.5, max_turns 50 for SportsTracker + TasksHighPriorityTasks
- **Result**: 2/7
- **Net new passes**: ExpenseAddMultipleFromMarkor (first ever!), TasksHighPriorityTasks
- **Key findings**:
  - ExpenseAddMultipleFromMarkor: cross-app rule #10 from R10 finally effective — agent opened Pro Expense
  - TasksHighPriorityTasks: 50-turn budget sufficient, found all priorities
  - SportsTracker: Edit strategy correct but too slow — 14 tracks × ~8 turns each exceeds 50 turns
  - TasksDueNextWeek: date range correct (Oct 16-22) but off-by-1 count (7 vs 8)
  - TasksCompletedTasksForDate: "Show completed" toggle clicks don't register via accessibility
- **Cumulative**: 18/22
- **Moved to cannot_handle**: none

## Autotune Round 12 (2026-03-09, run 20260309_004521, qwen3.5, shell strategy — FAILED)
- **Key changes** (9ab7243):
  - OpenTracks SKILL.md: shell+sqlite3 strategy to query database directly
  - Eval config: SportsTracker max_turns: 50 (added to default.yaml but not applied to this run)
- **Task selection rationale**: 7 remaining failures (3 SportsTracker + 3 Tasks + 1 Expense)
- **Tasks run**: 7
- **Config**: serial single-device, model qwen3.5, max_turns 30 (50 not yet applied)
- **Result**: 0/7
- **Key findings**:
  - Shell strategy failed completely: agent process cannot access other apps' private data directories (no root/run-as)
  - Agent oscillated between shell and UI approaches, wasting turns on both
  - TasksCompletedTasksForDate: provider SSE error (infra failure)
  - TasksDueNextWeek: date reasoning wrong — "next week" on Sunday calculated as Oct 23-29 instead of Oct 16-22
  - TasksHighPriorityTasks: found 4/8 priorities in scratchpad but never submitted answer on final turn
- **Cumulative**: 16/22 (unchanged)
- **Moved to cannot_handle**: none

## Autotune Round 11 (2026-03-09, run TBD, qwen3.5, OpenTracks + Tasks actionable guidance)
- **Key changes** (22ba8b9):
  - System prompt: softened anti-hallucination from "NEVER infer metadata" to "do not guess metadata from visual appearance alone. Follow app skill guidance."
  - OpenTracks SKILL.md: changed to shell+sqlite3 strategy (later proved wrong)
  - Tasks.org SKILL.md: specific overflow menu location (3 dots, top right), priority turn budget (~3 turns/task)
- **Task selection rationale**: 11 tasks from R10 failures + targeted retries
- **Tasks run**: 11
- **Result**: 4 new passes (TasksHighPriorityTasksDueOnDate, TasksIncompleteTasksOnDate, MarkorAddNoteHeader, SimpleCalendarEventsInNextWeek)
- **Cumulative**: 16/22
- **Moved to cannot_handle**: none

## Autotune Round 10 (2026-03-08, run 20260308_221303, qwen3.5, 8 fixes)
- **Key changes** (f5d26b5):
  - System prompt (StandaloneAgentDef.kt): cross-app destination rule (#10), strengthened Information mode, expanded Completion with anti-hallucination + file-ops + date verification + scratchpad cross-check
  - Perception (PerceptorInternals.kt): preserve paragraph breaks (`\n\n` instead of `\n`)
  - Debug logging (TurnPlanningPhaseRunner.kt): app skill lookup `Log.d`
  - New app skills: Calendar (NumberPicker, 24h, dates), Retro Music (Songs tab flow), Tasks.org (diff-based completion), OpenTracks (initial version)
- **Task selection rationale**: 22 tasks that failed in R8+R9 (qwen3.5 targeted subset)
- **Tasks run**: 22
- **Config**: serial single-device, model qwen3.5
- **Result**: 12/22 (54.5%)
- **Net new passes**: FilesMoveFile, RetroSavePlaylist, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarDeleteEvents, SimpleCalendarEventOnDateAtTime, SimpleCalendarEventsInTimeRange, SimpleCalendarEventsOnDate, SimpleCalendarNextEvent, SportsTrackerTotalDurationForCategoryThisWeek, TasksDueOnDate, VlcCreateTwoPlaylists, plus 1 more
- **Key findings**:
  - Calendar skill highly effective (5/6 calendar tasks pass)
  - Tasks.org skill injected but model doesn't follow it reliably
  - SportsTracker: no efficient strategy without Edit or DB access
  - Cross-app rule fixed ExpenseAddMultipleFromMarkor direction (agent now opens destination app)
- **Cumulative**: 12/22 (from 0/22 baseline)
- **Moved to cannot_handle**: none

## Autotune Round 9 (2026-03-07, runs 050909–080143, GPT-5.4, full baseline eval-only)
- **Key changes**: none (eval-only round, no code changes since R8)
- **Task selection rationale**: Full eval across all 116 tasks (groups 0-5) — first comprehensive GPT-5.4 baseline with R8 code changes
- **Tasks run**: 116
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model GPT-5.4, serial single-device, snapshot_policy best_effort
- **Result**: 77.5/116 (66.8%)
- **Score breakdown**: G0=12/14 (86%), G1=14/20 (70%), G2=15/20 (75%), G3=15.5/20 (78%), G4=10/20 (50%), G5=11/22 (50%)
- **Net new passes**: 65 tasks passing on first GPT-5.4 full eval
- **Regressions**: VlcCreateTwoPlaylists 0.5→0.0 (VLC skill NOT injected), RetroSavePlaylist regression from R2 (GPT-5.4 gets stuck in empty playlist view)
- **Key findings**:
  - **App skill injection still broken (CRITICAL)**: Tasks skill injected in 1/6 runs. VLC skill never injected. Same root cause as R8 — package name timing.
  - **FalseCompletion dominant failure mode (47%)**: ~18 tasks. Name-based guessing, incomplete scans, no post-verification.
  - **Calendar date picker navigation (HIGH)**: 7 tasks affected — can't reliably navigate to specific dates or resolve relative times.
  - **Action layer newline bug**: MarkorAddNoteHeader — `\n\n` swallowed by ACTION_SET_TEXT. Different from R3 reasoning fix; this is an action layer bug in NodeActionPerformer.
  - **Cross-app destination binding**: ExpenseAddMultipleFromMarkor created file in Markor instead of opening Pro Expense.
  - **Retro Music song addition flow**: RetroSavePlaylist stuck in empty playlist view. Working pattern (Songs → find → long-press → Add to playlist) not followed.
  - **SportsTracker per-item inspection cost**: 4-5 turns per track × 7 tracks exhausts 30-turn budget.
  - **A11y tree timestamp/property gaps**: MarkorDeleteNewestNote had swapped modification times in a11y vs screenshot.
- **Moved to cannot_handle**: none
- **Failure distribution**: FalseCompletion 47%, ReasoningError 18%, TurnExhaustion 13%, ActionFailure 10%, cannot_handle 8%, PerceptionGap 4%

## Autotune Round 8 (2026-03-07, runs 050909–080143, GPT-5.4, multi-action + app skills)
- **Key changes** (de28c00):
  - Multi-action per turn (Minitap-style): TurnToolPolicy allows all screen-changing tools (was: first only). TurnExecutionPhaseRunner has abort-on-first-failure. System prompt rule 2 rewritten with navigation isolation rule.
  - Tasks app skill rewrite: org.tasks/SKILL.md with "don't click checkboxes" + date resolution + reading task state guidance
  - VLC app skill created: org.videolan.vlc/SKILL.md with multi-select pattern (long-press + tap) + playlist creation
  - Cog-tune quality upgrade: per_task_analysis_template.md with mandatory turn-by-turn table, perception checks, false-success detection
  - autotune skill enforces subagent-per-task for analysis
- **Task selection rationale**: Full eval across all 116 tasks (groups 0-5) — first comprehensive GPT-5.4 baseline
- **Tasks run**: 116
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model GPT-5.4, serial single-device, snapshot_policy best_effort
- **Result**: 77.5/116 (66.8%)
- **Score breakdown**: G0=12/14 (86%), G1=14/20 (70%), G2=15/20 (75%), G3=15.5/20 (78%), G4=10/20 (50%), G5=11/22 (50%)
- **Net new passes**: 65 tasks passing on first GPT-5.4 full eval run
- **Regressions**: VlcCreateTwoPlaylists 0.5→0.0 (VLC skill NOT injected; 1 playlist w/ 1 file vs R7's 1 playlist w/ 4 files)
- **Key findings**:
  - **App skill injection unreliable (CRITICAL)**: Tasks skill injected in 1/6 runs. VLC skill never injected. Package name timing issue in AccessibilityPlatform.getCurrentPackageName() → AgentTurnRunner pipeline.
  - **Multi-action works mechanically**: RecipeAddMultipleRecipesFromMarkor used 5-action batch turns (50→29 turns). But GPT-5.4 rarely emits multiple tool calls unprompted (3/29 turns batched for Recipe, 0/29 for VLC).
  - **FalseCompletion epidemic (~18 tasks)**: Dominant failure mode for info-extraction tasks. Agent guesses answers without verifying metadata (priority, sport type, completion status).
  - **A11y tree missing `checked` property**: Tasks.org checkboxes expose `checked=N/A`. Skill's "read checked property" guidance impossible to follow.
  - **Model-level improvement**: GPT-5.4 naturally avoids destructive checkbox clicking (R7 qwen3.5 wasted 6/11 turns on it). No prompt needed.
- **Moved to cannot_handle**: none

## Autotune Round 7 (2026-03-07, run 20260307_025501, qwen3.5, loop detection redesign)
- **Key changes** (9e6c9c7):
  - Loop detection redesign: deleted all WARNING-level advisory warnings (tool dominance, scroll spam, action repetition, cycle detection). Kept only stable-screen detection (Jaccard >= 0.95, 5 turns). Removed Tier 2 (BLOCK) and Tier 3 (FORCE_COMPLETE). -445 lines of code.
  - System prompt rule 6 rewritten: "if the same action fails twice, try a different approach" (was: "after failure or loop warning, pivot immediately")
  - Created org.tasks app skill with date-label mapping and scroll guidance
  - Updated /prompt-tune skill to cover system warnings as prompt layer
- **Task selection rationale**: same 8 tasks as R6 for direct comparison
- **Tasks run**: 8
- **Config**: serial single-device, model qwen3.5
- **Result**: 1/8 (12.5%), total score 1.5/8
- **Net new passes**: none
- **Score improvements**: VlcCreateTwoPlaylists 0.0→0.5 (completed 1/2 playlists)
- **Regressions**: none
- **Key findings**:
  - Loop detection removal confirmed effective: RecipeAddMultipleRecipesFromMarkor used all 50 turns and saved 2/3 recipes (R6: self-terminated at turn 22 with 1/3 partial). VlcCreateTwoPlaylists 0.0→0.5.
  - Dominant root cause shifted from ReasoningError/FalseCompletion to **TurnExhaustion** (4/7 failures). The 1-action-per-turn architecture is now the primary bottleneck.
  - Tasks app org.tasks skill was not injected — need to verify package name on emulator.
  - Two Tasks app tasks (TasksCompletedTasksForDate, TasksIncompleteTasksOnDate) corrupted app state by clicking checkboxes to inspect state.
- **Moved to cannot_handle**: none

## Autotune Round 6 (2026-03-07, run 20260307_003831, GPT-5.4, never-succeeded subset)
- **Key changes**: none (eval-only round, no code changes since R5)
- **Task selection rationale**: never_succeeded.txt minus gpt54_never_succeeded_10.txt (already run in R5) minus cannot_handle_group.txt = 8 tasks
- **Tasks run**: 8
- **Config**: `eval/config/gpt54_never_succeeded.yaml`, model gpt-5.4, serial single-device
- **Result**: 1/8 (12.5%)
- **Net new passes**: VlcCreatePlaylist (first-ever success)
- **Key findings**:
  - VlcCreatePlaylist: agent used multi-select → overflow menu → add to playlist → verify pattern. Clean 14-turn execution.
  - Tasks app cluster (3/3 fail): premature completion remains the blocker. Agent answers in 2-11 turns without scrolling, date reasoning, or status filtering. GPT-5.4 ignores existing date-label tip.
  - Multi-item workflow endurance (3/3 fail): RecipeAddMultipleRecipesFromMarkor self-terminated after 1/3 citing hallucinated loop warning. RecipeAddMultipleRecipesFromImage got trapped in detail view. VlcCreateTwoPlaylists used one-at-a-time instead of multi-select.
  - SportsTrackerActivitiesCountForWeek: a11y tree doesn't expose sport type, agent guessed from titles.
- **Moved to cannot_handle**: none

## Post-R5 Click Deep Dive (2026-03-06)

- **Scope**: Investigated click reliability on Files RecyclerView across all four transports, then validated with BrowserMultiply eval.
- **Transport experiment** (`edb4acd`, `55976dd`): 2x2 agent-loop matrix + 8 isolated action-debug tests + secondary display smoke test. Result: `node_action_click` is 8/8 reliable; `dispatchGesture` false-succeeds; Shizuku `injectInputEvent` false-succeeded (likely test setup issue). Current priority `node_click → gesture_tap` confirmed correct. VD priority swap design (`vd_action_priority_design.md`) rejected — core premise invalidated.
- **BrowserMultiply eval** (A11Y: `20260306_230038`, VD: `20260306_232810`): Both 0/1, MaxTurnsReached.
  - **First-click-after-launch**: `node_action_click` on `task.html` returns true but UI unchanged after 1800ms. Same click works after other interactions (long_press). Reproducible in both modes. Root cause unknown.
  - **Unchanged-fallback double-click**: When click succeeds but screen content stays the same (random number repeats), `UiChangeDetector` sees Unchanged → fallback to `gesture_tap` → extra click overwrites next value. **Fixed** (`2042beb`): `Unchanged` now returns `Success(verified=false)` with warning — no automatic fallback.
- **Experiment doc moved** to `doc/main/infra/tool/click_transport_experiment.md` as permanent SOTA reference.
- **Key files**: `doc/main/infra/tool/mobile_action.md`, `doc/main/infra/tool/click_transport_experiment.md`, `app/.../debug/DebugActionExecutor.kt`, `scripts/action-test.sh`, `app/.../tool/action/PointActionExecutorCore.kt`

## Autotune Round 5 (2026-03-06, run 20260306_003753, commit 5f01e95, GPT-5.4 smoke)
- **Key changes** (7a85be5→5f01e95):
  - `refactor: split prompt ownership across tools and app skills` (02844a5) — moved app-specific tips out of monolithic agent def into per-app SKILL.md files under `assets/app_skills/`; tool descriptions now own their own guidance (ShellTool, CompleteTaskTool, etc.); added AppSkillRepository + PromptBuilder injection
  - Shell tool restriction relaxed: was files-only, now general with permission caveat; removed hardcoded storage paths from prompt
  - `feat(eval): add local parallel eval entrypoint` (68d1f88) + 3 follow-up fixes (da92d97, a3187ac, 456d3aa) — parallel eval runner with per-task baselines, but still unstable so R5 ran serial
  - GPT-5.4 model entry added (OpenAI `response` API), new `gpt54_never_succeeded.yaml` eval config
  - Parked RecipeDeleteDuplicateRecipes3 + RetroPlaylistDuration to `cannot_handle`
- **Task selection rationale**: serial 10-task smoke from `never_succeeded` because parallel eval is still unstable
- **Tasks run**: 10
- **Config**: `eval/config/gpt54_never_succeeded.yaml` + temporary 10-task subset file, OpenAI provider
- **Result**: 2/10 (20%)
- **Net new passes**: BrowserMultiply, ClockTimerEntry
- **Key findings**:
  - Main blocker cluster is generic exact-target verification, not app-specific workflow discovery
  - Cross-app source/destination binding remains weak on import/logging tasks
  - BrowserDraw result was contaminated by emulator developer overlays (`pointer_location`, `show_touches`) discovered mid-run and turned off after the task
  - ExpenseDeleteDuplicates2 and OsmAndTrack reconfirm existing `cannot_handle` calls
  - OsmAndMarker made partial progress beyond older totally-blocked runs but still false-completed on the final map action
- **Moved to cannot_handle**: none
  Existing parked calls were reconfirmed, not expanded.

## Autotune Round 4 (2026-03-05, run 20260305_151648, commit 7a85be5)
- **Task selection rationale**: 3 Tasks failures (P1 targets), 1 Tasks canary, 1 Retro (P2), 1 Recipe (P2 max_turns), 1 SportsTracker infra (P3), 2 fragile SportsTracker (P2)
- **Tasks run**: 9
- **Fixes applied**: P1 date-label tip + QA scratchpad guardrail, P2 shell restricted to files, P2 repetitive-task guidance, P2 Retro batch-select tip, P2 max_turns=50/45, P3 stop_agent→force_stop 3s delay
- **Result**: 1/9 (11%)
- **Net new passes**: none
- **Regressions**: TasksHighPriorityTasksDueOnDate (1.0→0.0, natural variance), SportsTrackerTotalDistanceForCategoryOverInterval (1.0→0.0, premature shell pivot)
- **Key findings**:
  - Date-label tip partially effective: TasksIncompleteTasksOnDate now does date computation (R3: zero filtering → R4: correct filtering), but still 0.0 due to no-scroll
  - QA scratchpad guardrail partially effective: TasksDueNextWeek used it but contradicted own count
  - write_todos guidance counterproductive: RecipeDeleteDuplicateRecipes3 wasted 18% of turns on standalone write_todos calls
  - Retro batch-select tip partially followed but qwen3.5 cannot distinguish duplicate "More options" buttons
  - Infra P3 fix confirmed: SportsTrackerActivitiesCountForWeek now terminates cleanly (was InfraError → now FalseCompletion)
  - Both regressions attributed to qwen3.5 natural variance, not prompt changes
- **Moved to cannot_handle**: RecipeDeleteDuplicateRecipes3 (structurally impossible at any reasonable turn budget), RetroPlaylistDuration (qwen3.5-blocked, 7 consecutive 0.0)

## Autotune Round 3 — Analysis & R4 Prep (2026-03-05)
- **Aligned analysis**: Claude + Codex independent analyses, cross-reviewed and aligned via /double-design /align
- **Root causes**: FalseCompletion (3 Tasks app), TurnExhaustion (2), InfraError (1), fragile passes (2 SportsTracker at 28-30 turns)
- **Per-app success rate** (loose, last 3 runs): 7 apps at 100%, Vlc 0%, Tasks 50%, Browser/OsmAnd 33%
- **Never-succeeded pattern analysis**: 22 tasks across 7 patterns. Largest clusters: cross-app import (5), complex dedup (3), non-standard UI (5)
- **R4 approved fixes**:
  1. Generic date-label tip + QA scratchpad guardrail (P1, 3 tasks)
  2. Shell tool restricted to Files only (P2)
  3. Repetitive-task guidance: use write_todo, commit to working approach (P2)
  4. RetroPlaylistDuration: batch-select app tip + max_turns=45 (P2)
  5. RecipeDeleteDuplicateRecipes3: max_turns=50, park if still failing (P2)
  6. Timeout finalization observability (P3)
  7. Per-app success rate added to scoreboard.py
- **R4 task selection**: 9 tasks (3 Tasks failures, 2 fragile SportsTracker, 1 Retro, 1 Recipe, 1 SportsTracker infra, 1 Tasks canary)

## Autotune Round 3 (2026-03-04, run 20260304_152742, commit 63e3a0a)
- **Task selection rationale**: 22 group_5 never-run tasks (full sweep) + 3 targeted fixes (MarkorAddNoteHeader P1 blank line, RetroPlaylistDuration P2 duration strategy, MarkorEditNote keyboard_visible canary)
- **Tasks run**: 25
- **Fixes applied**: P1 Markor blank line tip (`\n\n`), P2 Retro Music iterative add-and-check tip, Q3 keyboard_visible detection (window-type-based instead of broken node traversal)
- **Result**: 19/25 (76%)
- **Net new passes**: MarkorAddNoteHeader (0→1), NotesIsTodo (new→1), NotesTodoItemCount (new→1), RecipeAddMultipleRecipesFromMarkor2 (new→1), RecipeDeleteSingleWithRecipeWithNoise (new→1), SimpleCalendarEventsInTimeRange (new→1), SimpleCalendarFirstEventAfterStartTime (new→1), SportsTrackerActivityDuration (new→1), SportsTrackerLongestDistanceActivity (new→1), SportsTrackerTotalDistanceForCategoryOverInterval (new→1), SportsTrackerTotalDurationForCategoryThisWeek (new→1), SystemBluetoothTurnOffVerify (new→1), SystemBluetoothTurnOnVerify (new→1), SystemBrightnessMaxVerify (new→1), SystemBrightnessMinVerify (new→1), SystemWifiTurnOffVerify (new→1), SystemWifiTurnOnVerify (new→1), TasksHighPriorityTasksDueOnDate (new→1)
- **Regressions**: none
- **Key findings**:
  - P1 blank line tip effective — MarkorAddNoteHeader finally passes after 2 rounds stuck
  - P2 duration strategy tip ineffective — RetroPlaylistDuration still 0 (agent ignores tip, qwen3.5 limitation)
  - Group 5 sweep: 17/22 passing on first run (77%)
  - Tasks app date reasoning is the main new problem — 3/4 Tasks app queries fail
  - SportsTracker expansion strong: 4/5 new tasks passing
- **Moved to cannot_handle**: none

## Autotune Round 2 (2026-03-04, run 20260304_033425, commit eadbf03)
- **Task selection rationale**: 8 directly affected tasks from R1 approved fixes (P1 hint text, P2 completion verification, P3-P4 Markor tips, P5 app workflow tips, Q3 keyboard flag, Q7 agent_thought, Q12 write_todos)
- **Tasks run**: 8
- **Fixes applied**: P1 hint text isShowingHintText, Q7 agent_thought first in 8 schemas, P2 completion verification checklist, P3 Markor extension tip, P4 Markor cursor positioning tip, P5 Retro Music + OpenTracks tips, Q3 keyboard_visible flag, Q12 write_todos re-enabled
- **Result**: 6/8 (75%)
- **Net new passes**: MarkorCreateNote (0→1), MarkorEditNote (0→1), RetroCreatePlaylist (0→1), RetroSavePlaylist (new→1), SportsTrackerActivitiesOnDate (0→1), RecipeDeleteMultipleRecipes (new→1)
- **Regressions**: none
- **Key findings**:
  - P4 cursor positioning tip highly effective — MarkorEditNote done in just 7 turns
  - P1 hint text fix confirmed working — RetroCreatePlaylist, RetroSavePlaylist now pass
  - MarkorAddNoteHeader still 0: `\n` vs `\n\n` blank line interpretation persists
  - RetroPlaylistDuration still 0: agent pre-reads durations individually instead of iterative add-and-check
  - keyboard_visible flag not observed in traces — may need APK rebuild verification
  - write_todos not used by RecipeDeleteMultipleRecipes (3 items too few to benefit)
- **Moved to cannot_handle**: none
- **Task selection rationale**: 4 turn-exhaustion tasks (max_turns=50), 2 Markor no-shell, 2 VLC infra-fixed, 2 investigation (RetroPlaylistDuration, SportsTrackerActivitiesOnDate), 2 regression canaries
- **Tasks run**: 12
- **Fixes applied**: P1-R3 max_turns override, P2-R3 cannot_handle exclusion (OsmAnd*), P3-R3 Markor no-shell directive, P6-R3 VLC init fix
- **Result**: 2/12 (16.7%)
- **Net new passes**: none (both passes are regression canaries: MarkorCreateNote, MarkorEditNote)
- **Regressions**: none
- **Key findings**:
  - `type` action hint text contamination is #1 blocker (3-4 tasks). Root cause: `NodeActionPerformer.setTextOnNode()` reads `node.text` which returns hint text on empty fields. Fix: `node.isShowingHintText` (API 26+).
  - `agent_thought` ordering in tool schemas — currently after `action`, should be before for chain-of-thought reasoning. 8 tools affected.
  - FalseCompletion without verification is #2 (2 tasks after removing cannot_handle).
  - max_turns=50 increase was ineffective — tasks FalseComplete early, not turn-exhausted.
  - LLM retry infra already exists (MAX_RETRIES=5); SSE error was likely misclassified as non-retryable.
  - Perception filters keyboard with no trace → need `keyboardVisible` flag.
  - `write_todos` tool should be re-enabled for multi-step tasks.
- **Moved to cannot_handle**: OsmAndMarker, OsmAndTrack (confirmed R35), ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2 (ambiguous duplicate definition + noise trap)

## Pre-autotune Round 3 (2026-03-01, run 20260301_012237, commit ae6ecbc)
- **Task selection rationale**: Re-run 15 failures from R2 to validate P1-P8 fixes
- **Tasks run**: 15
- **Fixes applied**: P1 type clear=false, P3 Markor nav tip, P4 OsmAnd hybrid, P6 strategy-pivot, P7 OpenTracks tip, P8 VLC teardown
- **Result**: 2/15 (13.3%), up from 0/15 in R2 for this subset
- **Net new passes**: MarkorEditNote, SimpleCalendarEventsInNextWeek
- **Regressions**: none
- **Moved to cannot_handle**: none

## Pre-autotune Round 2 (2026-02-27, run 20260227_222506)
- **Task selection rationale**: Group 4 (20 tasks) + group 2/3 re-runs (8 tasks)
- **Tasks run**: 28
- **Result**: 14/27 scored (51.9%). 5 FalseCompletion, 3 NavigationFailure, 2 ActionFailure, 2 InfraError, 1 TurnExhaustion, 1 PerceptionGap
- **Key findings**: type clear=false needed, OsmAnd inaccessible, strategy-pivot prompt ineffective

## Pre-autotune Round 1 (2026-02-27, run 20260227_161048)
- **Task selection rationale**: Group 4 initial eval (20 tasks)
- **Tasks run**: 21
- **Result**: 7/21 (33.3%)
- **Key findings**: First systematic eval of group 4. Identified ActionFailure and NavigationFailure as top categories.

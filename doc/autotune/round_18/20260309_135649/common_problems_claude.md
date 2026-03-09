# Round 18 Eval Analysis — Common Problems

**Run ID**: `20260309_135649` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 9 evaluated | **Pass Rate**: 0.5/9 = 6%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| TurnOnWifiAndOpenApp | 0.5 | 7 | PerceptionError (Wi-Fi state) |
| MarkorDeleteNewestNote | 0.0 | 7 | GroundTruthMismatch (mod time vs creation time) |
| MarkorMergeNotes | 0.0 | 19 | AppBehavior (.md extension + auto heading) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 35 | NavigationError (no scroll, 3rd consecutive) |
| TasksHighPriorityTasksDueOnDate | 0.0 | 21 | ScratchpadOverwrite (priority scale fix worked!) |
| BrowserDraw | 0.0 | 29 | HardTask (canvas interaction) |
| BrowserMaze | 0.0 | 30 | HardTask (maze navigation) |
| ExpenseAddMultipleFromGallery | 0.0 | 23 | HardTask (gallery image extraction) |
| RecipeAddMultipleRecipes | 0.0 | 30 | TurnExhaustion (added 1/3 recipes) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| NavigationError | 1 | SportsTracker (3rd time) |
| ScratchpadOverwrite | 1 | TasksHighPriority |
| AppBehavior | 1 | MarkorMergeNotes |
| GroundTruthMismatch | 1 | MarkorDeleteNewestNote |
| PerceptionError | 1 | TurnOnWifiAndOpenApp |
| HardTask | 3 | BrowserDraw, BrowserMaze, ExpenseAddMultipleFromGallery |
| TurnExhaustion | 1 | RecipeAddMultipleRecipes |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Tasks priority scale (left=High) | **Effective** | Agent correctly read priority scale. "Renew Driver's License" = High (correct). |
| Scroll-until-stuck (both skills) | **Ineffective** | SportsTracker: zero scrolls. Tasks: scrolled but lost candidates. |
| Android Settings shell skill | Not triggered | TurnOnWifi opens quick settings (launcher fg), not Settings app |
| AudioRecorder shell test | Confirmed blocked | Scoped storage prevents cross-app access |

## Common Problems

### P1: SportsTracker scroll — 3 consecutive failures — HIGH

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval (R16, R17, R18 all 0.0)
**Root Cause**: Agent consistently ignores scroll guidance in skill. Treats visible viewport as complete list. The guidance "scroll down repeatedly" is present but not followed.
**Assessment**: After 3 rounds of identical failure despite targeted skill fixes, this task cluster is **exhausted for skill-only fixes**. The agent needs either a structural enforcement (mandatory scroll step before track inspection) or this should be escalated.

### P2: Scratchpad overwrite on scroll — HIGH — 1 task

**Affected**: TasksHighPriorityTasksDueOnDate
**Root Cause**: Agent overwrites candidate list after scrolling instead of merging. Duplicate task names at different scroll positions cause confusion.
**Proposed Fix**: Add "never overwrite full candidate list — only append or remove with explicit reasoning" to Tasks skill.

### P3: Markor file extension/heading — MED — 1 task

**Affected**: MarkorMergeNotes
**Root Cause**: Markor defaults new files to .md with auto-inserted `# filename` heading. Evaluator checks filesystem for exact content.
**Proposed Fix**: Markor skill: "When creating a new note for merge/content tasks, use shell to write the file directly to `/storage/emulated/0/Documents/Markor/` instead of Markor's new-file dialog. This avoids extension defaults and template headings."

### P4: MarkorDeleteNewest timestamp ambiguity — MED — 1 task

**Affected**: MarkorDeleteNewestNote
**Root Cause**: Agent correctly sorted by modification time and deleted the file with the latest timestamp. But evaluator may define "newest" as creation time. Both files were created at similar times (Oct 15, 15:17 and 15:34).
**Assessment**: This might be eval variance or a genuine creation-vs-modification time issue. Need to check if Markor shows creation time anywhere.

### P5: Cannot-handle candidates — LOW

**Affected**: BrowserDraw, BrowserMaze, ExpenseAddMultipleFromGallery
**Assessment**: Browser tasks require pixel-level canvas interaction (drawing shapes, navigating mazes) — beyond accessibility service capability even in hybrid mode. ExpenseAddMultipleFromGallery requires reading gallery images — needs hybrid perception mode.

## Next Steps

### Must Fix
1. **Scratchpad merge guidance**: Add "accumulate candidates across scroll positions, never overwrite" to Tasks skill
   - Targets: TasksHighPriorityTasksDueOnDate
   - Owner: app skill: org.tasks

2. **Markor shell-write for merge tasks**: Add guidance to create files via shell when exact content matters
   - Targets: MarkorMergeNotes
   - Owner: app skill: net.gsantner.markor

### Should Fix
3. **Markor newest-note strategy**: Add guidance to check file creation date or use a more reliable strategy
   - Targets: MarkorDeleteNewestNote
   - Owner: app skill: net.gsantner.markor

### Accept as Capability Gap (move to cannot_handle)
- **AudioRecorderRecordAudioWithFileName**: Filesystem rename impossible (scoped storage). Agent does correct UI rename but evaluator checks filesystem.
- **BrowserDraw**: Pixel-level canvas drawing beyond accessibility capability
- **BrowserMaze**: Pixel-level canvas maze navigation beyond accessibility capability
- **SportsTrackerTotalDistanceForCategoryOverInterval**: 3 consecutive failures despite targeted fixes. Agent consistently ignores scroll guidance. Park until structural scroll enforcement is available.

### Needs Investigation
- **ExpenseAddMultipleFromGallery**: hybrid perception mode — may work with correct config, needs dedicated investigation
- **RecipeAddMultipleRecipes**: 1/3 added in 30 turns — might pass with max_turns=50

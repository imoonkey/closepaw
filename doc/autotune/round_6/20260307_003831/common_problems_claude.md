# Round 6 Eval Analysis — Common Problems

**Run ID**: `20260307_003831` | **Date**: 2026-03-07 | **Model**: gpt-5.4
**Tasks**: 8 evaluated | **Pass Rate**: 1/8 = 12.5%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| RecipeAddMultipleRecipesFromImage | 0.0 | 32 | NavigationFailure + ReasoningError |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 22 | FalseCompletion |
| SportsTrackerActivitiesCountForWeek | 0.0 | 5 | ReasoningError |
| TasksCompletedTasksForDate | 0.0 | 11 | FalseCompletion |
| TasksDueNextWeek | 0.0 | 2 | ReasoningError |
| TasksIncompleteTasksOnDate | 0.0 | 2 | ReasoningError |
| VlcCreatePlaylist | 1.0 | 14 | Success |
| VlcCreateTwoPlaylists | 0.0 | 22 | ReasoningError |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | VlcCreatePlaylist |
| ReasoningError | 4 | SportsTrackerActivitiesCountForWeek, TasksDueNextWeek, TasksIncompleteTasksOnDate, VlcCreateTwoPlaylists |
| FalseCompletion | 2 | RecipeAddMultipleRecipesFromMarkor, TasksCompletedTasksForDate |
| NavigationFailure | 1 | RecipeAddMultipleRecipesFromImage |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Prompt split into per-app SKILL.md | Effective | VlcCreatePlaylist now passes (first-ever) |
| Shell tool general access | N/A | Not tested directly |
| GPT-5.4 model upgrade | Mixed | VLC workflows succeed, but Tasks app still FalseCompletes |

## Common Problems

### P1: Tasks App Premature Completion Without Reasoning — HIGH — 3 tasks

**Affected**: TasksDueNextWeek, TasksIncompleteTasksOnDate, TasksCompletedTasksForDate

**Root Cause**: All three Tasks app queries fail because the agent answers after 2-11 turns without: (a) scrolling to see all items, (b) resolving relative dates (Today/Tmrw/Wed) to absolute dates to apply the filter, (c) checking completion status (checkbox state). The date-label tip from R4 exists but GPT-5.4 either ignores it or cannot reason through the mapping correctly. TasksDueNextWeek answered "4" from a single viewport. TasksIncompleteTasksOnDate listed 8 items without filtering by date or status. TasksCompletedTasksForDate picked a task from the wrong date.

**Proposed Fix**:
- Strengthen Tasks app skill with explicit instruction: "For any counting or listing query: (1) scroll to the end of the list first, (2) compute today's date from visible cues, (3) map relative labels to absolute dates, (4) enumerate matching items with explicit pass/fail reasoning before answering"
- Add "verify before answer" guardrail for information-retrieval tasks

---

### P2: Multi-Item Workflow Endurance — MED — 3 tasks

**Affected**: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, VlcCreateTwoPlaylists

**Root Cause**: Agent struggles with repetitive multi-item workflows. RecipeAddMultipleRecipesFromMarkor self-terminated after 1/3 recipes citing a hallucinated "loop warning" with 28 turns remaining. RecipeAddMultipleRecipesFromImage got trapped in a detail view after saving recipe 1 and couldn't find the "add new" path. VlcCreateTwoPlaylists used an inefficient one-at-a-time file-add approach instead of the multi-select strategy that worked in VlcCreatePlaylist.

**Proposed Fix**:
- Add multi-item workflow guidance: "When adding N items, use the same proven add→save→back→add flow each time. Never try to create a new item from an edit/detail view."
- Address phantom loop detection: the agent fabricated a loop warning to justify quitting early. Either (a) the system prompt should clarify what constitutes a real loop (3+ identical actions with no screen change), or (b) examine whether the system prompt's repetition guardrail text is being misinterpreted
- For VLC specifically: the Video tab multi-select approach that worked for 2 files should be the default strategy for any playlist creation

---

### P3: Activity Type Perception Gap — LOW — 1 task

**Affected**: SportsTrackerActivitiesCountForWeek

**Root Cause**: The a11y tree shows activity titles (user-given names like "Bicycle Adventure") but not the underlying sport type (running/cycling/hiking). The agent guessed type from title text alone and got the wrong count. The correct approach is to tap into each activity to see its sport type, but the agent didn't attempt this.

**Proposed Fix**:
- Add OpenTracks app skill tip: "Activity type is not visible in the list view. To filter by type, open each activity's detail screen to check the recorded sport type."
- Alternatively, try using the app's search/filter functionality if available

---

## Next Steps

### Must Fix
1. **Tasks app completion guardrail**: Strengthen the Tasks app skill with scroll-before-answer and date-reasoning instructions. Also add a generic "verify before answer" prompt for information-retrieval tasks.
   - Targets: TasksDueNextWeek, TasksIncompleteTasksOnDate, TasksCompletedTasksForDate
   - Implementation: `assets/app_skills/tasks.md` + possibly `CompleteTaskTool` description

### Should Fix
2. **Multi-item workflow resilience**: Add guidance for repetitive add-N-items workflows (consistent add→save→back→add pattern, turn budget awareness, no premature self-termination). Clarify what constitutes a real loop vs. normal repetitive work.
   - Targets: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, VlcCreateTwoPlaylists
   - Implementation: System prompt or agent policy
3. **OpenTracks drill-down tip**: Add app skill tip that activity type requires detail-screen inspection.
   - Targets: SportsTrackerActivitiesCountForWeek
   - Implementation: `assets/app_skills/opentracks.md`

### Accept as Capability Gap
- None new. All 7 failures are addressable with prompt/skill improvements.

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

**Root Cause**: Agent struggles with repetitive multi-item workflows due to loop detection false positives. RecipeAddMultipleRecipesFromMarkor self-terminated after 1/3 recipes at turn 22/50 — the system injected a tool dominance warning ("mobile_action used 7 times without progress") and the agent obeyed system prompt rule 6 ("after loop warning, pivot immediately"). This was initially misdiagnosed as a "hallucinated loop warning" but was a real system-injected advisory. RecipeAddMultipleRecipesFromImage got trapped in a detail view after saving recipe 1 (27 tool dominance warnings accumulated). VlcCreateTwoPlaylists used an inefficient one-at-a-time file-add approach instead of the multi-select strategy that worked in VlcCreatePlaylist (17 warnings). See `loop_detection_redesign_claude.md` for full analysis.

**Proposed Fix**:
- Primary: delete loop detection WARNING advisories — see `loop_detection_redesign_claude.md`
- Secondary: after loop detection fix, re-evaluate whether multi-item guidance is still needed

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
1. **Loop detection redesign**: Delete all WARNING-level advisory warnings (tool dominance, scroll spam, action repetition, cycle detection). Keep only stable-screen factual warning (similarity 0.95, 5 turns) and final-turn warning. Rewrite system prompt rule 6 to remove "loop warning" reference. See full design: `loop_detection_redesign_claude.md`.
   - Targets: RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromImage, VlcCreateTwoPlaylists
   - Implementation: `LoopDetectionPolicy.kt`, `AgentTurnRunner.kt`, `NavigationState.kt`, `TurnToolPolicy.kt`, `StandaloneAgentDef.kt`
   - Owner layer: infra + core prompt

### Should Fix
2. **Tasks app skill (restore lost date-label tip)**: The R4 date-label tip was in the monolithic system prompt but dropped during the prompt refactor — Tasks app never got its own `SKILL.md`. Create `app_skills/org.tasks/SKILL.md` with: (a) date-label mapping guidance (relative labels like Today/Tmrw/Wed → use device date to resolve), (b) "scroll if you need more information beyond the current viewport" for list queries.
   - Targets: TasksDueNextWeek, TasksIncompleteTasksOnDate, TasksCompletedTasksForDate
   - Implementation: `app/src/main/assets/app_skills/org.tasks/SKILL.md`
   - Owner layer: app skill: org.tasks
3. **OpenTracks activity type tip**: Already present in `de.dennisguse.opentracks/SKILL.md` — verify it's sufficient. If the agent still guesses from titles, strengthen the wording.
   - Targets: SportsTrackerActivitiesCountForWeek
   - Implementation: `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
   - Owner layer: app skill: de.dennisguse.opentracks

### Housekeeping
4. **Update `/prompt-tune` skill**: After loop detection redesign, update the skill to cover system warnings as a prompt layer. System-injected warnings (stable screen, final turn) are part of the LLM's input and follow the same ownership/facts-not-opinions principles. Reflect the new SOTA: warnings state facts only, no strategy suggestions.
   - Implementation: `.claude/skills/prompt-tune/SKILL.md`

### Accept as Capability Gap
- None new. All 7 failures are addressable with infra/prompt/skill improvements.

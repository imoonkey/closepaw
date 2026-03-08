# Round 8 Eval Analysis — Common Problems

**Run ID**: `20260307_050909` (6 groups: 050909, 053316, 061407, 064200, 071709, 080143)
**Date**: 2026-03-07 | **Model**: GPT-5.4
**Tasks**: 116 evaluated | **Pass Rate**: 77.5/116 = 66.8%

## Scorecard — Full Results

| Group | Tasks | Score | Pass Rate |
|-------|-------|-------|-----------|
| 0 | 14 | 12/14 | 85.7% |
| 1 | 20 | 14/20 | 70.0% |
| 2 | 20 | 15/20 | 75.0% |
| 3 | 20 | 15.5/20 | 77.5% |
| 4 | 20 | 10/20 | 50.0% |
| 5 | 22 | 11/22 | 50.0% |

### R8 Fix Target Results (10 analyzed tasks)

| Task | Score | Turns | Root Cause | R7 | Delta |
|------|-------|-------|------------|----|-------|
| TasksCompletedTasksForDate | 0.0 | 8 | FalseCompletion + PerceptionGap | 0.0/14 | Skill injected but a11y lacks `checked`; accidental checkbox click |
| TasksDueNextWeek | 0.0 | 7 | ReasoningError | 0.0/7 | Skill NOT injected; over-counted (12 vs 6) |
| TasksIncompleteTasksOnDate | 0.0 | 11 | FalseCompletion | 0.0/11 | Skill NOT injected; checkbox clicking eliminated (model-level) |
| TasksDueOnDate | 0.0 | 4 | FalseCompletion | N/A | Skill NOT injected; hallucinated tasks from ambiguous dates |
| TasksHighPriorityTasks | 0.0 | 2 | FalseCompletion | N/A | Skill NOT injected; guessed from task name in 2 turns |
| TasksHighPriorityTasksDueOnDate | 0.0 | 26 | FalseCompletion | N/A | Not analyzed |
| VlcCreateTwoPlaylists | 0.0 | 29 | ReasoningError + TurnExhaustion | 0.5/30 | **REGRESSION**. Skill NOT injected. 1 playlist w/ 1 file (R7: 1 playlist w/ 4 files) |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 29 | ReasoningError + FalseCompletion | 0.0/50 | Multi-action worked (5-action turns). 50→29 turns. But overwrote recipe 1 |
| SportsTrackerActivitiesCountForWeek | 0.0 | 4 | ReasoningError | 0.0/30 | 30→4 turns. Guessed "0" from display names, never checked sport category |
| SystemBrightnessMax | 0.0 | 6 | ActionFailure + FalseCompletion | N/A | SeekBar swipe intercepted by QS shade gesture handler |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 25 | cannot_handle | 0.0 | DB-level duplicates indistinguishable via UI |

### Failed Tasks by Category (all 38.5 failures)

| Category | Count | Tasks |
|----------|-------|-------|
| FalseCompletion | ~18 | All Tasks.org (6), SportsTracker info (4), Calendar info (4), MarkorDeleteNewestNote, ExpenseDeleteDuplicates2, AudioRecorderFileName, RetroPlaylistDuration |
| TurnExhaustion | ~5 | SimpleCalendarDeleteEvents, BrowserDraw, BrowserMaze, RecipeAddMultipleRecipesFromImage, SimpleCalendarEventsInTimeRange |
| ActionFailure | ~4 | FilesMoveFile, SystemBrightnessMax, MarkorAddNoteHeader, OsmAndMarker |
| ReasoningError | ~6 | RecipeAddMultipleRecipesFromMarkor, VlcCreateTwoPlaylists, RecipeDeleteDuplicateRecipes2, OsmAndTrack, MarkorTranscribeVideo, MarkorMergeNotes |
| cannot_handle | ~3 | RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, ExpenseAddMultipleFromGallery |

## Previous Round Fix Assessment

| R8 Fix | Status | Impact |
|--------|--------|--------|
| Multi-action per turn (TurnToolPolicy) | **Partially effective** | RecipeAddMultiple used 5-action batch turns (50→29 turns). But GPT-5.4 rarely emits multiple tool calls unprompted — only 3 of 29 turns batched. VLC never batched (0/29). |
| Tasks app skill (org.tasks/SKILL.md) | **Inconsistently injected** | Injected for TasksCompletedTasksForDate (confirmed in trace). NOT injected for TasksDueNextWeek, TasksHighPriorityTasks, TasksIncompleteTasksOnDate. Package name timing issue. |
| VLC app skill (org.videolan.vlc/SKILL.md) | **NOT injected** | Zero VLC skill content in any trace. Multi-select and playlist guidance never reached the model. |
| Cog-tune quality upgrade | **Effective** | Per-task analyses now have turn-by-turn tables with reasoning/grounding/execution ratings. |
| Abort-on-first-failure | **Working** | No evidence of cascading failures from failed actions. |
| Navigation isolation prompt rule | **Working** | Agent respects "observe after navigation" pattern. |

## Common Problems

### P1: App Skill Injection Unreliable — CRITICAL — 6+ tasks

**Affected**: All Tasks.org tasks (6/6 failed), VlcCreateTwoPlaylists

**Root Cause**: App skills are injected based on foreground package name detection via `AccessibilityPlatform.getCurrentPackageName()`. The injection is timing-dependent: the skill appears only when the app is already in foreground at the start of a turn. For Tasks.org, the skill was injected in 1 of 6 runs (TasksCompletedTasksForDate) but not the other 5. For VLC, it was never injected across any run.

Possible causes:
1. **Race condition**: Package name captured before app fully foregrounds
2. **Package name mismatch**: App reports a sub-activity package (e.g., `com.todoroo.astrid`) instead of `org.tasks`
3. **Build not deployed**: The eval APK may not include the latest skill assets

**Proposed Fix**:
- **Immediate**: Debug the injection pipeline. Check `AgentTurnRunner` logs for `buildAppSkillMessage()` calls and what package name it receives. Compare against skill directory names.
- **Structural**: Make skill injection persistent for the session once triggered (not per-turn). If Tasks.org was detected at any point, keep the skill injected for subsequent turns.

---

### P2: FalseCompletion Epidemic — HIGH — ~18 tasks

**Affected**: Tasks.org (6), SportsTracker info (4), Calendar info queries (4), misc (4)

**Root Cause**: The agent declares GoalAchieved with incorrect answers, particularly for information-extraction tasks. Common patterns:
1. **Name-based guessing**: TasksHighPriorityTasks answered from task title rather than priority field (2 turns, zero evidence)
2. **Incomplete scan**: SportsTrackerActivitiesCountForWeek answered "0 running" without scrolling or checking sport category metadata
3. **Date resolution failures**: Tasks/Calendar tasks can't resolve relative dates ("Tue", "Mon", "Fri") to absolute dates
4. **Viewport overlap**: TasksDueNextWeek double-counted across scroll viewports

**Proposed Fix**:
- **Core prompt**: Add anti-hallucination guard for QA tasks: "For information tasks, do not answer until you have observed the specific data field. Never infer metadata (priority, category, type) from display names."
- **App skills**: Add task-specific query patterns (e.g., "Sort by priority" for Tasks.org, "Open track details to check sport type" for OpenTracks)

---

### P3: A11y Tree Missing Properties — HIGH — 6 tasks

**Affected**: All Tasks.org tasks

**Root Cause**: Tasks.org checkboxes render as `ImageView` with empty text, no content description, and `checked=N/A`. The `isChecked` property is not exposed in the a11y tree, making it impossible to:
1. Distinguish completed from incomplete tasks
2. Identify checkboxes vs decorative icons (leading to accidental checkbox clicks)

The Tasks app skill says "observe the checked/unchecked property" but this property doesn't exist in the sanitized a11y tree.

**Proposed Fix**:
- Check if `isChecked` exists in the raw a11y tree but is stripped during sanitization (perception pipeline bug)
- If the property genuinely isn't exposed by Tasks.org, add a diff-based strategy to the skill: "Toggle 'Show completed' OFF, note tasks, toggle ON, the new tasks are completed"
- Flag empty-text clickable ImageView elements in RecyclerView rows as potential checkboxes

---

### P4: Multi-Action Underutilized by Model — MED — most tasks

**Affected**: VlcCreateTwoPlaylists, most form-filling tasks

**Root Cause**: The TurnToolPolicy now allows multiple screen-changing actions per turn, but GPT-5.4 rarely emits multiple tool calls spontaneously. Only RecipeAddMultipleRecipesFromMarkor used multi-action (3 out of 29 turns had 5-action batches). VLC had 0 multi-action turns out of 29.

**Proposed Fix**:
- Add explicit few-shot example to system prompt showing batched tool calls (e.g., "When filling a form, you may batch multiple type actions in one turn")
- Or: add a `fill_form` tool that accepts field-value pairs if the generic approach doesn't generalize

---

### P5: SeekBar/Slider Manipulation Fragile — LOW — 2 tasks

**Affected**: SystemBrightnessMax, (potentially SystemBrightnessMin)

**Root Cause**: Agent uses swipe gesture on SeekBar, but the swipe coordinates extend beyond the slider bounds, causing the parent container's gesture handler to intercept (QS shade collapse in SystemBrightnessMax). The agent doesn't verify the slider state afterward.

**Proposed Fix**:
- Implement `set_range` action type for SeekBar elements (use a11y `ACTION_SET_PROGRESS`)
- Or: constrain swipe coordinates to strictly within the SeekBar bounds with margin

---

## Next Steps

### Must Fix
1. **Debug app skill injection pipeline**: This is the #1 blocker. The Tasks and VLC skills exist but don't reach the model. Need to trace `getCurrentPackageName()` → `buildAppSkillMessage()` → `AssetAppSkillRepository.load()` flow with logging.
   - Priority: CRITICAL
   - Implementation: Add debug logging, verify package names on emulator

2. **Fix Tasks.org a11y `checked` property**: Check raw vs sanitized a11y tree for `isChecked`. If sanitized away, fix the perception pipeline. If not exposed by app, add diff-based completion detection to skill.
   - Priority: HIGH

### Should Fix
3. **Anti-hallucination prompt for QA tasks**: Add to core prompt rule: "For information tasks, verify your answer against observed data. Never infer metadata from display names."
   - Priority: HIGH
   - Targets: All FalseCompletion tasks (~18)

4. **Few-shot multi-action example**: Add explicit batched tool call example to system prompt to encourage GPT-5.4 to use multi-action capability.
   - Priority: MED

### Accept as Capability Gap
- RecipeDeleteDuplicateRecipes2/3: DB-level duplicates indistinguishable via UI (`cannot_handle`)
- BrowserDraw, BrowserMaze: Require pixel-level interaction not supported by a11y
- ExpenseAddMultipleFromGallery: Gallery permission/interaction issues

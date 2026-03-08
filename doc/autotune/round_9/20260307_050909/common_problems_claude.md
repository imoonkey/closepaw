# Round 9 Eval Analysis — Common Problems (Full GPT-5.4 Baseline)

**Run ID**: `20260307_050909` (6 groups: 050909, 053316, 061407, 064200, 071709, 080143)
**Date**: 2026-03-07 | **Model**: GPT-5.4 | **Round**: 9 (no code changes from R8; eval-only)
**Tasks**: 116 evaluated | **Pass Rate**: 77.5/116 = 66.8%

This round establishes the first comprehensive GPT-5.4 baseline across all 116 tasks. Round 8 applied multi-action, app skills, and cog-tune upgrades. Round 9 ran the full eval without additional changes.

## Score Summary

| Group | Tasks | Score | Pass Rate | Category Mix |
|-------|-------|-------|-----------|-------------|
| 0 | 14 | 12/14 | 85.7% | Easy: System, Camera, Clock, Contacts, SMS, Markor basic |
| 1 | 20 | 14/20 | 70.0% | Medium: Calendar write, Expense, Audio, Browser |
| 2 | 20 | 15/20 | 75.0% | Medium: Expense delete, Markor edit, Calendar read, SMS |
| 3 | 20 | 15.5/20 | 77.5% | Medium-Hard: Recipe delete, Retro, OsmAnd, SportsTracker |
| 4 | 20 | 10/20 | 50.0% | Hard: Recipe add, OsmAnd hard, VLC, Calendar read |
| 5 | 22 | 11/22 | 50.0% | Hard: SportsTracker info, Tasks.org, System verify |

### By App

| App | Pass | Fail | Rate | Failure Pattern |
|-----|------|------|------|-----------------|
| System (BT/WiFi/Brightness) | 11 | 1 | 92% | SystemBrightnessMax: SeekBar gesture |
| SimpleSmsSend/Reply | 6 | 0 | 100% | — |
| Markor | 9 | 5 | 64% | Header blank line, merge, transcribe video, newest note, move |
| SimpleCalendar (write) | 7 | 2 | 78% | Relative day, delete multiple |
| SimpleCalendar (read) | 3 | 5 | 38% | Date navigation, event enumeration |
| Expense | 5 | 3 | 63% | Cross-app import, dedup |
| Recipe (delete) | 5 | 2 | 71% | DB-level duplicates (cannot_handle) |
| Recipe (add) | 1 | 5 | 17% | Multi-step form-fill + cross-app |
| Retro Music | 2 | 2 | 50% | Song addition flow, playlist duration |
| Tasks.org | 0 | 6 | 0% | App skill not injected, FalseCompletion |
| SportsTracker | 2 | 4 | 33% | Category metadata hidden, turn exhaustion |
| VLC | 1 | 1 | 50% | Multi-select not taught (skill not injected) |
| OsmAnd | 1 | 2 | 33% | Map UI inaccessible |
| Notes | 4 | 0 | 100% | — |
| Browser | 1 | 2 | 33% | Draw/Maze: pixel interaction |
| Camera | 2 | 0 | 100% | — |
| Clock | 3 | 0 | 100% | — |
| Contacts | 2 | 0 | 100% | — |
| Files | 1 | 1 | 50% | Wrong file grounding |
| AudioRecorder | 1 | 1 | 50% | Filename field |

### 18 Analyzed Tasks (10 R8 + 8 R9)

| Task | Score | Turns | Root Cause | Analysis |
|------|-------|-------|------------|----------|
| TasksCompletedTasksForDate | 0.0 | 8 | FalseCompletion + PerceptionGap | A11y lacks `checked` |
| TasksDueNextWeek | 0.0 | 7 | ReasoningError | Over-counted, viewport overlap |
| TasksIncompleteTasksOnDate | 0.0 | 11 | FalseCompletion | Date filtering errors |
| TasksDueOnDate | 0.0 | 4 | FalseCompletion | Hallucinated tasks |
| TasksHighPriorityTasks | 0.0 | 2 | FalseCompletion | Guessed from name |
| VlcCreateTwoPlaylists | 0.0 | 29 | ReasoningError + TurnExhaustion | VLC skill not injected |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 29 | ReasoningError | Edit-vs-create confusion |
| SportsTrackerActivitiesCountForWeek | 0.0 | 4 | ReasoningError | Guessed from display names |
| SystemBrightnessMax | 0.0 | 6 | ActionFailure | SeekBar swipe intercepted |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 25 | cannot_handle | DB-level duplicates |
| SimpleCalendarEventsOnDate | 0.0 | 9 | FalseCompletion | Never navigated to target date |
| MarkorAddNoteHeader | 0.0 | 16 | ActionFailure | `\n\n` swallowed by action layer |
| ExpenseAddMultipleFromMarkor | 0.0 | 17 | FalseCompletion | Created file in Markor, never opened Expense app |
| RetroSavePlaylist | 0.0 | 20 | ReasoningError | Stuck in empty playlist view |
| SportsTrackerLongestDistanceActivity | 0.0 | 30 | TurnExhaustion | 4/7 tracks checked, missed target |
| FilesMoveFile | 0.0 | 14 | GroundingError | Moved wrong file (partial name match) |
| SimpleCalendarAddOneEventRelativeDay | 0.0 | 24 | ReasoningError | "5h" → 17:00 instead of 05:00 |
| MarkorDeleteNewestNote | 0.0 | 7 | PerceptionGap | A11y reported swapped timestamps |

## Common Problems

### P1: App Skill Injection Pipeline Broken — CRITICAL — 7+ tasks

Carried from R8. Tasks.org skill injected in 1/6 runs. VLC skill never injected. This is the single highest-impact bug blocking 7+ tasks.

---

### P2: FalseCompletion on Information Tasks — HIGH — ~18 tasks

Agent declares success with wrong answers on information-extraction tasks. Patterns:
- **Never navigates to target**: SimpleCalendarEventsOnDate never reached Oct 24; ExpenseAddMultipleFromMarkor never opened Expense app
- **Name-based guessing**: TasksHighPriorityTasks inferred priority from task title
- **Incomplete scan**: SportsTracker answered without scrolling; TasksDueNextWeek double-counted
- **No post-verification**: FilesMoveFile moved wrong file without checking

---

### P3: Calendar Date Picker Navigation — HIGH — 7 tasks (3 write + 4 read)

Two distinct sub-problems:
1. **Date picker interaction**: SimpleCalendarEventsOnDate tried to click on month grid but hit wrong coordinates. Agent can't reliably navigate to a specific date in SimpleCalendar's month view.
2. **Relative time resolution**: SimpleCalendarAddOneEventRelativeDay interpreted "5h" as 17:00 instead of 05:00. Different from date-of-week resolution — this is time-of-day ambiguity.

---

### P4: Action Layer Newline Bug — MED — 1+ tasks

MarkorAddNoteHeader: GPT-5.4 correctly included `\n\n` in the type action, but the action layer (ACTION_SET_TEXT) delivered only `\n`. This was "fixed" in R3 for qwen3.5 by adding a blank line tip, but the underlying action bug persists. The R3 fix was a workaround, not a root cause fix.

**Investigation needed**: Check if `NodeActionPerformer.setTextOnNode()` strips or normalizes newlines when constructing the Bundle argument for `ACTION_SET_TEXT`.

---

### P5: Cross-App Destination Binding — MED — 3 tasks

ExpenseAddMultipleFromMarkor, ExpenseAddMultipleFromGallery, (potentially RecipeAddMultipleRecipesFromImage). Agent extracts source data correctly but fails to switch to the destination app. ExpenseAddMultipleFromMarkor created a text file in Markor instead of opening Pro Expense. Same pattern across R5 and R9.

**Fix**: Core prompt guidance or tool-level check: "When the task names a destination app, verify you have opened that app before entering data."

---

### P6: Retro Music Song Addition Flow — MED — 2 tasks

RetroSavePlaylist: Agent creates playlist but can't add songs to it. Gets stuck in empty playlist view. The working flow (used by qwen3.5 in R2): navigate to Songs tab → find song → long-press → "Add to playlist". GPT-5.4 tries to add from within the playlist, which doesn't work.

---

### P7: SportsTracker Per-Item Inspection Cost — LOW — 4 tasks

Checking sport type requires opening each track → More → Edit → read category → back × 2. At 4-5 turns/track, a 7-track week exhausts 30 turns before checking all. Multi-action could help (batch the navigation) but the fundamental issue is metadata not being visible in the list view.

---

### P8: A11y Tree Timestamp/Property Gaps — LOW — 2 tasks

MarkorDeleteNewestNote: A11y tree showed swapped modification times vs what the screenshot displayed. This is a perception pipeline issue — either the a11y tree is stale or the timestamps are being incorrectly parsed/ordered.

---

## Failure Category Distribution (38.5 failures)

| Category | Count | % of Failures |
|----------|-------|---------------|
| FalseCompletion | 18 | 47% |
| ReasoningError | 7 | 18% |
| TurnExhaustion | 5 | 13% |
| ActionFailure | 4 | 10% |
| cannot_handle | 3 | 8% |
| PerceptionGap | 1.5 | 4% |

**FalseCompletion is the dominant failure mode** — nearly half of all failures. The agent is too eager to declare success without sufficient evidence.

## Next Steps

### Must Fix (R10)
1. **Fix app skill injection pipeline** — Debug `getCurrentPackageName()` → `buildAppSkillMessage()` → `AssetAppSkillRepository.load()`. Add logging. This unblocks Tasks.org (6 tasks) and VLC (1 task).
2. **Anti-hallucination prompt guard for QA tasks** — Add to core prompt: "For information tasks, verify your answer against observed data before completing. Never infer metadata (priority, category, type) from display names."
3. **Fix a11y `checked` property** — Check raw vs sanitized a11y tree for Tasks.org checkboxes.

### Should Fix (R10-11)
4. **Calendar date picker navigation** — Add SimpleCalendar app skill with date navigation patterns (month grid, forward/back buttons, date resolution examples).
5. **Cross-app destination binding** — Add prompt guard: "When task names a destination app, open that app before entering data."
6. **Investigate newline action bug** — Check `NodeActionPerformer.setTextOnNode()` for `\n\n` normalization.
7. **Retro Music skill** — Add song addition flow guidance (Songs tab → find → long-press → Add to playlist).

### Accept as Capability Gap
- RecipeDeleteDuplicateRecipes2/3: DB-level duplicates (cannot_handle)
- BrowserDraw, BrowserMaze: Pixel-level interaction
- MarkorTranscribeVideo: Video OCR
- OsmAndMarker, OsmAndTrack: Map UI inaccessible

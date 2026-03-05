# Round 3 Eval Analysis — Aligned

**Run ID**: `20260304_152742` | **Date**: 2026-03-05 | **Model**: qwen3.5
**Tasks**: 25 | **Pass Rate**: 19/25 (76%)

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| MarkorAddNoteHeader | 1.0 | 27 | Success |
| MarkorEditNote | 1.0 | 9 | Success |
| NotesIsTodo | 1.0 | 2 | Success |
| NotesTodoItemCount | 1.0 | 4 | Success |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | 48 | Success |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 30 | TurnExhaustion |
| RecipeDeleteSingleWithRecipeWithNoise | 1.0 | 8 | Success |
| RetroPlaylistDuration | 0.0 | 30 | TurnExhaustion / ModelLimitation |
| SimpleCalendarEventsInTimeRange | 1.0 | 5 | Success |
| SimpleCalendarFirstEventAfterStartTime | 1.0 | 9 | Success |
| SportsTrackerActivitiesCountForWeek | 0.0 | 0 (timeout; trace shows 25 turns) | InfraError + TurnExhaustion signal |
| SportsTrackerActivityDuration | 1.0 | 10 | Success |
| SportsTrackerLongestDistanceActivity | 1.0 | 30 | Success (fragile — at turn limit) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | 28 | Success (fragile — near turn limit) |
| SportsTrackerTotalDurationForCategoryThisWeek | 1.0 | 16 | Success |
| SystemBluetoothTurnOffVerify | 1.0 | 11 | Success |
| SystemBluetoothTurnOnVerify | 1.0 | 5 | Success |
| SystemBrightnessMaxVerify | 1.0 | 8 | Success |
| SystemBrightnessMinVerify | 1.0 | 4 | Success |
| SystemWifiTurnOffVerify | 1.0 | 3 | Success |
| SystemWifiTurnOnVerify | 1.0 | 4 | Success |
| TasksCompletedTasksForDate | 0.0 | 30 | FalseCompletion |
| TasksDueNextWeek | 0.0 | 6 | FalseCompletion (under-verified count) |
| TasksHighPriorityTasksDueOnDate | 1.0 | 21 | Success |
| TasksIncompleteTasksOnDate | 0.0 | 2 | FalseCompletion (no date filtering) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 19 | (see above) |
| FalseCompletion | 3 | TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate |
| TurnExhaustion | 2 | RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration |
| InfraError | 1 | SportsTrackerActivitiesCountForWeek |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Markor blank-line/cursor guidance (P1 from R2) | **Working** | MarkorAddNoteHeader: 0 -> 1.0 (2-round stuck bug fixed) |
| Markor edit path stability | **Stable** | MarkorEditNote stayed at 1.0 (7 -> 9 turns, minor) |
| Retro duration strategy prompting (P2 from R2) | **Ineffective** | RetroPlaylistDuration still 0.0 at 30 turns. Agent didn't follow tip. |

## Common Problems

### P1: Tasks App False Completion — HIGH — 3 tasks

**Affected**: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause** (agreed by both analyses): The agent calls `complete_task` before building date-filtered, verified evidence. Specific failure modes:
- TasksCompletedTasksForDate (30 turns): Looped trying to find completed tasks for a specific date, eventually submitted unverified answer.
- TasksDueNextWeek (6 turns): Returned a count after shallow scanning without robust date-window verification.
- TasksIncompleteTasksOnDate (2 turns): Answered immediately without any date filtering — included tasks past the cutoff.

The underlying issue is that the Tasks app shows relative date labels ("Mon", "Tue", "Oct 22") rather than absolute dates. The agent cannot reliably map between absolute dates in the goal and relative labels in the UI.

**Fix (approved)**:
1. Add generic date-label tip to system prompt: "Some apps show relative date labels (day of week, 'tomorrow', MM/DD). Use the device date in your context to map between the goal's date and the UI labels." (Device info already includes today's date — no shell `date` needed.)
2. Add QA completion guardrail: "For count/list questions, use scratchpad to record each matching item with its evidence before calling complete_task. Do not guess."

---

### P2: Turn Inefficiency on Long-Horizon Tasks — HIGH — 4 tasks

**Affected (failed)**: RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration
**Affected (fragile passes)**: SportsTrackerLongestDistanceActivity (30 turns), SportsTrackerTotalDistanceForCategoryOverInterval (28 turns)

**Root Cause** (agreed): The agent uses correct local actions but wastes turns on exploratory loops, shell detours, and repeated recounting. On hard tasks this causes turn exhaustion; on marginal passes it creates fragile near-limit behavior.

**Fix (approved)**:
1. Restrict shell tool to Files-related operations only (update tool description or system prompt). No shell for other apps.
2. Add general repetitive-task guidance to system prompt: "For repetitive multi-step tasks (deleting many items, adding many entries, etc.), use write_todo to plan and track progress. Once you find an effective working approach, commit to it — do not explore alternatives mid-execution."
3. RecipeDeleteDuplicateRecipes3: Set max_turns=50. If still failing, park.
4. RetroPlaylistDuration: Replace old strategy tip with app tip: "In Retro Music, to add songs to a playlist: go to the Songs tab, long-press a song to start selection mode, then tap additional songs to select multiple at once. Use 'Add to playlist' from the menu to add them in batch." Set max_turns=45.

---

### P3: SportsTrackerActivitiesCountForWeek — Infra/Timeout — MED — 1 task

**Root Cause**: Both analyses agree the agent ran many actions but the session didn't terminate cleanly.
- Claude: classifies as TurnExhaustion + InfraError (25+ turns of checking each track's type)
- Codex: classifies as InfraError (no session_stopped event, bridge timeout)

**Proposed Fix**:
- Add instrumentation for missing `session_stopped` + fail-safe run summary flush on forced stop.

## What Worked

1. **Markor blank-line tip** -> MarkorAddNoteHeader: 0 -> 1.0. 2-round stuck bug finally fixed.
2. **Group 5 first-run sweep**: 17/22 never-run tasks passed first attempt (77%).
3. **System verify tasks**: 6/6 passing.
4. **SportsTracker expansion**: 4/5 new tasks passing.
5. **Calendar expansion**: 2/2 passing.
6. **Notes tasks**: 2/2 passing.
7. **Recipe tasks**: 2/3 passing.

## Next Steps (Approved for Round 4)

### Must Fix
1. **Tasks app date reasoning + completion guardrail** (P1): Add generic date-label tip to system prompt + scratchpad-based QA answer grounding. Targets: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate.
2. **Shell tool restriction** (P2): Restrict shell to Files-related operations only.
3. **Repetitive-task guidance** (P2): Add general "use write_todo, commit to working approach" guidance to system prompt.
4. **RetroPlaylistDuration app tip** (P2): Replace old strategy tip with batch-select tip. Set max_turns=45.
5. **RecipeDeleteDuplicateRecipes3 max_turns** (P2): Set max_turns=50. Park if still failing.

### Should Fix
6. **Timeout finalization observability** (P3): Guarantee timeout runs emit completion diagnostics. Target: SportsTrackerActivitiesCountForWeek.

### Scoreboard Enhancement
7. **Per-app success rate**: Add to scoreboard.py output (loose definition: 1+ success in last 3 runs).

### Accept / Park
- **RecipeDeleteDuplicateRecipes3**: Park if still failing after max_turns=50.

## Per-App Success Rate (loose: last 3 runs, 1+ success)

| App | Rate | Notes |
|-----|------|-------|
| SimpleCalendar | 17/17 (100%) | |
| System | 13/13 (100%) | |
| Camera | 2/2 (100%) | |
| Contacts | 2/2 (100%) | |
| Files | 2/2 (100%) | |
| Notes | 4/4 (100%) | |
| SimpleSms | 6/6 (100%) | |
| Markor | 12/14 (85%) | 2 stuck: MergeNotes, TranscribeVideo |
| SportsTracker | 5/6 (83%) | 1 infra: ActivitiesCountForWeek |
| Retro | 3/4 (75%) | 1 stuck: PlaylistDuration |
| Recipe | 9/13 (69%) | 4 stuck (cross-app import + dedup variants) |
| Expense | 6/9 (66%) | 3 stuck (cross-app import + dedup) |
| Clock | 2/3 (66%) | 1 stuck: TimerEntry |
| Tasks | 3/6 (50%) | 3 new failures (date reasoning) |
| AudioRecorder | 1/2 (50%) | 1 stuck: RecordAudioWithFileName |
| OsmAnd | 1/3 (33%) | 2 stuck: Marker, Track |
| Browser | 1/3 (33%) | 2 stuck: Draw, Multiply |
| Vlc | 0/2 (0%) | All stuck |

## Never-Succeeded Tasks (22, by pattern)

**Pattern A: Cross-app data import (5)** — read from one app, input into another
- ExpenseAddMultipleFromGallery (7 runs), ExpenseAddMultipleFromMarkor (8 runs)
- RecipeAddMultipleRecipesFromImage (4 runs), RecipeAddMultipleRecipesFromMarkor (4 runs)
- MarkorTranscribeVideo (3 runs)

**Pattern B: Complex dedup / bulk delete (3)** — compare multiple items, selectively delete
- ExpenseDeleteDuplicates2 (5 runs), RecipeDeleteDuplicateRecipes2 (4 runs), RecipeDeleteDuplicateRecipes3 (1 run)

**Pattern C: Non-standard UI (5)** — maps, drawing, calculation
- BrowserDraw (5 runs), BrowserMultiply (7 runs), OsmAndMarker (3 runs), OsmAndTrack (3 runs), ClockTimerEntry (5 runs)

**Pattern D: Media player playlists (3)** — VLC and Retro playlist operations
- VlcCreatePlaylist (3 runs), VlcCreateTwoPlaylists (4 runs), RetroPlaylistDuration (6 runs)

**Pattern E: Multi-step text (1)** — MarkorMergeNotes (5 runs)

**Pattern F: Audio recording variant (1)** — AudioRecorderRecordAudioWithFileName (5 runs)

**Pattern G: Tasks app new tasks (3)** — first run, date reasoning issue
- TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate (1 run each)

**Takeaway**: Patterns A+B (cross-app import + dedup) are the largest failure cluster (8 tasks). Solving cross-app data transfer would yield significant gains. Pattern C (non-standard UI) may be partially a capability gap. Pattern G (Tasks) is being fixed this round.



## Qi Notes

P1: 修复：加Tasks应用提示（用shell date算星期几再对应UI）+ QA完成护栏（scratchpad记录证据再complete_task）
> 1. system prompt里 device info有今天日期和星期几。2. prompt应该generically talk about date format, e.g., day of week, "tomorrow", mmdd etc. 不要overfit to 现在task是yymmdd，app是tomorrow, day of week 然后超过一周又是mmdd的形式。

**回复**: 同意。device info已经有日期，不需要shell `date`。提示会写成generic的："Some apps show relative date labels (day of week, 'tomorrow', MM/DD). Use the device date in your context to map between the goal's date and the UI labels." 不会overfit到某种特定格式。QA护栏也保留。

P2:
1. shell：加/改description or system prompt，只在Files/ manipulating files时候用，别的app不要用。
2. "Add long-horizon checklist policy: fixed extraction/deletion loop, explicit stop conditions" 什么玩意，听不懂。
3. SportsTracker: 不加提示。
4. RecipeDeleteDuplicateRecipes3: 加turns上限到50，如果还是过不了就先park。
5. RetroPlaylistDuration: 加个app tip: on Songs tab, long-press to select multiple songs(或者类似的，帮我polish一下)。轮数加高一点。

**回复**:
1. 同意。shell tool的description/system prompt限制为Files相关操作。
2. 抱歉，说人话就是：原来的aligned建议是在prompt里加一段类似"删除多个item时，按固定节奏做：找到目标→删除→确认→下一个，不要中途停下来重新数"的指引。但既然你觉得不需要，那就不加，用turns上限来兜底。
3. 收到，SportsTracker不加提示。
4. 收到，RecipeDeleteDuplicateRecipes3 max_turns=50，不过就park。
5. RetroPlaylistDuration app tip polish: "In Retro Music, to add songs to a playlist: go to the Songs tab, long-press a song to start selection mode, then tap additional songs to select multiple at once. Use 'Add to playlist' from the menu to add them in batch." 轮数建议调到45？

Qi:
2. 这个太hacky, overfit to deletion case。system prompt可以加，如果有长程多个重复性任务，用write_todo管理。发现一条可行优化路径后，不要explore。类似这种general的，可以吗？

**回复**: 完全同意，这样更好。General的guidance比overfit的deletion节奏好得多。类似："For repetitive multi-step tasks (deleting many items, adding many entries, etc.), use write_todo to plan and track progress. Once you find an effective working approach, commit to it — do not explore alternatives mid-execution." 这种就够了，不需要针对具体app。


P3: OK

**回复**: 收到，P3 infra修复照做。

Extra:
1. 每个task name的开头基本都是app name，看看不同app的task的成功率情况。for this analysis, use a loose definition of success: 一个task的most recent 3 runs至少成功过一次就算success。把这个加到scoreboard里。
2. 帮我总结一下哪些任务一次都没有success过，看看有没有什么common pattern（app, task type, etc）可以指导下一轮的fix。

**回复**:

### Per-App Success Rate (loose: last 3 runs中至少成功1次)

| App | Rate | Notes |
|-----|------|-------|
| SimpleCalendar | 17/17 (100%) | |
| System | 13/13 (100%) | |
| Camera | 2/2 (100%) | |
| Contacts | 2/2 (100%) | |
| Files | 2/2 (100%) | |
| Notes | 4/4 (100%) | |
| SimpleSms | 6/6 (100%) | |
| Markor | 12/14 (85%) | 2 stuck: MergeNotes, TranscribeVideo |
| SportsTracker | 5/6 (83%) | 1 infra: ActivitiesCountForWeek |
| Retro | 3/4 (75%) | 1 stuck: PlaylistDuration |
| Recipe | 9/13 (69%) | 4 stuck (cross-app import + dedup variants) |
| Expense | 6/9 (66%) | 3 stuck (cross-app import + dedup) |
| Clock | 2/3 (66%) | 1 stuck: TimerEntry |
| Tasks | 3/6 (50%) | 3 new failures (date reasoning) |
| AudioRecorder | 1/2 (50%) | 1 stuck: RecordAudioWithFileName |
| OsmAnd | 1/3 (33%) | 2 stuck: Marker, Track |
| Browser | 1/3 (33%) | 2 stuck: Draw, Multiply |
| Vlc | 0/2 (0%) | 全部stuck |

### Never Succeeded的任务 (22个，按pattern分组)

**Pattern A: 跨应用数据导入 (5个)** — 需要从一个app读数据再到另一个app输入
- ExpenseAddMultipleFromGallery (7 runs)
- ExpenseAddMultipleFromMarkor (8 runs)
- RecipeAddMultipleRecipesFromImage (4 runs)
- RecipeAddMultipleRecipesFromMarkor (4 runs)
- MarkorTranscribeVideo (3 runs)

**Pattern B: 复杂去重/批量删除 (3个)** — 需要比较多个item后选择性删除
- ExpenseDeleteDuplicates2 (5 runs)
- RecipeDeleteDuplicateRecipes2 (4 runs)
- RecipeDeleteDuplicateRecipes3 (1 run)

**Pattern C: 非标准UI交互 (5个)** — 需要地图操作、画图、计算等非常规操作
- BrowserDraw (5 runs)
- BrowserMultiply (7 runs)
- OsmAndMarker (3 runs)
- OsmAndTrack (3 runs)
- ClockTimerEntry (5 runs)

**Pattern D: 媒体播放器 (3个)** — VLC和Retro的playlist操作
- VlcCreatePlaylist (3 runs)
- VlcCreateTwoPlaylists (4 runs)
- RetroPlaylistDuration (6 runs)

**Pattern E: 多步文本操作 (1个)**
- MarkorMergeNotes (5 runs)

**Pattern F: 音频录制变体 (1个)**
- AudioRecorderRecordAudioWithFileName (5 runs)

**Pattern G: Tasks app新任务 (3个)** — 首次运行，date reasoning问题
- TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate (各1 run)

**指导意义**: Pattern A (跨应用导入) 和 Pattern B (批量去重) 是最大的failure cluster，涉及8个任务。如果能解决跨应用数据传递的通用能力，会有显著收益。Pattern C (非标准UI) 可能部分是capability gap。Pattern G (Tasks) 是本轮要修的。

scoreboard per-app统计会加到scoreboard.py的输出里。

Qi:上面的分析写到scoreboard里吗？还是单独写个文档？

**回复**: 建议两层：
1. **Per-app success rate表** → 加到scoreboard.py自动生成，写入scoreboard.md的新section。每次eval后自动更新，不需要手动维护。
2. **Never-succeeded pattern分析** → 留在这个aligned_analysis里就行，这是一次性的分析洞察，不需要每轮重复。后续如果pattern变了，在新round的分析里更新。
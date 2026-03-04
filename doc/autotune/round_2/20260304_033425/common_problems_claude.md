# Common Problems — Round 2 (20260304_033425)

**Agent**: qwen3.5 | **Model**: basic | **Perception**: accessibility_only
**Run**: 20260304_033425 | **Tasks**: 8 | **Score**: 6/8 (75%)

## Summary

Round 2 applied 8 fixes from Round 1's approved next steps. Results show strong improvement: 5 tasks flipped from 0→1, 1 new task passed, 2 tasks remain at 0.

| Task | R1 Score | R2 Score | Delta | Fix Applied |
|------|----------|----------|-------|-------------|
| MarkorAddNoteHeader | 0 | 0 | — | P4 cursor positioning + Q3 keyboard flag |
| MarkorCreateNote | 0 | **1** | +1 | P3 Markor extension tip |
| MarkorEditNote | 0 | **1** | +1 | P4 cursor positioning + Q3 keyboard flag |
| RecipeDeleteMultipleRecipes | — | **1** | new | Q12 write_todos re-enabled |
| RetroCreatePlaylist | 0 | **1** | +1 | P1 hint text fix |
| RetroPlaylistDuration | 0 | 0 | — | P5 Retro Music tips |
| RetroSavePlaylist | — | **1** | +1 | P1 hint text + P5 tips |
| SportsTrackerActivitiesOnDate | 0 | **1** | +1 | P2 completion verification + P5 OpenTracks tips |

## Remaining Problems

### P1: MarkorAddNoteHeader — `\n` vs `\n\n` for blank lines (Reasoning)

**Severity**: High | **Recurrence**: 2 rounds | **Tasks**: MarkorAddNoteHeader

The agent uses `\n` (single newline) when the task requires "a new blank line" (`\n\n` double newline). This is the exact same bug as R1. The agent:
1. Correctly used Special Key > Document Start (P4 tip worked!)
2. Typed header text with `\n` instead of `\n\n`
3. Verified the file content but didn't notice the missing blank line
4. Declared success — false positive (worse than R1's exhausted-turns-trying-to-fix behavior)

**Root cause**: Reasoning — "blank line" → `\n\n` interpretation is missing.

### P2: RetroPlaylistDuration — Wrong strategy for duration-constrained tasks (Reasoning)

**Severity**: High | **Recurrence**: 3+ rounds | **Tasks**: RetroPlaylistDuration

The agent tries to pre-read individual song durations (via Details dialogs, ~3 turns each) instead of using the iterative add-and-check approach. The playlist card shows a running total ("N Songs / MM:SS") that updates after each addition, but the agent never exploits this.

Used 30/30 turns, added only 2 songs, then self-reported failure.

**Root cause**: Reasoning — chose wrong strategy despite visible running total.

### Q1: False-positive completion on MarkorAddNoteHeader

The R2 agent is more confident but wrong on MarkorAddNoteHeader. In R1 it at least detected the content problem and tried to fix it (failing after 30 turns). In R2 it declares success at turn 15 without noticing the missing blank line. The P2 completion verification checklist may not be specific enough about whitespace/formatting.

### Q2: write_todos not used by RecipeDeleteMultipleRecipes

The Q12 fix re-enabled write_todos and added soft guidance, but RecipeDeleteMultipleRecipes (3 items) completed successfully without using it. This makes sense — 3 items is simple enough. The tool's value will be clearer on larger delete/add tasks.

### Q3: keyboard_visible flag not observed — root cause found

The keyboard_visible flag was implemented in Perceptor (Phase D) but was NOT present in any R2 observation. APK was rebuilt and installed (eval runner runs `assembleDebug` + `adb install` in `build_and_install_bridge()`).

**Root cause**: `AccessibilityPlatform.collectRootsOnActiveDisplay()` (line 227) filters out `TYPE_INPUT_METHOD` windows before passing roots to `Perceptor.snapshot()`. The keyboard nodes are never traversed, so `isKnownKeyboardNode()` never fires and `keyboardFlag` stays false.

**Fix**: Detect keyboard visibility from the `windows` list directly — `windows.any { it.type == TYPE_INPUT_METHOD }` — instead of relying on node traversal. Remove `keyboardFlag` threading from `traverse()`/`traverseChildren()`.

## What Worked

1. **P3 Markor extension tip** → MarkorCreateNote: 0→1. Agent correctly handled the filename.
2. **P4 cursor positioning tip** → MarkorEditNote: 0→1 in just 7 turns. Agent used Special Key > Document Start efficiently.
3. **P1 hint text fix** → RetroCreatePlaylist: 0→1, RetroSavePlaylist: new→1. Playlist name typing no longer has hint contamination.
4. **P2 + P5 completion verification + OpenTracks tips** → SportsTrackerActivitiesOnDate: 0→1. Agent correctly used Edit dialog to find activity type.
5. **Q7 agent_thought ordering** → 0 tool failures across 101 calls in 6 passing tasks. Clean execution.

## Next Steps (approved for Round 3)

### Fixes

1. **Blank line interpretation app tip** (P1): Add to system prompt Markor tips: "When the task says 'blank line' or 'empty line', use `\n\n` (double newline). A single `\n` creates a line break, NOT a blank line."

2. **Duration-constrained playlist strategy app tip** (P2): Add to system prompt Retro Music tips: "For playlist duration targets, use iterative add-and-check: add songs one by one, navigate to playlist view to read the running total (visible as 'N Songs / MM:SS'), stop when target is reached. Do NOT pre-read individual song durations."

3. **Fix keyboard_visible detection** (Q3): Move detection from Perceptor node traversal to `AccessibilityPlatform.collectRootsOnActiveDisplay()` — use `windows.any { it.type == TYPE_INPUT_METHOD }`. Remove `keyboardFlag` threading from `traverse()`/`traverseChildren()`.

### Task Selection

- **Group 5 full sweep** (22 tasks): All never-run tasks from `aw_subset_group_5.txt`. Ensures every task in the universe has at least one run.
- **Targeted fixes** (~3 tasks): MarkorAddNoteHeader (P1 blank line), RetroPlaylistDuration (P2 duration strategy), plus 1 text-input task to verify keyboard_visible flag.

# Qi Note
1. Must-fix 1/2: For those fixes, add as app tips in system prompt.
2. Must-fix 3: 这应该install了吧，没install为什么别的fix apply了？需要确认一下。
3. should fix 两个都不做，都overfit to eval dataset了，real world里没用。

Extra analysis ask:
1. 你帮我看看scoreboard，全部task里eval/config/aw_fullset.txt还有哪些从来没跑过，是不是都在eval/config/aw_subset_group_5.txt里？
2. 每个task name的开头基本都是app name，看看不同app的task的成功率情况。for this analysis, use a loose definition of success: 一个task的most recent 3 runs至少成功过一次就算success。

# Qi Note — Answers

### 1. Must-fix 1/2: app tips
Acknowledged. R3 will add blank line rule and duration strategy as app tips in system prompt (StandaloneAgentDef).

### 2. Must-fix 3: keyboard_visible APK rebuild
**APK was rebuilt and installed.** The eval runner runs `assembleDebug` + `adb install` in `runner_preflight.build_and_install_bridge()` (runner_preflight.py:705-728) before every eval run. The code fix is in the deployed APK.

**Root cause found**: `AccessibilityPlatform.collectRootsOnActiveDisplay()` (line 227) filters out `TYPE_INPUT_METHOD` windows before passing roots to `Perceptor.snapshot()`. The keyboard nodes are never traversed, so `isKnownKeyboardNode()` never fires and `keyboardFlag` stays false. The detection logic is in the wrong place — it needs to check the window type list, not the node tree.

Fix: detect keyboard visibility from the `windows` list directly in `collectRootsOnActiveDisplay()` by checking `windows.any { it.type == TYPE_INPUT_METHOD }`, then pass that boolean to the caller alongside roots.

### 3. Should-fix: skip both
Acknowledged. Both "formatting verification guard" and "never self-report failure" are overfit to eval and skipped for R3.

---

### Extra 1: Never-run tasks vs group_5

**22 tasks in aw_fullset.txt have never appeared in any scoreboard run.** They are **exactly** the 22 tasks in `aw_subset_group_5.txt`:

| Task | In group_5? |
|------|-------------|
| RecipeAddMultipleRecipesFromMarkor2 | yes |
| RecipeDeleteDuplicateRecipes3 | yes |
| RecipeDeleteSingleWithRecipeWithNoise | yes |
| SystemBluetoothTurnOffVerify | yes |
| SystemBluetoothTurnOnVerify | yes |
| SystemBrightnessMaxVerify | yes |
| SystemBrightnessMinVerify | yes |
| SystemWifiTurnOffVerify | yes |
| SystemWifiTurnOnVerify | yes |
| SimpleCalendarFirstEventAfterStartTime | yes |
| SimpleCalendarEventsInTimeRange | yes |
| TasksHighPriorityTasksDueOnDate | yes |
| TasksDueNextWeek | yes |
| TasksCompletedTasksForDate | yes |
| TasksIncompleteTasksOnDate | yes |
| SportsTrackerActivitiesCountForWeek | yes |
| SportsTrackerActivityDuration | yes |
| SportsTrackerLongestDistanceActivity | yes |
| SportsTrackerTotalDurationForCategoryThisWeek | yes |
| SportsTrackerTotalDistanceForCategoryOverInterval | yes |
| NotesIsTodo | yes |
| NotesTodoItemCount | yes |

**Perfect match.** All never-run tasks are in group_5, and all group_5 tasks are never-run.

Qi Note: R3里，把group_5都跑一遍。这样所有task至少跑过一遍。外加几个targeted修复的task。

### Extra 2: Per-app success rate (loose: at least 1 pass in most recent 3 runs)

Excludes 5 cannot_handle tasks (MarkorTranscribeVideo, OsmAndMarker, OsmAndTrack, ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2). Only counts tasks that have appeared in the scoreboard.

| App | Success / Total | Rate | Failing Tasks |
|-----|-----------------|------|---------------|
| **System** | 7/7 | 100% | — |
| **SimpleSms** | 6/6 | 100% | — |
| **SimpleCalendar** | 15/15 | 100% | — |
| **Contacts** | 2/2 | 100% | — |
| **Camera** | 2/2 | 100% | — |
| **Files** | 2/2 | 100% | — |
| **Notes** | 2/2 | 100% | — |
| **Tasks** | 2/2 | 100% | — |
| **Multi-app** | 2/2 | 100% | — (TurnOff/TurnOn combo tasks) |
| **Misc 1-task** | 3/3 | 100% | — (OpenApp, SimpleDrawPro, SaveCopyOfReceipt) |
| **Markor** | 10/13 | 77% | AddNoteHeader, MergeNotes, TranscribeReceipt* |
| **Retro** | 3/4 | 75% | PlaylistDuration |
| **Recipe** | 6/9 | 67% | AddMultipleFromImage, AddMultipleFromMarkor, DeleteDuplicateRecipes3* (never run) |
| **Expense** | 6/7 | 86% | AddMultipleFromGallery |
| **Clock** | 2/3 | 67% | TimerEntry |
| **SportsTracker** | 1/1 | 100% | — (only 1 task run so far) |
| **AudioRecorder** | 1/2 | 50% | RecordAudioWithFileName |
| **Browser** | 1/3 | 33% | Draw, Multiply |
| **OsmAnd** | 1/1 | 100% | — (2 others are cannot_handle) |
| **Vlc** | 0/2 | **0%** | CreatePlaylist, CreateTwoPlaylists |

*MarkorTranscribeReceipt: 1/2, counted as success under loose definition.

**Summary**: 14 apps at 100%. Problem apps: **Vlc** (0%), **Browser** (33%), **AudioRecorder** (50%). Mid-tier: Clock (67%), Recipe (67%), Retro (75%), Markor (77%). Overall: **71/82 tasks succeed** (87%) under loose definition across all run tasks (excluding cannot_handle).

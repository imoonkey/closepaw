# Group 2/3 Failed Tasks Rerun — Summary Analysis

**Run ID**: 20260227_142214
**Date**: 2026-02-27
**Tasks**: 13 (12 with results + 1 infra failure)
**Model**: qwen3.5 | **Max Turns**: 30 | **Perception**: accessibility_only (hybrid for MarkorTranscribeReceipt)

---

## Results Overview

| # | Task | Prev Score | New Score | Change | Turns | Stop Reason |
|---|------|-----------|-----------|--------|-------|-------------|
| 0 | ExpenseDeleteDuplicates2 | 0.0 | 0.0 | — | 17 | GoalAchieved (false) |
| 1 | MarkorAddNoteHeader | 0.0 | 0.0 | — | 9 | ForceComplete |
| 2 | **MarkorCreateFolder** | 0.0 | **1.0** | **+1** | 5 | GoalAchieved |
| 3 | MarkorEditNote | 0.0 | 0.0 | — | 9 | ForceComplete |
| 4 | MarkorMergeNotes | 0.0 | 0.0 | — | 21 | ForceComplete |
| 5 | **MarkorTranscribeReceipt** | 0.0 | **1.0** | **+1** | 12 | GoalAchieved |
| 6 | RecipeAddMultipleRecipes | 0.0 | 0.0 | — | 13 | ForceComplete |
| 7 | **RetroSavePlaylist** | 0.5 | **1.0** | **+0.5** | 30 | MaxTurnsReached |
| 8 | SimpleCalendarEventsInNextWeek | 0.0 | 0.0 | — | 11 | ForceComplete |
| 9 | **SimpleCalendarEventsOnDate** | 0.0 | **1.0** | **+1** | 17 | GoalAchieved |
| 10 | SportsTrackerActivitiesOnDate | 0.0 | 0.0 | — | 5 | GoalAchieved (false) |
| 11 | TasksHighPriorityTasks | 0.0 | 0.0 | — | 17 | ForceComplete |
| 12 | VlcCreatePlaylist | N/A | N/A | — | 0 | InfraFailure |

**New pass rate on this subset**: 4/12 = 33% (was 0/12 = 0%, or 0.5/12 with RetroSavePlaylist partial)
**Net improvement**: +3.5 points across 12 tasks

---

## What Improved (4 tasks fixed)

### 1. MarkorCreateFolder (0→1)
- **Fix**: Agent chose UI-only approach from the start instead of attempting shell
- **Attribution**: Shell guardrails in prompt (Section 3) likely steered away from shell; anti-loop would have caught shell failures faster

### 2. MarkorTranscribeReceipt (0→1)
- **Fix**: `perception_mode: hybrid` override in eval config
- **Attribution**: Direct result of improvement design Section 4 (Vision Task Overrides)
- **Impact**: Agent used screenshot to read receipt.png — went from 30 wasted turns to 12 efficient turns

### 3. RetroSavePlaylist (0.5→1)
- **Fix**: Agent completed all 3 songs in correct order within 30 turns
- **Attribution**: Anti-loop improvements avoided false-positive on legitimate song-adding sequence; turn budget visibility helped pace work

### 4. SimpleCalendarEventsOnDate (0→1)
- **Fix**: Agent navigated to correct date and submitted answer via complete_task
- **Attribution**: QA Data Collection Protocol (Section 2) — agent now calls complete_task with collected data instead of running out of turns

---

## What Still Fails (8 tasks) — Categorized

### Category A: Text Editing Limitation (2 tasks)
**Tasks**: MarkorAddNoteHeader, MarkorEditNote

**Common Pattern**: Agent cannot position cursor at the beginning of a text field via accessibility. Both tasks require prepending text to an existing note. The agent tries clicking at the "start" of the EditText but cursor placement is not controllable through accessibility click actions.

**Root Cause**: Fundamental accessibility limitation — click on an EditText doesn't allow specifying cursor position within the text.

**Proposed Fix**:
1. **Shell-based text editing**: For prepend/insert operations, read file via shell → construct new content → write back. E.g., `printf 'Header\n\n' | cat - existing.txt > temp && mv temp existing.txt`
2. **Select-All + Retype**: Long press → Select All → type (header + original content). Agent started this approach on MarkorEditNote turn 6 but abandoned it.
3. **Prompt guidance**: Add explicit instruction that cursor positioning via click is unreliable in text editors. For text prepend tasks, use shell or select-all strategies.

**Note**: Shell had permission issues in MarkorMergeNotes (see Category B). Need to verify if shell write access works for Markor files in the eval environment.

### Category B: Shell Permission + Multi-File Operations (1 task)
**Task**: MarkorMergeNotes

**Pattern**: Shell `cat` on Markor files returns "Permission denied". Agent falls back to UI copy-paste which is fragile (clipboard panel issues). Even after reading content via UI, no efficient way to merge into new file.

**Root Cause**: Markor files at `/sdcard/Documents/Markor/` are not readable by shell in the eval environment (different user/permission context). Combined with the inherent difficulty of multi-file merge via UI accessibility.

**Proposed Fix**:
1. **Investigate shell permissions**: Determine if the permission issue is fixable at the eval environment level. If Markor files were shell-accessible, merge would be trivial.
2. **UI accumulate strategy**: Read each file via UI → scratchpad to accumulate → type merged content into new file. This was what the agent started doing at turns 16-20 but ran out of anti-loop patience.
3. **More lenient anti-loop for multi-file tasks**: The navigating-between-files pattern (open file → read → back → open next) should not be classified as a loop.

### Category C: Anti-Loop False Positive (1 task)
**Task**: RecipeAddMultipleRecipes

**Pattern**: Agent was efficiently filling form fields (click field → type value → click next field → type next value) when anti-loop incorrectly identified this as a "repeated action loop" at turn 13. The agent had only completed ~50% of the FIRST recipe.

**Root Cause**: Anti-loop detector treats sequential click→type→click→type as repetitive, but each interaction targets a DIFFERENT element with DIFFERENT content. This is legitimate sequential form filling, not a loop.

**Proposed Fix**:
1. **Differentiate form-filling from loops**: If successive tool calls target different `element_index` values and/or type different `text`, they are NOT a loop even if the tool name pattern repeats.
2. **Progress detection**: Check if the screen state changes meaningfully between turns (new fields filled, new text visible). If screen is progressing, don't escalate.
3. **Turn budget**: Even without false-positive, this task needs ~42 turns for 3 recipes. Consider per-task turn override.

### Category D: QA Protocol Not Followed (2 tasks)
**Tasks**: SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks

**Common Pattern**: Agent navigates to the right place but never extracts/records data. Scrolls around or opens individual items without using scratchpad to accumulate findings. Eventually force-completed with no answer.

**Root Cause**: QA protocol from improvement design Section 2 is either not in the prompt or not effective enough for these specific scenarios.

**SimpleCalendarEventsInNextWeek specifics**: Agent switched to weekly view but couldn't identify events from the accessibility tree. Scrolled up/down without reading screen elements. May be a perception issue — weekly view might not expose event titles in the a11y tree.

**TasksHighPriorityTasks specifics**: Agent sorted by priority (good) but then inefficiently opened individual tasks to check priority instead of reading them from the sorted list. The sorted list view should show priority indicators.

**Proposed Fix**:
1. **Mandatory scratchpad usage for QA**: "Before scrolling or navigating away, read ALL visible elements and record relevant data in scratchpad"
2. **Strategy for weekly/list views**: "After sorting or switching views, extract data from the current screen BEFORE clicking into individual items"
3. **Partial answer submission**: "If < 5 turns remaining without an answer, call complete_task with whatever you've collected"

### Category E: QA Field Semantics (1 task)
**Task**: SportsTrackerActivitiesOnDate

**Pattern**: Agent answered with track NAMES ("Active Rest Day, Mindful Movement") instead of ACTIVITY TYPES (the sport/category like "walking", "yoga"). The goal explicitly asks for "activity type only."

**Root Cause**: The OpenTracks list view shows track display names, not activity type categories. The agent confused display name with activity type. The QA field semantics guidance in Section 2 of improvement design addressed this, but the agent still made the error.

**Proposed Fix**:
1. **Stronger field semantics**: "When asked for 'activity type', this means the CATEGORY (running, walking, cycling) not the track/session name"
2. **Verification step**: "Open at least one item to verify you're reading the correct field before submitting"
3. **OpenTracks-specific**: Consider adding app-specific hints if the pattern persists

### Category F: False Completion (1 task)
**Task**: ExpenseDeleteDuplicates2

**Pattern**: Agent deleted 1 duplicate (Seminars) but missed others. Claimed success with detailed reasoning about why "Jeans" entries weren't duplicates (different dates/amounts), but eval still scored 0.

**Root Cause**: Either agent's definition of "exact duplicate" is too strict, or there were duplicates the agent missed entirely. The agent actively debated whether Jeans entries were duplicates, suggesting the duplicate criteria are ambiguous.

**Proposed Fix**:
1. **Investigate eval expectations**: What duplicates does the eval expect to be deleted? This will clarify if the agent's logic was wrong or the task setup is ambiguous.
2. **Pre-completion verification**: After deletions, re-scan the full list and compare before/after
3. **Broader duplicate matching**: Consider same name + similar amount as "duplicate" even if dates differ

---

## Anti-Loop Escalation Assessment

The 3-tier anti-loop system (from improvement design Section 1) is **active and functioning**:

**Correctly triggered** (5 tasks):
- MarkorAddNoteHeader (turn 9): Repeated click-to-position-cursor pattern
- MarkorEditNote (turn 9): Same pattern
- SimpleCalendarEventsInNextWeek (turn 11): Scroll loop in weekly view
- TasksHighPriorityTasks (turn 17): One-by-one task checking loop
- MarkorMergeNotes (turn 21): Multi-strategy failure cycle

**False positive** (1 task):
- RecipeAddMultipleRecipes (turn 13): Sequential form filling misidentified as loop

**Not triggered** (correct):
- ExpenseDeleteDuplicates2, SportsTrackerActivitiesOnDate: Agent completed (falsely) before loop detected
- MarkorCreateFolder, MarkorTranscribeReceipt, RetroSavePlaylist, SimpleCalendarEventsOnDate: Passed

**Assessment**: Anti-loop prevents MaxTurnsReached waste (was 10/12 in previous run, now 1/12). But it has a false-positive problem with form-filling patterns. The force-complete ensures the runner gets `interaction_cache` data, but the cache contains a generic failure message rather than partial results.

---

## Systemic Issues (Ordered by Impact)

### 1. Text Editing via Accessibility (HIGH — 3 tasks affected)
The agent cannot prepend, insert, or position cursor in text editors. This blocks MarkorAddNoteHeader, MarkorEditNote, and reduces effectiveness in MarkorMergeNotes. Need either shell-based editing (if permissions allow) or Select-All+Retype strategy.

### 2. Anti-Loop False Positive on Form Filling (HIGH — 1 task, but generalizable)
RecipeAddMultipleRecipes shows that sequential form filling triggers anti-loop. This pattern affects ANY multi-form task (adding multiple contacts, events, etc.). The loop detector needs to differentiate "same tool, different target+content" from "same tool, same target, same content."

### 3. QA Data Extraction Discipline (MEDIUM — 3 tasks affected)
SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks, and SportsTrackerActivitiesOnDate all fail because the agent doesn't systematically read and record screen data. The QA protocol needs to be stronger and more prescriptive.

### 4. Shell Permission in Eval Environment (MEDIUM — 1 task directly, but impacts text editing fix)
Shell can't read Markor files due to permission denied. If this could be fixed at the eval environment level, it would unblock shell-based text editing strategies (fixing issue #1) and shell-based file merging.

### 5. Turn Budget for Multi-Step Tasks (LOW — 1 task)
RecipeAddMultipleRecipes needs ~42 turns but has 30 max. Even without anti-loop false positive, this task would fail. Needs per-task turn override.

---

## Recommended Next Actions

### Immediate (prompt/config changes)
1. **Text editing strategy in prompt**: Add guidance for prepend/insert operations — use Select-All+Retype or shell-based editing
2. **Anti-loop form-filling exemption**: Modify loop detector to check if successive tool calls target different elements with different content
3. **Stronger QA discipline**: "ALWAYS scratchpad-record visible data before scrolling/navigating. Submit partial answer if < 5 turns remain."
4. **Per-task turn override**: RecipeAddMultipleRecipes needs `max_turns: 50`

### Investigation needed
5. **Shell permissions**: Can the eval environment grant shell read/write access to `/sdcard/Documents/Markor/`? If yes, many Markor tasks become simpler.
6. **ExpenseDeleteDuplicates2 eval criteria**: What exactly does the eval expect as "duplicate"? Agent's logic may be correct but misaligned with eval definition.
7. **Weekly view a11y**: Does Simple Calendar's weekly view expose event titles in the accessibility tree? If not, agent needs an alternative navigation strategy.

### Projected impact if fixes applied
- Text editing fix: +2 tasks (MarkorAddNoteHeader, MarkorEditNote)
- Anti-loop fix: +1 task (RecipeAddMultipleRecipes, with turn override)
- QA discipline: +1-2 tasks (TasksHighPriorityTasks likely, SimpleCalendarEventsInNextWeek maybe)
- SportsTracker field fix: +1 task
- **Projected new pass rate**: 8-9/12 = 67-75% (up from current 33%)

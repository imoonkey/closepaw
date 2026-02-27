# Group 3 Eval Analysis — Summary

**Run ID**: 20260227_002312
**Model**: qwen3.5 (via OpenRouter)
**Perception**: accessibility_only
**Max Turns**: 30
**Tasks**: 20 (from aw_subset_group_3.txt)

## Results Overview

| Metric | Value |
|--------|-------|
| **Success Rate** | **65% (13/20)** |
| **Full Pass** | 12 tasks |
| **Partial Pass** | 1 task (0.5) |
| **Fail** | 6 tasks |
| **Infra Failure** | 1 task |
| **Median Duration** | 112s |
| **P90 Duration** | 351s |

## Per-Task Results

| # | Task | Score | Turns | Reason | Root Cause Category |
|---|------|-------|-------|--------|---------------------|
| 1 | ExpenseDeleteMultiple2 | 1.0 | 30 | MaxTurns | Success (but slow) |
| 2 | MarkorCreateNoteFromClipboard | 1.0 | 9 | GoalAchieved | Success |
| 3 | MarkorDeleteNote | 1.0 | 5 | GoalAchieved | Success |
| 4 | **MarkorMergeNotes** | **0.0** | 30 | MaxTurns | Reasoning + Execution |
| 5 | **MarkorTranscribeReceipt** | **0.0** | 30 | MaxTurns | Perception (capability gap) |
| 6 | NotesMeetingAttendeeCount | 1.0 | 6 | GoalAchieved | Success |
| 7 | NotesRecipeIngredientCount | 1.0 | 6 | GoalAchieved | Success |
| 8 | OsmAndFavorite | 1.0 | 8 | GoalAchieved | Success |
| 9 | RecipeDeleteDuplicateRecipes | 1.0 | 13 | GoalAchieved | Success |
| 10 | RecipeDeleteMultipleRecipesWithConstraint | 1.0 | 4 | GoalAchieved | Success |
| 11 | **RetroSavePlaylist** | **0.5** | 30 | MaxTurns | Reasoning (wasted turns) |
| 12 | SaveCopyOfReceiptTaskEval | 1.0 | 15 | GoalAchieved | Success |
| 13 | **SimpleCalendarEventsInNextWeek** | **0.0** | 30 | MaxTurns | Reasoning (scroll loop) |
| 14 | SimpleDrawProCreateDrawing | 1.0 | 28 | GoalAchieved | Success |
| 15 | SimpleSmsResend | 1.0 | 11 | GoalAchieved | Success |
| 16 | SimpleSmsSendReceivedAddress | 1.0 | 11 | GoalAchieved | Success |
| 17 | **SportsTrackerActivitiesOnDate** | **0.0** | 5 | GoalAchieved | Observation/Eval gap |
| 18 | **TasksHighPriorityTasks** | **0.0** | 30 | MaxTurns | Reasoning (UI loop) |
| 19 | TurnOnWifiAndOpenApp | 1.0 | 5 | GoalAchieved | Success |
| 20 | **VlcCreatePlaylist** | **N/A** | 0 | Infra failure | Infrastructure |

## Common Problem Patterns

### P1: Scroll/Navigation Loop (3 tasks — SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks, MarkorMergeNotes)

**Pattern**: Agent gets stuck alternating between scroll up/down or repeatedly opening and closing UI elements without making progress toward the goal.

**Evidence**:
- SimpleCalendarEventsInNextWeek: Turns 14-30 = pure scroll up/down alternation in event list
- TasksHighPriorityTasks: Turns 6-30 = open task → back → discard → open next task, repeating
- MarkorMergeNotes: Turns 17-28 = repeated shell `find` commands returning empty

**Impact**: 3 tasks failed (15% of total)

**Root Cause**: The agent lacks:
1. Loop detection — cannot recognize when it's repeating the same actions without progress
2. Strategy escalation — no mechanism to switch to a different approach after N failed attempts
3. State accumulation — never uses scratchpad to track what it's already tried or found

### P2: No Data Collection Strategy for QA Tasks (2 tasks — SimpleCalendarEventsInNextWeek, TasksHighPriorityTasks)

**Pattern**: For QA tasks that require gathering information from multiple screens/views, the agent scrolls around but never systematically records what it sees.

**Evidence**:
- SimpleCalendarEventsInNextWeek: Agent saw event dates and titles while scrolling but never used scratchpad to record them; never called complete_task
- TasksHighPriorityTasks: Agent opened individual tasks but never recorded which had high priority; never called complete_task

**Impact**: 2 tasks failed (10% of total)

**Root Cause**: Missing prompt guidance for QA data-collection pattern:
1. Browse/scroll to find relevant data
2. Record each finding in scratchpad
3. When enough data collected (or all data seen), synthesize answer
4. Call complete_task with compiled answer

### P3: Vision/Image Capability Gap (1 task — MarkorTranscribeReceipt)

**Pattern**: Task requires reading visual content (image text) but agent runs in accessibility_only mode with no vision input.

**Evidence**:
- MarkorTranscribeReceipt: Agent opened receipt.png in Gallery but couldn't extract any text content. Tried shell workarounds (strings, hexdump, base64) — all failed.

**Impact**: 1 task failed (5% of total)

**Root Cause**: Fundamental limitation — accessibility_only mode cannot read image content. This task is incompatible with the current perception mode.

### P4: Shell/Filesystem Path Issues (1 task — MarkorMergeNotes)

**Pattern**: Agent tries to read files via shell commands but can't find them, even though they're visible in the app UI.

**Evidence**:
- MarkorMergeNotes: Files visible in Markor UI (tough_frog_2023_08_05.txt, etc.) but `find /sdcard -name "*.txt"` returned empty. Directory listing showed `/sdcard/Documents/markor/.app` only.

**Impact**: 1 task failed (5% of total, contributing to MarkorMergeNotes failure)

**Root Cause**: Possible file path issue — Markor may store files transiently or with different permissions. The shell user may not have read access to the Markor documents directory.

### P5: Wasted Turns from Incorrect Actions (1 task — RetroSavePlaylist)

**Pattern**: Agent performs the same action twice (duplicate song addition), wasting turns.

**Evidence**:
- RetroSavePlaylist: Added "Chasing Shadows" to playlist twice (turns 12-14 and 15-17), wasting 3 turns. Then ran out of turns during export.

**Impact**: 1 partial failure (0.5 score)

**Root Cause**: Agent doesn't verify action results before repeating. No memory of which songs have already been added.

### P6: Answer Format / Semantic Mismatch (1 task — SportsTrackerActivitiesOnDate)

**Pattern**: Agent completes the task efficiently and provides an answer, but scores 0.0 despite GoalAchieved.

**Evidence**:
- SportsTrackerActivitiesOnDate: Agent answered "Active Rest Day, Mindful Movement" but scored 0.0. The goal says "Answer with the activity type only" — the agent may have provided activity NAMES (Active Rest Day) instead of activity TYPES (e.g., "rest", "movement"). Or the expected answer uses different terminology.

**Impact**: 1 task scored 0.0 despite correct navigation

**Root Cause**: Semantic misunderstanding of "activity type" vs "activity name" in OpenTracks

## Proposed Changes

### Change 1: Anti-Loop Detection (addresses P1)
**Impact**: 3 tasks
**Files**: Agent prompt/system prompt, possibly agent orchestration code

Add loop detection logic:
- Track last N actions — if same (action, element) pair appears 3+ times, force strategy change
- In system prompt: "If you've tried the same approach 3 times without progress, try a completely different strategy"
- Consider adding turn budget awareness: "You have N turns remaining"

### Change 2: QA Data Collection Pattern in Prompt (addresses P2)
**Impact**: 2 tasks
**Files**: System prompt / agent prompt

Add explicit QA strategy guidance:
```
For information-gathering tasks (questions about app content):
1. Navigate to the relevant view
2. Use scratchpad to record each piece of information you find
3. Scroll/navigate to see all relevant data
4. Use scratchpad to compile your final answer
5. Call complete_task with your compiled answer
IMPORTANT: Always call complete_task before running out of turns, even with partial data.
```

### Change 3: Early Failure Recognition (addresses P3)
**Impact**: 1 task
**Files**: System prompt

Add guidance:
```
If a task requires reading image/visual content and you're in accessibility_only mode
(no screenshot input), recognize this limitation early and call complete_task with
status="failure" and explanation rather than wasting turns.
```

### Change 4: Markor File Path Knowledge (addresses P4)
**Impact**: 1+ tasks
**Files**: System prompt or tool descriptions

Add Markor-specific knowledge:
```
Markor stores files at /sdcard/Documents/Markor/ (capital M).
When reading Markor files via shell, use: cat "/sdcard/Documents/Markor/<filename>"
```

### Change 5: Action Verification Before Repeat (addresses P5)
**Impact**: 1 task
**Files**: System prompt

Add guidance:
```
Before performing an action you've already done (like adding a song to a playlist),
verify the current state to avoid duplicate actions.
```

### Change 6: QA Answer Semantics (addresses P6)
**Impact**: 1 task
**Files**: System prompt

Add guidance:
```
For QA tasks, pay careful attention to what is being asked:
- "activity type" = the category (e.g., running, walking, cycling)
- "activity name/title" = the specific name given to the activity
Read the question carefully and answer with exactly what is asked.
```

## Priority Ranking

1. **Change 2** (QA data collection) — highest impact, 2 tasks, easy prompt change
2. **Change 1** (anti-loop) — 3 tasks affected, medium complexity
3. **Change 6** (QA semantics) — easy prompt fix, 1 task
4. **Change 4** (Markor paths) — easy knowledge addition, 1+ tasks
5. **Change 5** (duplicate action prevention) — prompt guidance, 1 task
6. **Change 3** (vision limitation) — fundamental capability gap, needs mode awareness

## Comparison with Previous Groups

| Group | Tasks | Success Rate | Key Issues |
|-------|-------|-------------|------------|
| Group 1 (20 tasks) | Core + calendar + expense + camera | ~70% | Swipe reliability, eval script issues |
| Group 2 (20 tasks) | Mixed apps | ~65-70% | Similar scroll/navigation issues |
| **Group 3 (20 tasks)** | **Diverse: Notes, OsmAnd, Sports, Tasks** | **65%** | **Scroll loops, QA data collection, vision gap** |

The scroll loop and QA data collection patterns are recurring across groups, suggesting these are systemic issues worth addressing in the agent's prompt and reasoning architecture.

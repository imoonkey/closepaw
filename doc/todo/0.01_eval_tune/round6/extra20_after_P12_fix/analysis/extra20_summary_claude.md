# Extra 20 Tasks — Evaluation Summary (Claude Analysis)

## Overview

| Metric | Value |
|---|---|
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Perception | accessibility_only (no screenshots) |
| Total Tasks | 20 (12 unique task definitions, 8 with retries or separate runs) |
| Max Turns | 30 per task |
| Eval Runs | 3 (20260224_222320, 20260224_225315, 20260224_230158) |
| **Pass Rate** | **1/20 (5.0%)** |

## Results by Task

| # | Task | Turns | Completion | Score | Root Cause Category |
|---|------|-------|------------|-------|---------------------|
| 1 | AudioRecorderRecordAudio | 0 | infra_failure | 0.0 | Infra: App not installed + retry bug |
| 2 | AudioRecorderRecordAudio (retry) | 0 | infra_failure | 0.0 | Infra: Same |
| 3 | AudioRecorderRecordAudioWithFileName | 14 | GoalAchieved | 0.0 | Cognitive: Premature completion (skipped filename) |
| 4 | BrowserDraw | 21 | GoalAchieved | 0.0 | Perception: Can't see color values in a11y tree |
| 5 | BrowserMaze | 30 | GoalAchieved | 0.0 | Perception: Can't see maze grid in a11y tree |
| 6 | CameraTakeVideo | 6 | GoalAchieved | 0.0 | Cognitive: Never switched to video mode |
| 7 | ClockStopWatchPausedVerify | 4 | GoalAchieved | 0.0 | Cognitive: Confused "stopped" with "paused" |
| 8 | ClockStopWatchRunning | 4 | GoalAchieved | 0.0 | Ambiguous: Likely false negative (validator timing) |
| 9 | ContactsNewContactDraft | 9 | GoalAchieved | 0.0 | Ambiguous: Likely app mismatch with validator |
| 10 | ExpenseAddMultiple | 30 | MaxTurnsReached | 0.0 | Turn Budget: 2/3 expenses done, 3rd 90% complete |
| 11 | ExpenseAddMultipleFromGallery | 0 | infra_failure | 0.0 | Infra: ADB cleanup timeout |
| 12 | ExpenseAddMultipleFromGallery (retry) | 0 | infra_failure | 0.0 | Infra: Same |
| 13 | ExpenseAddMultipleFromMarkor | 30 | MaxTurnsReached | 0.0 | Cognitive/Perception: Couldn't extract file content |
| 14 | **ExpenseDeleteDuplicates** | **14** | **GoalAchieved** | **1.0** | **N/A — Success** |
| 15 | SimpleCalendarAddOneEvent | 30 | MaxTurnsReached | 0.0 | App Resolution + Date Picker Navigation |
| 16 | SimpleCalendarAddOneEventInTwoWeeks | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |
| 17 | SimpleCalendarAddOneEventRelativeDay | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |
| 18 | SimpleCalendarAddOneEventTomorrow | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |
| 19 | SimpleCalendarAddRepeatingEvent | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |
| 20 | SimpleCalendarDeleteOneEvent | 30 | MaxTurnsReached | 0.0 | App Resolution + Date Picker Navigation |
| — | SimpleCalendarDeleteEvents | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |
| — | SimpleCalendarDeleteEventsOnRelativeDay | 0 | ASK_USER_BLOCKED | 0.0 | Agent Architecture: Blocked on ask_user |

*Note: Runs 16-20 and the last 2 are from Run 3 (20260224_230158) which had 8 tasks, 6 ASK_USER_BLOCKED. Total unique task attempt outcomes = 20.*

## Failure Distribution by Root Cause

| Root Cause Category | Count | Tasks |
|---|---|---|
| **ASK_USER_BLOCKED** | 6 | 6 Calendar tasks with relative/ambiguous dates |
| **Infra/Environment** | 4 | AudioRecorderRecordAudio ×2, ExpenseAddMultipleFromGallery ×2 |
| **Perception Limitation** | 2 | BrowserDraw, BrowserMaze |
| **Cognitive Error** | 3 | AudioRecorderRecordAudioWithFileName, CameraTakeVideo, ClockStopWatchPausedVerify |
| **Turn Budget Exhaustion** | 3 | ExpenseAddMultiple, SimpleCalendarAddOneEvent, SimpleCalendarDeleteOneEvent |
| **Cross-App Complexity** | 1 | ExpenseAddMultipleFromMarkor |
| **Ambiguous / Possible False Negative** | 2 | ClockStopWatchRunning, ContactsNewContactDraft |
| **Success** | 1 | ExpenseDeleteDuplicates |

## Cross-Cutting Analysis

### 1. ASK_USER_BLOCKED is the Largest Failure Bucket (6/20 = 30%)

Six calendar tasks never executed a single turn because the agent called `ask_user` (which is blocked in eval). These tasks had relative dates ("tomorrow", "in two weeks", "next Tuesday") or ambiguous references that the model interpreted as needing user clarification.

**Fix**: The system prompt should instruct the agent to never call `ask_user` in eval mode — or better, to resolve relative dates using the device's current date/time rather than asking the user.

**Interesting edge case**: SimpleCalendarAddRepeatingEvent had an *absolute* date ("October 29, 2023") but still triggered ask_user — suggesting the model's ask_user tendency is over-broad and not limited to genuinely ambiguous inputs.

### 2. App Resolution is a Systemic Bottleneck (13 turns lost per Calendar task)

Both SimpleCalendarAddOneEvent and SimpleCalendarDeleteOneEvent lost 13 turns to app resolution: `open_app("Simple Calendar Pro")` fails → try "Calendar" → hit Google sign-in → go home → app drawer → try first Calendar (Google) → sign-in again → try second Calendar (Simple Calendar Pro).

**Fix**: The `open_app` resolver should map "Simple Calendar Pro" to `com.simplemobiletools.calendar.pro`. This single fix would save 12-13 turns per calendar task and likely flip at least one of these from failure to success.

### 3. Perception-Only Mode Creates Hard Ceilings

BrowserDraw and BrowserMaze are fundamentally impossible with accessibility-only perception — canvas elements expose no visual content through the a11y tree. These tasks require screenshot-based perception (vision model).

ExpenseAddMultipleFromMarkor hit a softer version: the EditText a11y node doesn't expose the full file content, only the currently visible portion. The agent couldn't read the file systematically.

**Fix**: For BrowserDraw/Maze, screenshot perception is required. For Markor text extraction, a `read_clipboard` or `read_file` tool would help.

### 4. Cognitive Errors are Addressable via Prompt Engineering

| Task | Error | Prompt Fix |
|---|---|---|
| AudioRecorderRecordAudioWithFileName | Skipped typing the filename | "Verify all goal parameters are fulfilled before completing" |
| CameraTakeVideo | Didn't switch to video mode | "Check current mode in Camera before acting" |
| ClockStopWatchPausedVerify | Confused stopped with paused | "Paused = started then paused (non-zero time)" |

### 5. Turn Budget is Tight for Multi-Step Tasks

Three tasks hit MaxTurnsReached while making real progress:
- **ExpenseAddMultiple**: 2/3 expenses done, 3rd was 90% complete (just needed save click)
- **SimpleCalendarAddOneEvent**: Title + description entered, date stuck at August 2023 (needed October)
- **SimpleCalendarDeleteOneEvent**: Found event, clicked delete — session ended before confirmation

All three would likely succeed with 5-10 more turns. The write_todos overhead (2-5 turns per task) directly contributed to budget exhaustion.

### 6. The Only Success Was a Simple, Single-App Task

ExpenseDeleteDuplicates succeeded because it had:
- Single app (Pro Expense) — no cross-app navigation
- Simple list UI with clear delete affordances
- Limited scope (1 duplicate pair)
- No date picker manipulation
- Correct app resolution on first try

This defines the current capability boundary: single-app, list-based, moderate-complexity tasks in well-known app UIs.

## Priority Recommendations

### P0 — Immediate Fixes (would flip 3+ tasks)

1. **Fix `open_app` resolver for Simple Calendar Pro**: Map the display name to `com.simplemobiletools.calendar.pro`. Impact: saves 12-13 turns on every calendar task.
2. **Disable/guard `ask_user` in eval**: Either remove the tool from the eval tool set or add a system prompt instruction to never use it. Impact: 6 tasks would actually execute.

### P1 — High Impact (would improve 3+ tasks)

3. **Reduce write_todos in eval**: The agent's write_todos calls consume 2-5 turns per task with zero contribution to task completion. Either reduce frequency in the system prompt or remove the tool in eval.
4. **Pre-completion checklist prompt**: Add "Before calling complete_task, verify every parameter in the goal has been addressed" to prevent premature GoalAchieved declarations (AudioRecorderRecordAudioWithFileName, CameraTakeVideo, ClockStopWatchPausedVerify).
5. **Increase turn budget for multi-item tasks**: Tasks that explicitly require 3+ sequential operations (e.g., "Add 3 expenses") should get 40-50 turns.

### P2 — Structural Improvements

6. **Screenshot perception for canvas tasks**: BrowserDraw and BrowserMaze are impossible without vision. Enable hybrid perception for tasks involving canvas/drawing.
7. **File content extraction tool**: For cross-app tasks involving text file reading (ExpenseAddMultipleFromMarkor), a dedicated tool to read file contents would be more reliable than navigating the Markor UI.
8. **App-specific knowledge base**: Common patterns (camera mode switching, calendar date picker navigation, contacts app differentiation) could be provided as reference knowledge to reduce trial-and-error.

### P3 — Validation

9. **Investigate false negatives**: ClockStopWatchRunning and ContactsNewContactDraft show correct agent behavior but scored 0.0. The scripted validators may have timing or app-matching issues.

## Aggregate Statistics

| Metric | Value |
|---|---|
| Total turns executed | 222 (across 20 task attempts) |
| Total tool calls | 228 |
| Total tool failures | 3 |
| Tool success rate | 98.7% |
| Tasks that executed ≥1 turn | 12 |
| Tasks blocked before execution | 8 (6 ASK_USER + 2 infra retry pairs) |
| Tasks hitting MaxTurnsReached | 5 |
| Tasks declaring GoalAchieved (false) | 6 |
| Tasks with correct agent behavior | 3 (1 success + 2 likely false negatives) |
| Average turns for executed tasks | 18.5 |
| Median duration | ~131s |

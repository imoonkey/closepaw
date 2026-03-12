# R41 Starting Point

## Token Baseline (post R39-R40 cuts)

| Layer | Tokens |
|-------|--------|
| System prompt | 715 |
| Tool descriptions | 1,791 |
| App skills (total) | 3,093 |
| **Always-on (prompt + tools)** | **2,506** |

R39-R40 cut 992 tokens (-28.4%) with zero confirmed regressions. Current budget is lean.

## Current Failure State (R39+R40 combined)

24 unique failures out of 51+20 evaluated. After excluding cannot_handle (OsmAnd×2), infra (1), and scorer bugs (1): **18 real failures** from 54 actionable tasks.

### Trace-verified root cause breakdown

| Root Cause | Count | Tasks | Actionable via Prompt? |
|-----------|-------|-------|----------------------|
| FalseCompletion — premature answer | 8 | SimpleCalendarEventsInNextWeek, SportsTrackerActivitiesCountForWeek, TasksDueNextWeek, TasksCompletedTasksForDate, SportsTrackerTotalDistanceCat..., TasksIncompleteTasksOnDate, SimpleCalendarAddOneEventRelativeDay, MarkorMoveNote | **Yes** — 1 system prompt line |
| Markor extension field | 1 | MarkorMergeNotes | **Yes** — 1 skill line |
| R40 non-deterministic flips | 3 | SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, TurnOnWifiAndOpenApp | No — same prompt passed R39 |
| Generalization regression (R36-38) | 2 | RecipeDeleteDuplicateRecipes2/3 | No — anti-overfit |
| Filename mismatch (grounding) | 2 | FilesMoveFile, MarkorMoveNote (also in FalseCompletion) | Maybe — general verification |
| Possible R39 cut regression | 1 | AudioRecorderRecordAudioWithFileName | Maybe — check skill |
| Reasoning error | 1 | RetroPlaylistDuration | No — task ambiguity |
| Scorer/task design bug | 1 | ExpenseAddMultipleFromMarkor | No — propose in eval_tips_proposal.md, human-approved only |
| Infra (LLM stream error) | 1 | BrowserMultiply | No |
| Skill injection unreliable | 1 | VlcCreateTwoPlaylists | No — code fix |
| A11y gap (checked property) | 2 | TasksCompletedTasksForDate, TasksIncompleteTasksOnDate | No — platform |
| Newly unblocked (untested since removal) | 3 | MarkorTranscribeVideo, MarkorDeleteNewestNote, BrowserDraw | Eval to triage |

## Required Reading

- Failure analysis: `doc/autotune/round_39/r39_failure_analysis.md` (trace-verified root causes)
- Codex review: `doc/autotune/round_39/r39_failure_analysis_review_codex.md` (5 corrections to above)
- Per-task files: `doc/autotune/meta/per_task/*.md` (check before proposing fixes)

## R41 Focus: Targeted Add-Back

### Change 1 (HIGH): System prompt — info-extraction verification

Add to Completion section in `StandaloneAgentDef.kt`:
```
For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering.
```
Cost: ~20 tokens. Targets 8 FalseCompletion tasks.

### Change 2 (MEDIUM): Markor skill — extension field

Add to `app/src/main/assets/app_skills/net.gsantner.markor/SKILL.md`:
```
New-file dialog has a separate extension field (default `.md`). Clear it if target has no extension or a different extension.
```
Cost: ~25 tokens. Targets MarkorMergeNotes.

### After eval, investigate:

- AudioRecorderRecordAudioWithFileName: compare R31 skill (22 lines) vs R39 skill (14 lines). Was rename procedure guidance needed?
- BrowserDraw, MarkorTranscribeVideo, MarkorDeleteNewestNote: newly unblocked, evaluate baseline.

## Task List

`eval/config/autotune_round_41.txt` — 23 tasks: R39+R40 failures minus cannot_handle (OsmAnd×2 + MarkorTranscribeVideo).

## Eval Config

- Model: gpt-5.4
- Run: `--remote --parallel 2`

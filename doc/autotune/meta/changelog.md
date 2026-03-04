# Changelog

Rounds before autotune are reconstructed from doc/todo/0.01_eval_tune/ history.

## Autotune Round 2 (2026-03-04, run 20260304_033425, commit eadbf03)
- **Task selection rationale**: 8 directly affected tasks from R1 approved fixes (P1 hint text, P2 completion verification, P3-P4 Markor tips, P5 app workflow tips, Q3 keyboard flag, Q7 agent_thought, Q12 write_todos)
- **Tasks run**: 8
- **Fixes applied**: P1 hint text isShowingHintText, Q7 agent_thought first in 8 schemas, P2 completion verification checklist, P3 Markor extension tip, P4 Markor cursor positioning tip, P5 Retro Music + OpenTracks tips, Q3 keyboard_visible flag, Q12 write_todos re-enabled
- **Result**: 6/8 (75%)
- **Net new passes**: MarkorCreateNote (0→1), MarkorEditNote (0→1), RetroCreatePlaylist (0→1), RetroSavePlaylist (new→1), SportsTrackerActivitiesOnDate (0→1), RecipeDeleteMultipleRecipes (new→1)
- **Regressions**: none
- **Key findings**:
  - P4 cursor positioning tip highly effective — MarkorEditNote done in just 7 turns
  - P1 hint text fix confirmed working — RetroCreatePlaylist, RetroSavePlaylist now pass
  - MarkorAddNoteHeader still 0: `\n` vs `\n\n` blank line interpretation persists
  - RetroPlaylistDuration still 0: agent pre-reads durations individually instead of iterative add-and-check
  - keyboard_visible flag not observed in traces — may need APK rebuild verification
  - write_todos not used by RecipeDeleteMultipleRecipes (3 items too few to benefit)
- **Moved to cannot_handle**: none
- **Task selection rationale**: 4 turn-exhaustion tasks (max_turns=50), 2 Markor no-shell, 2 VLC infra-fixed, 2 investigation (RetroPlaylistDuration, SportsTrackerActivitiesOnDate), 2 regression canaries
- **Tasks run**: 12
- **Fixes applied**: P1-R3 max_turns override, P2-R3 cannot_handle exclusion (OsmAnd*), P3-R3 Markor no-shell directive, P6-R3 VLC init fix
- **Result**: 2/12 (16.7%)
- **Net new passes**: none (both passes are regression canaries: MarkorCreateNote, MarkorEditNote)
- **Regressions**: none
- **Key findings**:
  - `type` action hint text contamination is #1 blocker (3-4 tasks). Root cause: `NodeActionPerformer.setTextOnNode()` reads `node.text` which returns hint text on empty fields. Fix: `node.isShowingHintText` (API 26+).
  - `agent_thought` ordering in tool schemas — currently after `action`, should be before for chain-of-thought reasoning. 8 tools affected.
  - FalseCompletion without verification is #2 (2 tasks after removing cannot_handle).
  - max_turns=50 increase was ineffective — tasks FalseComplete early, not turn-exhausted.
  - LLM retry infra already exists (MAX_RETRIES=5); SSE error was likely misclassified as non-retryable.
  - Perception filters keyboard with no trace → need `keyboardVisible` flag.
  - `write_todos` tool should be re-enabled for multi-step tasks.
- **Moved to cannot_handle**: OsmAndMarker, OsmAndTrack (confirmed R35), ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2 (ambiguous duplicate definition + noise trap)

## Pre-autotune Round 3 (2026-03-01, run 20260301_012237, commit ae6ecbc)
- **Task selection rationale**: Re-run 15 failures from R2 to validate P1-P8 fixes
- **Tasks run**: 15
- **Fixes applied**: P1 type clear=false, P3 Markor nav tip, P4 OsmAnd hybrid, P6 strategy-pivot, P7 OpenTracks tip, P8 VLC teardown
- **Result**: 2/15 (13.3%), up from 0/15 in R2 for this subset
- **Net new passes**: MarkorEditNote, SimpleCalendarEventsInNextWeek
- **Regressions**: none
- **Moved to cannot_handle**: none

## Pre-autotune Round 2 (2026-02-27, run 20260227_222506)
- **Task selection rationale**: Group 4 (20 tasks) + group 2/3 re-runs (8 tasks)
- **Tasks run**: 28
- **Result**: 14/27 scored (51.9%). 5 FalseCompletion, 3 NavigationFailure, 2 ActionFailure, 2 InfraError, 1 TurnExhaustion, 1 PerceptionGap
- **Key findings**: type clear=false needed, OsmAnd inaccessible, strategy-pivot prompt ineffective

## Pre-autotune Round 1 (2026-02-27, run 20260227_161048)
- **Task selection rationale**: Group 4 initial eval (20 tasks)
- **Tasks run**: 21
- **Result**: 7/21 (33.3%)
- **Key findings**: First systematic eval of group 4. Identified ActionFailure and NavigationFailure as top categories.

# Changelog

Rounds before autotune are reconstructed from doc/todo/0.01_eval_tune/ history.

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

# MarkorMoveNote

**Status: PARKED (3 consecutive failures, remaining fix would be eval-specific)**

## R42 (20260312_185423) — gpt-5.4 — FAIL — 3RD CONSECUTIVE
- Score: 0.0 | Turns: 9 | GoalAchieved (false completion) | Duration: 72s
- R42 added substring matching guidance to Markor skill. Agent acknowledged it ("by substring as instructed") but selected first match (`2023_02_13_shy_king_copy.md` at index 16) instead of correct `BlJB_shy_king_copy.md` (index 20). Three files contained "shy_king_copy" as substring. Agent did not differentiate between them.
- No post-move verification despite skill saying "verify the file is gone from source."
- **Assessment**: Further disambiguation (prefer randomized prefix) would be eval-specific — real users don't encounter this pattern. Parking per tuning_principles.md anti-overfit gate.

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 9 | Failed — GoalAchieved. Answer: Moved shy_king_copy.md from StudyGuides to MeetingMinutes in Markor. (still failing)
- Same failure as R39: agent grabbed element 16 (wrong file) instead of element 20 (`BlJB_shy_king_copy.md`). Target was visible on-screen.
- R39 recommendations (app-skill update, prompt reinforcement) were NOT applied between R39 and R41.
- FalseCompletion: target file still in StudyGuides after move; agent did not verify.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 9 | Failed — GoalAchieved. Answer: Moved the note to MeetingMinutes in Markor. (regressed from 1.0)

